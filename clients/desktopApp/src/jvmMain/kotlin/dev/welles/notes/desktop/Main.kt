package dev.welles.notes.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.welles.notes.app.DesktopCredentialStore
import dev.welles.notes.app.NotesApp
import dev.welles.notes.app.NotesDatabase
import dev.welles.notes.app.desktopDatabaseModule
import dev.welles.notes.app.notesModules
import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.core.sync.CredentialStore
import dev.welles.notes.core.sync.SyncController
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

val VERSION: String = System.getProperty("notes.version") ?: DesktopApp::class.java.`package`?.implementationVersion ?: "0.1.0-dev"

private object DesktopApp

fun main() {
    val platform = module {
        single<CredentialStore> { DesktopCredentialStore() }
        single { ClientInfo("desktop", VERSION) }
    }
    val startupError = runCatching {
        startKoin { modules(notesModules(platform = platform, database = desktopDatabaseModule())) }
        // Open the database early so a newer schema is reported instead of crashing later.
        runBlocking { GlobalContext.get().get<NotesDatabase>().contextDao().all() }
    }.exceptionOrNull()

    val sync = if (startupError == null) GlobalContext.get().get<SyncController>() else null
    sync?.requestSync()
    sync?.startPeriodic(5.minutes)

    application {
        Window(onCloseRequest = ::exitApplication, title = "Notes", state = rememberWindowState(width = 1000.dp, height = 720.dp)) {
            // Window focus → sync.
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) { sync?.requestSync() }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            NotesApp(startupError = startupError)
        }
    }
}
