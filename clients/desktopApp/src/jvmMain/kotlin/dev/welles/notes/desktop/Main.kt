package dev.welles.notes.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.welles.notes.app.DesktopCredentialStore
import dev.welles.notes.app.NotesApp
import dev.welles.notes.app.NotesDatabase
import dev.welles.notes.app.desktopDatabaseModule
import dev.welles.notes.app.notesModules
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.EntityActions
import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.core.sync.CredentialStore
import dev.welles.notes.core.sync.SyncController
import dev.welles.notes.feature.stickies.StickyDesktopPlacement
import java.awt.SystemTray
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module

val VERSION: String = System.getProperty("notes.version") ?: DesktopApp::class.java.`package`?.implementationVersion ?: "0.1.0-dev"

private object DesktopApp

private const val USAGE = """Notes – persönliche Notizen

  notes                      Hauptfenster öffnen (startet Notes, falls nötig)
  notes --capture            Schnellerfassung (für ein globales Tastenkürzel)
  notes --capture-text TEXT  Post-it ohne Fenster anlegen (Skripte)
  notes --background         im Hintergrund starten (Autostart), ohne Hauptfenster
  notes --quit               laufende Instanz beenden
"""

/** WM_CLASS "notes" for the main window (matches StartupWMClass in the .desktop file); post-it windows use "notes-sticky". */
private fun setWmClass() = runCatching {
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, "notes")
}

fun main(args: Array<String>) {
    val argList = args.toList()
    setWmClass()
    if ("--help" in argList || "-h" in argList) return println(USAGE)

    // A second start hands its arguments to the running instance and exits.
    if (SingleInstance.forward(argList.ifEmpty { listOf("--show") })) return
    if ("--quit" in argList) return

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val platform = module {
        single<CredentialStore> { DesktopCredentialStore() }
        single { ClientInfo("desktop", VERSION) }
        single { DesktopStickyWindows(get(), get(), get(), scope) } bind StickyDesktopPlacement::class
    }
    val startupError = runCatching {
        startKoin { modules(notesModules(platform = platform, database = desktopDatabaseModule())) }
        runBlocking { GlobalContext.get().get<NotesDatabase>().contextDao().all() }
    }.exceptionOrNull()

    val koin = GlobalContext.getOrNull()
    val sync = if (startupError == null) koin?.get<SyncController>() else null

    // --capture-text without a running instance: store, try one quick sync, done.
    argList.indexOf("--capture-text").takeIf { it >= 0 }?.let { i ->
        val text = argList.getOrNull(i + 1)?.trim().orEmpty()
        if (startupError != null || text.isEmpty()) exitProcess(1)
        runBlocking {
            koin!!.get<ContextRepository>().ensureDefaultContext()
            koin.get<EntityActions>().capture(CaptureContent(null, text))
            withTimeoutOrNull(15.seconds) { sync?.syncNow() }
        }
        println("Post-it angelegt")
        exitProcess(0)
    }

    val showMain = MutableStateFlow("--background" !in argList && "--capture" !in argList)
    val captureOpen = MutableStateFlow("--capture" in argList)
    val navigation = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val stickyWindows = koin?.getOrNull<DesktopStickyWindows>()
    stickyWindows?.openInMainWindow = { id -> showMain.value = true; navigation.tryEmit("stickies/edit?id=$id") }

    var quit: () -> Unit = { exitProcess(0) }
    SingleInstance.listen { forwarded ->
        when {
            "--quit" in forwarded -> quit()
            "--capture" in forwarded -> captureOpen.value = true
            "--capture-text" in forwarded -> {
                val text = forwarded.getOrNull(forwarded.indexOf("--capture-text") + 1)?.trim().orEmpty()
                if (text.isNotEmpty() && koin != null) scope.launch { koin.get<EntityActions>().capture(CaptureContent(null, text)) }
            }
            "--background" in forwarded -> Unit
            else -> showMain.value = true
        }
    }

    if (sync != null) {
        sync.requestSync()
        sync.startPeriodic(5.minutes)
        // Desktop post-its are always visible, so the live connection stays open while Notes runs.
        koin!!.get<dev.welles.notes.core.sync.LiveSync>().start()
        stickyWindows?.start()
    }

    application(exitProcessOnExit = true) {
        quit = ::exitApplication
        val mainVisible by showMain.collectAsState()
        val capturing by captureOpen.collectAsState()
        val windowState = rememberWindowState(width = 1000.dp, height = 720.dp)

        // The main window stays in the composition (hidden when closed) so Notes keeps running in the background.
        Window(
            onCloseRequest = { showMain.value = false },
            visible = mainVisible,
            title = "Notes",
            state = windowState,
            icon = appIcon(),
        ) {
            LaunchedEffect(mainVisible) { if (mainVisible) { window.toFront(); window.requestFocus() } }
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) { sync?.requestSync() }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            NotesApp(startupError = startupError, navigationRequests = navigation)
        }

        if (capturing && koin != null) {
            var contextName by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                val contexts = koin.get<ContextRepository>()
                contexts.ensureDefaultContext()
                val id = contexts.activeIdOrDefault()
                contextName = contexts.contexts.collectFirst()?.firstOrNull { it.id == id }?.name ?: ""
            }
            CaptureWindow(
                contextName = contextName,
                onSave = { text ->
                    captureOpen.value = false
                    scope.launch { koin.get<EntityActions>().capture(CaptureContent(null, text)) }
                },
                onClose = { captureOpen.value = false },
            )
        }

        // Tray icon only where AWT supports one (X11 XEmbed tray, e.g. Cinnamon). KDE Plasma 6 only offers SNI → no icon.
        if (SystemTray.isSupported()) {
            Tray(icon = appIcon() ?: return@application, tooltip = "Notes", onAction = { showMain.value = true }) {
                Item("Notes öffnen") { showMain.value = true }
                Item("Schnell notieren") { captureOpen.value = true }
                Item("Beenden") { exitApplication() }
            }
        }
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectFirst(): T? = firstOrNull()

private var cachedIcon: BitmapPainter? = null
private fun appIcon(): BitmapPainter? = cachedIcon ?: runCatching {
    DesktopApp::class.java.getResourceAsStream("/notes.png")?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
}.getOrNull()?.also { cachedIcon = it }
