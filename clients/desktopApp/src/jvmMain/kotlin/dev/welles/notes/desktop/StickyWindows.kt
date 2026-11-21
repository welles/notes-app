package dev.welles.notes.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.LocalSetting
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.ui.ColorSwatches
import dev.welles.notes.core.ui.NoteColors
import dev.welles.notes.core.ui.MarkdownHighlight
import dev.welles.notes.core.model.Markdown
import dev.welles.notes.feature.stickies.ui.StickyPaper
import dev.welles.notes.core.ui.NotesTheme
import dev.welles.notes.feature.stickies.StickyDesktopPlacement
import dev.welles.notes.feature.stickies.data.Sticky
import dev.welles.notes.feature.stickies.data.StickyRepository
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Device-local state of one desktop post-it window (core_local_setting, per context). */
@Serializable
data class StickyWindowState(
    val stickyId: String,
    val x: Int,
    val y: Int,
    val width: Int = 260,
    val height: Int = 240,
    val layer: X11Hints.Layer = X11Hints.Layer.Normal,
)

/**
 * Post-its as their own borderless windows directly on the desktop ("Stickies"). Only windows of the active context
 * are shown; on a context switch the old ones close and the new context's ones are restored. Window state is device-local.
 * Each window gets WM_CLASS / role `notes-sticky` and the title "Notes-Post-it: …" for KWin window rules.
 */
class DesktopStickyWindows(
    private val repository: StickyRepository,
    private val contexts: ContextRepository,
    private val settings: LocalSettingsDao,
    private val scope: CoroutineScope,
) : StickyDesktopPlacement {
    private val json = Json { ignoreUnknownKeys = true }
    private val windows = mutableMapOf<String, ComposeWindow>()
    private var states = mutableListOf<StickyWindowState>()
    private var contextId: String = Contexts.DEFAULT_ID
    private var saveJob: Job? = null

    /** Opens the post-it in the main window (set by Main). */
    var openInMainWindow: (String) -> Unit = {}

    fun start() = scope.launch(Dispatchers.Swing) {
        contexts.activeContextId.map { it ?: Contexts.DEFAULT_ID }.distinctUntilChanged().collect { ctx ->
            closeAll(persist = false)
            contextId = ctx
            states = load(ctx).toMutableList()
            for (s in states.toList()) {
                val sticky = repository.get(s.stickyId)
                if (sticky == null || sticky.deleted || sticky.contextId != ctx) states.remove(s) else open(s)
            }
            save()
        }
    }

    override fun place(stickyId: String) {
        scope.launch(Dispatchers.Swing) {
            windows[stickyId]?.let { it.toFront(); return@launch }
            val offset = 40 * (states.size % 8)
            val state = StickyWindowState(stickyId, 120 + offset, 120 + offset)
            states.add(state)
            open(state)
            save()
        }
    }

    fun openWindowIds(): Set<String> = windows.keys.toSet()

    private fun open(state: StickyWindowState) {
        val window = ComposeWindow()
        window.isUndecorated = true
        window.title = "Notes-Post-it"
        window.type = java.awt.Window.Type.NORMAL
        window.isAlwaysOnTop = state.layer == X11Hints.Layer.Above
        window.setBounds(state.x, state.y, state.width, state.height)
        window.setContent {
            NotesTheme {
                StickyWindowContent(
                    stickyId = state.stickyId,
                    window = window,
                    initialLayer = state.layer,
                    repository = repository,
                    onLayer = { setLayer(state.stickyId, it) },
                    onClose = { close(state.stickyId) },
                    onOpenInMain = { openInMainWindow(state.stickyId) },
                    onGone = { close(state.stickyId) },
                )
            }
        }
        // Peer (X window) exists after addNotify: set EWMH hints before the window is mapped.
        window.addNotify()
        X11Hints.prepare(window, state.layer, wmClass = "notes-sticky", role = "notes-sticky")
        window.isVisible = true
        // Some WMs (Muffin) take skip-taskbar/sticky from the pre-map property but the layer only via client message.
        X11Hints.reassert(window)
        if (state.layer != X11Hints.Layer.Normal) scope.launch(Dispatchers.Swing) { delay(200.milliseconds); X11Hints.setLayer(window, state.layer) }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = boundsChanged(state.stickyId, window)
            override fun componentResized(e: ComponentEvent) = boundsChanged(state.stickyId, window)
        })
        windows[state.stickyId] = window
    }

    private fun boundsChanged(id: String, window: ComposeWindow) {
        val i = states.indexOfFirst { it.stickyId == id }
        if (i < 0) return
        states[i] = states[i].copy(x = window.x, y = window.y, width = window.width, height = window.height)
        save()
    }

    private fun setLayer(id: String, layer: X11Hints.Layer) {
        val window = windows[id] ?: return
        window.isAlwaysOnTop = layer == X11Hints.Layer.Above
        X11Hints.setLayer(window, layer)
        val i = states.indexOfFirst { it.stickyId == id }
        if (i >= 0) states[i] = states[i].copy(layer = layer)
        save()
    }

    /** Takes the post-it off the desktop (the post-it itself stays). */
    fun close(id: String) {
        scope.launch(Dispatchers.Swing) {
            windows.remove(id)?.dispose()
            states.removeAll { it.stickyId == id }
            save()
        }
    }

    private fun closeAll(persist: Boolean) {
        windows.values.forEach { it.dispose() }
        windows.clear()
        if (persist) save()
    }

    /** Throttled write of the window states of the current context. */
    private fun save() {
        val ctx = contextId
        val snapshot = states.toList()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300.milliseconds)
            settings.put(LocalSetting(key(ctx), json.encodeToString(ListSerializer(StickyWindowState.serializer()), snapshot)))
        }
    }

    private suspend fun load(ctx: String): List<StickyWindowState> =
        settings.get(key(ctx))?.let { runCatching { json.decodeFromString(ListSerializer(StickyWindowState.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun key(ctx: String) = "desktop.stickyWindows.$ctx"
}

@OptIn(ExperimentalComposeUiApi::class, FlowPreview::class)
@Composable
private fun StickyWindowContent(
    stickyId: String,
    window: ComposeWindow,
    initialLayer: X11Hints.Layer,
    repository: StickyRepository,
    onLayer: (X11Hints.Layer) -> Unit,
    onClose: () -> Unit,
    onOpenInMain: () -> Unit,
    onGone: () -> Unit,
) {
    val sticky by remember { repository.observe(stickyId, Unit) }.collectAsState(null)
    var loaded by remember { mutableStateOf<Sticky?>(null) }
    var text by remember { mutableStateOf("") }
    var hovered by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var layer by remember { mutableStateOf(initialLayer) }

    // Initial load + live updates from sync (unless the user is typing the same text).
    LaunchedEffect(Unit) {
        repository.observe(stickyId, Unit).collect { s ->
            if (s == null || s.deleted) { onGone(); return@collect }
            // Changed elsewhere (sync, main window)? Take it over; own saves come back identical and change nothing.
            val prev = loaded
            if (prev == null || s.text != prev.text) text = s.text
            loaded = s
        }
    }
    // Autosave while typing.
    LaunchedEffect(Unit) {
        snapshotFlow { text }.drop(1).debounce(600.milliseconds).collect { t ->
            val s = repository.get(stickyId) ?: return@collect
            if (t != s.text) repository.update(stickyId, t, s.color, s.pinned)
        }
    }
    LaunchedEffect(sticky?.text) {
        window.title = "Notes-Post-it: " + Markdown.toPlainText(sticky?.text.orEmpty()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(40)
    }

    val s = sticky ?: return
    StickyPaper(
        color = s.color,
        fold = 0.dp,
        elevation = 0.dp,
        modifier = Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
    Column(Modifier.fillMaxSize()) {
        // Drag bar (moves the window) + tools, visible on hover.
        Row(
            Modifier.fillMaxWidth().height(30.dp).moveWindow(window).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            if (hovered) {
                Box {
                    ToolButton(Icons.Filled.Palette, "Farbe") { colorMenu = true }
                    DropdownMenu(colorMenu, { colorMenu = false }) {
                        Box(Modifier.padding(8.dp)) { ColorSwatches(s.color, { c -> colorMenu = false; kotlinx.coroutines.MainScope().launch { repository.setColor(stickyId, c) } }) }
                    }
                }
                val next = when (layer) { X11Hints.Layer.Normal -> X11Hints.Layer.Above; X11Hints.Layer.Above -> X11Hints.Layer.Below; X11Hints.Layer.Below -> X11Hints.Layer.Normal }
                ToolButton(
                    when (layer) { X11Hints.Layer.Above -> Icons.Filled.VerticalAlignTop; X11Hints.Layer.Below -> Icons.Filled.VerticalAlignBottom; else -> Icons.Filled.Layers },
                    "Ebene: ${layerLabel(layer)} → ${layerLabel(next)}",
                ) { layer = next; onLayer(next) }
                ToolButton(Icons.Filled.OpenInNew, "Im Hauptfenster öffnen", onOpenInMain)
                ToolButton(Icons.Filled.Close, "Vom Desktop nehmen", onClose)
            }
        }
        BasicTextField(
            text, { text = it },
            textStyle = TextStyle(color = NoteColors.text, fontSize = 14.sp),
            cursorBrush = SolidColor(NoteColors.text),
            visualTransformation = MarkdownHighlight,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Box(Modifier.fillMaxWidth().height(14.dp)) {
            Box(Modifier.align(Alignment.BottomEnd).size(14.dp).resizeWindow(window))
        }
    }
    }
}

private fun layerLabel(l: X11Hints.Layer) = when (l) { X11Hints.Layer.Normal -> "normal"; X11Hints.Layer.Above -> "immer oben"; X11Hints.Layer.Below -> "auf dem Desktop" }

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(26.dp)) { Icon(icon, label, tint = NoteColors.text, modifier = Modifier.size(16.dp)) }

/** Moves an undecorated window with the mouse (absolute screen coordinates – no jitter). */
private fun Modifier.moveWindow(window: ComposeWindow) = pointerInput(Unit) {
    var start = Point(); var origin = Point()
    detectDragGestures(onDragStart = { start = MouseInfo.getPointerInfo().location; origin = window.location }) { change, _ ->
        change.consume()
        val now = MouseInfo.getPointerInfo().location
        window.setLocation(origin.x + now.x - start.x, origin.y + now.y - start.y)
    }
}

private fun Modifier.resizeWindow(window: ComposeWindow) = pointerInput(Unit) {
    var start = Point(); var size = java.awt.Dimension()
    detectDragGestures(onDragStart = { start = MouseInfo.getPointerInfo().location; size = window.size }) { change, _ ->
        change.consume()
        val now = MouseInfo.getPointerInfo().location
        window.setSize((size.width + now.x - start.x).coerceAtLeast(140), (size.height + now.y - start.y).coerceAtLeast(100))
    }
}

private object Dispatchers {
    val Swing get() = kotlinx.coroutines.Dispatchers.Swing
}

