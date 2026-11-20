package dev.welles.notes.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.welles.notes.app.NotesApp
import dev.welles.notes.app.desktopDatabaseModule
import dev.welles.notes.app.notesModules
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun main() {
    val startupError = runCatching {
        startKoin { modules(notesModules(platform = module { }, database = desktopDatabaseModule())) }
        // Open the database early so a newer schema is reported instead of crashing later.
        org.koin.core.context.GlobalContext.get().get<dev.welles.notes.app.NotesDatabase>()
    }.exceptionOrNull()

    application {
        Window(onCloseRequest = ::exitApplication, title = "Notes", state = rememberWindowState(width = 1000.dp, height = 720.dp)) {
            NotesApp(startupError = startupError)
        }
    }
}
