package dev.welles.notes.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.welles.notes.core.ui.NoteColors
import dev.welles.notes.core.ui.NotesTheme

/**
 * `notes --capture`: small borderless window, Enter saves into the active context, Shift+Enter new line, Esc closes.
 * Global hotkeys are configured in the desktop environment (see README), not in the app.
 */
@Composable
fun CaptureWindow(contextName: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    val state = rememberWindowState(width = 520.dp, height = 150.dp, position = WindowPosition(androidx.compose.ui.Alignment.Center))
    Window(onCloseRequest = onClose, title = "Notes – Schnell notieren", state = state, undecorated = true, alwaysOnTop = true, resizable = false) {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { window.toFront(); window.requestFocus(); focus.requestFocus() }
        NotesTheme {
            Column(Modifier.fillMaxSize().background(NoteColors.background("yellow")).border(1.dp, NoteColors.accent("yellow"), RoundedCornerShape(2.dp)).padding(14.dp)) {
                Text(contextName, style = MaterialTheme.typography.labelSmall, color = NoteColors.text.copy(alpha = .6f))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = NoteColors.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(NoteColors.text),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus).onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            it.key == Key.Escape -> { onClose(); true }
                            it.key == Key.Enter && !it.isShiftPressed -> { if (text.isNotBlank()) onSave(text.trim()) else onClose(); true }
                            else -> false
                        }
                    },
                )
            }
        }
    }
}
