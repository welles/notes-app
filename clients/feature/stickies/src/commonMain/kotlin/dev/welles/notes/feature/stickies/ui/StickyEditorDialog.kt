package dev.welles.notes.feature.stickies.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.welles.notes.core.model.NoteColorNames
import dev.welles.notes.core.ui.ColorSwatches
import dev.welles.notes.core.ui.LocalSnackbar
import dev.welles.notes.core.ui.MarkdownHighlight
import dev.welles.notes.core.ui.NoteColors
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Creating and editing look the same: a large post-it floating over the board, so a new one already looks
 * like the post-it it becomes. Saves automatically when closed (back, tap outside, ✓), like Keep.
 * [id] null creates a new post-it (only if something was entered).
 */
@Composable
fun StickyEditorDialog(id: String?, onClose: () -> Unit) {
    val vm: StickyEditorViewModel = koinViewModel()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == null) }
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(NoteColorNames.DEFAULT) }
    var pinned by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(id) {
        if (id != null) vm.load(id)?.let { s ->
            text = s.text; color = s.color; pinned = s.pinned
        }
        loaded = true
        if (id == null) runCatching { focus.requestFocus() }
    }

    val latest by rememberUpdatedState(EditorState(text, color, pinned, deleted, loaded))
    DisposableEffect(id) {
        onDispose {
            val s = latest
            if (s.loaded && !s.deleted) vm.save(id, s.text, s.color, s.pinned)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Tapping next to the post-it closes it (and saves).
        Box(
            Modifier.fillMaxSize().clickable(MutableInteractionSource(), indication = null, onClick = onClose)
                .safeDrawingPadding().imePadding().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            StickyPaper(
                color = color,
                fold = 30.dp,
                elevation = 16.dp,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 300.dp)
                    // Taps on the paper itself must not close it.
                    .clickable(MutableInteractionSource(), indication = null) { runCatching { focus.requestFocus() } },
            ) {
                Column(Modifier.padding(start = 20.dp, top = 18.dp, end = 12.dp, bottom = 8.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoteColors.text),
                        cursorBrush = SolidColor(NoteColors.text),
                        visualTransformation = MarkdownHighlight,
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()).focusRequester(focus),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("Notiz …", style = MaterialTheme.typography.bodyLarge, color = NoteColors.text.copy(alpha = .45f))
                                inner()
                            }
                        },
                    )
                    // Tools along the bottom edge, leaving the folded corner free.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, end = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box {
                            ToolIcon(Icons.Filled.Palette, "Farbe") { colors = true }
                            DropdownMenu(colors, { colors = false }) {
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ColorSwatches(color, { color = it; colors = false }) }
                            }
                        }
                        ToolIcon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (pinned) "Lösen" else "Anpinnen") { pinned = !pinned }
                        if (id != null) ToolIcon(Icons.Filled.Delete, "Löschen") {
                            deleted = true
                            vm.delete(id)
                            onClose()
                            scope.launch {
                                if (snackbar.showSnackbar("Post-it gelöscht", "Rückgängig", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.restore(id)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ToolIcon(Icons.Filled.Check, "Fertig", onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, label, tint = NoteColors.text, modifier = Modifier.size(22.dp)) }

private data class EditorState(val text: String, val color: String, val pinned: Boolean, val deleted: Boolean, val loaded: Boolean)
