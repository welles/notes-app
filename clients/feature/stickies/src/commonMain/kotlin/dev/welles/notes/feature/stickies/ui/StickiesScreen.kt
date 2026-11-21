package dev.welles.notes.feature.stickies.ui


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.data.CaptureTarget
import dev.welles.notes.core.ui.ColorSwatches
import dev.welles.notes.core.ui.LocalActiveContext
import dev.welles.notes.core.ui.LocalSnackbar
import dev.welles.notes.core.ui.MoveToContextDialog
import dev.welles.notes.core.ui.Navigator
import dev.welles.notes.core.ui.NoteColors
import dev.welles.notes.core.ui.markdownAnnotated
import dev.welles.notes.core.ui.collectAsStateCompat
import dev.welles.notes.feature.stickies.data.STICKY_TYPE
import dev.welles.notes.feature.stickies.data.Sticky
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import dev.welles.notes.core.sync.SyncController
import dev.welles.notes.core.sync.SyncStatus
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StickiesScreen(navigator: Navigator, routeEdit: EditRequest? = null) {
    val vm: StickiesViewModel = koinViewModel()
    val sync: SyncController = koinInject()
    val syncState by sync.state.collectAsStateCompat()
    val stickies by vm.stickies.collectAsStateCompat()
    val contexts by vm.contextList.collectAsStateCompat()
    val active = LocalActiveContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    // Local order while dragging; persisted on drop.
    var items by remember { mutableStateOf(emptyList<Sticky>()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(stickies) { if (!dragging) items = stickies.orEmpty() }

    // Opened from the board: local state, so the board keeps its scroll position.
    // Opened via route (new post-it, widget, desktop window): closing goes back.
    var editing by remember { mutableStateOf<EditRequest?>(null) }
    var moving by remember { mutableStateOf<Sticky?>(null) }
    var converting by remember { mutableStateOf<Pair<Sticky, CaptureTarget>?>(null) }
    val desktop: dev.welles.notes.feature.stickies.StickyDesktopPlacement? = org.koin.compose.getKoin().getOrNull()
    val convertTargets = if (vm.targets.conversionAvailable) vm.targets.all.filter { it.entityType != STICKY_TYPE } else emptyList()

    fun delete(s: Sticky) {
        vm.delete(s)
        scope.launch {
            val r = snackbar.showSnackbar("Post-it gelöscht", actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(s.id)
        }
    }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIdx = items.indexOfFirst { it.id == from.key }
        val toIdx = items.indexOfFirst { it.id == to.key }
        // Only within the same section (pinned / others); pinning is done via the pin button.
        if (fromIdx >= 0 && toIdx >= 0 && items[fromIdx].pinned == items[toIdx].pinned) {
            items = items.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        }
    }

    if (stickies == null) return
    val pinned = items.filter { it.pinned }
    val others = items.filter { !it.pinned }

    // Pull-to-refresh triggers a sync (Android; harmless on desktop).
    PullToRefreshBox(isRefreshing = syncState.status == SyncStatus.Syncing, onRefresh = sync::requestSync, modifier = Modifier.fillMaxSize()) {
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.StickyNote2, null, Modifier.size(48.dp).alpha(.6f))
            Text("Keine Post-its", Modifier.padding(top = 12.dp))
        }
    } else LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for ((label, section) in listOf("Angepinnt" to pinned, "Weitere" to others)) {
            if (section.isEmpty()) continue
            if (pinned.isNotEmpty()) item(key = "header-$label", span = { GridItemSpan(maxLineSpan) }) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
            items(section, key = { it.id }) { sticky ->
                ReorderableItem(reorderState, key = sticky.id) { isDragging ->
                    StickyCard(
                        sticky = sticky,
                        dragging = isDragging,
                        convertTargets = convertTargets,
                        modifier = Modifier.longPressDraggableHandle(
                            onDragStarted = { dragging = true },
                            onDragStopped = {
                                dragging = false
                                val group = items.filter { it.pinned == sticky.pinned }
                                val i = group.indexOfFirst { it.id == sticky.id }
                                vm.reorder(sticky.id, group.getOrNull(i - 1)?.id, group.getOrNull(i + 1)?.id)
                            },
                        ),
                        onOpen = { editing = EditRequest(sticky.id) },
                        onPin = { vm.setPinned(sticky, !sticky.pinned) },
                        onColor = { vm.setColor(sticky, it) },
                        onMove = { moving = sticky },
                        onConvert = { converting = sticky to it },
                        onDelete = { delete(sticky) },
                        onPlaceOnDesktop = desktop?.let { d -> { d.place(sticky.id) } },
                    )
                }
            }
        }
    }
    }

    moving?.let { s ->
        MoveToContextDialog(contexts, exclude = s.contextId, onPick = {
            vm.moveToContext(s, it); moving = null
            scope.launch { snackbar.showSnackbar("Post-it verschoben") }
        }, onDismiss = { moving = null })
    }
    converting?.let { (s, target) ->
        AlertDialog(
            onDismissRequest = { converting = null },
            title = { Text("In ${target.displayName} umwandeln") },
            text = { Text("Soll das Post-it danach gelöscht werden?") },
            confirmButton = { TextButton(onClick = { vm.convert(s, target.key, true); converting = null }) { Text("Umwandeln & löschen") } },
            dismissButton = { TextButton(onClick = { vm.convert(s, target.key, false); converting = null }) { Text("Umwandeln & behalten") } },
        )
    }
    if (routeEdit != null) key(routeEdit) { StickyEditorDialog(routeEdit.id, onClose = navigator::back) }
    else editing?.let { e -> key(e) { StickyEditorDialog(e.id, onClose = { editing = null }) } }
}

/** Which post-it the editor shows; [id] null = a new one. */
data class EditRequest(val id: String?)

@Composable
private fun StickyCard(
    sticky: Sticky,
    dragging: Boolean,
    convertTargets: List<CaptureTarget>,
    modifier: Modifier,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onColor: (String) -> Unit,
    onMove: () -> Unit,
    onConvert: (CaptureTarget) -> Unit,
    onDelete: () -> Unit,
    onPlaceOnDesktop: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    StickyPaper(
        color = sticky.color,
        elevation = if (dragging) 12.dp else 3.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp).clickable(onClick = onOpen),
    ) {
        CompositionLocalProvider(LocalContentColor provides NoteColors.text) {
            val text = remember(sticky.text) { markdownAnnotated(sticky.text) }
            Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 36.dp)) {
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
            }
            // Leaves the folded corner free.
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp)) {
                IconButton(onClick = onPin, modifier = Modifier.size(36.dp)) {
                    Icon(if (sticky.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (sticky.pinned) "Lösen" else "Anpinnen", Modifier.size(18.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Mehr", Modifier.size(18.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Farbe …") }, leadingIcon = { Icon(Icons.Filled.Palette, null) }, onClick = { menu = false; colors = true })
                        if (onPlaceOnDesktop != null) DropdownMenuItem(
                            text = { Text("Auf den Desktop legen") }, leadingIcon = { Icon(Icons.Filled.Transform, null) },
                            onClick = { menu = false; onPlaceOnDesktop() },
                        )
                        DropdownMenuItem(text = { Text("In Kontext verschieben …") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { menu = false; onMove() })
                        for (t in convertTargets) DropdownMenuItem(
                            text = { Text("Umwandeln in ${t.displayName} …") }, leadingIcon = { Icon(Icons.Filled.Transform, null) }, onClick = { menu = false; onConvert(t) },
                        )
                        DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
    if (colors) {
        AlertDialog(
            onDismissRequest = { colors = false },
            title = { Text("Farbe") },
            text = { ColorSwatches(sticky.color, { onColor(it); colors = false }) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { colors = false }) { Text("Schließen") } },
        )
    }
}

@Composable
@Suppress("unused")
private fun Spacing() = Spacer(Modifier.size(0.dp))

const val ROUTE_EDIT = "stickies/edit"
