package dev.welles.notes.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import dev.welles.notes.core.data.NotesContext

@Composable
fun ColorDot(color: Color, size: Int = 10, modifier: Modifier = Modifier) =
    Box(modifier.size(size.dp).background(color, CircleShape))

/** Row of colour swatches. */
@Composable
fun ColorSwatches(selected: String?, onSelect: (String) -> Unit, accent: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in NoteColors.names) {
            val c = if (accent) NoteColors.accent(name) else NoteColors.background(name)
            Box(
                Modifier.size(32.dp).background(c, CircleShape)
                    .border(if (name == selected) 3.dp else 1.dp, if (name == selected) NoteColors.text else Color.Black.copy(alpha = .15f), CircleShape)
                    .clickable { onSelect(name) },
            )
        }
    }
}

/** Quick capture dialog: Enter saves, Shift+Enter new line. */
@Composable
fun CaptureDialog(targetName: String, contextName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$targetName in „$contextName“") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Schnell notieren …") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) {
                        if (text.isNotBlank()) onSave(text)
                        true
                    } else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismiss(); true
                    } else false
                },
                minLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Picks a target context for "In Kontext verschieben …". */
@Composable
fun MoveToContextDialog(contexts: List<NotesContext>, exclude: String?, title: String = "In Kontext verschieben …", onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val options = contexts.filter { it.id != exclude }
                if (options.isEmpty()) Text("Es gibt keinen anderen Kontext.")
                for (c in options) {
                    Row(Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(NoteColors.accent(c.color))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
