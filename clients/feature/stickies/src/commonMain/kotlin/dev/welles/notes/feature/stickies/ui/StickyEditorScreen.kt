package dev.welles.notes.feature.stickies.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.welles.notes.core.model.NoteColorNames
import dev.welles.notes.core.ui.ColorSwatches
import dev.welles.notes.core.ui.LocalSnackbar
import dev.welles.notes.core.ui.Navigator
import dev.welles.notes.core.ui.NoteColors
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Full-screen editor. Saves automatically when leaving (back), like Keep. */
@Composable
fun StickyEditorScreen(id: String?, navigator: Navigator) {
    val vm: StickyEditorViewModel = koinViewModel()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(NoteColorNames.DEFAULT) }
    var pinned by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(id) {
        if (id != null) vm.load(id)?.let { s ->
            title = s.title.orEmpty(); content = s.content; color = s.color; pinned = s.pinned
        }
        loaded = true
        if (id == null) runCatching { focus.requestFocus() }
    }

    val latest by rememberUpdatedState(EditorState(title, content, color, pinned, deleted, loaded))
    DisposableEffect(id) {
        onDispose {
            val s = latest
            if (s.loaded && !s.deleted) vm.save(id, s.title, s.content, s.color, s.pinned)
        }
    }

    val textColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = NoteColors.text, unfocusedTextColor = NoteColors.text,
        focusedPlaceholderColor = NoteColors.text.copy(alpha = .5f), unfocusedPlaceholderColor = NoteColors.text.copy(alpha = .5f),
        cursorColor = NoteColors.text,
    )

    Column(Modifier.fillMaxSize().background(NoteColors.background(color)).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = title, onValueChange = { title = it }, placeholder = { Text("Titel") }, singleLine = true,
                textStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp), colors = textColors, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { pinned = !pinned }) {
                Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Anpinnen", tint = NoteColors.text)
            }
            if (id != null) IconButton(onClick = {
                deleted = true
                vm.delete(id)
                navigator.back()
                scope.launch {
                    if (snackbar.showSnackbar("Post-it gelöscht", "Rückgängig", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.restore(id)
                }
            }) { Icon(Icons.Filled.Delete, "Löschen", tint = NoteColors.text) }
            IconButton(onClick = navigator::back) { Icon(Icons.Filled.Check, "Fertig", tint = NoteColors.text) }
        }
        TextField(
            value = content, onValueChange = { content = it }, placeholder = { Text("Notiz …") },
            textStyle = MaterialTheme.typography.bodyLarge, colors = textColors, minLines = 8,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorSwatches(color, { color = it })
        }
    }
}

private data class EditorState(val title: String, val content: String, val color: String, val pinned: Boolean, val deleted: Boolean, val loaded: Boolean)
