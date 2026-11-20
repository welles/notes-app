package dev.welles.notes.android

import android.app.Application
import dev.welles.notes.app.NotesDatabase
import dev.welles.notes.app.androidDatabaseModule
import dev.welles.notes.app.androidPlatformModule
import dev.welles.notes.app.notesModules
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module

class NotesApplication : Application() {
    /** Error while opening the database (e.g. written by a newer app version) – shown instead of crashing. */
    var startupError: Throwable? = null
        private set

    /** Requests from outside the UI (widget "+", widget tap on a post-it). */
    val captureRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@NotesApplication)
            modules(notesModules(androidPlatformModule(this@NotesApplication), androidDatabaseModule(this@NotesApplication), extraModules()))
        }
        // Open the database now so a newer schema is reported at start instead of crashing later.
        startupError = runCatching { runBlocking { GlobalContext.get().get<NotesDatabase>().contextDao().all() } }.exceptionOrNull()
    }

    /** Android-only additions (sync scheduler, credential store) – filled in by later steps. */
    private fun extraModules(): List<Module> = emptyList()
}
