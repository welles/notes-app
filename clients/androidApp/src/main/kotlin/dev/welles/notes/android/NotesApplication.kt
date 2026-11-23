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
import org.koin.dsl.module
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.welles.notes.core.sync.AppUpdater
import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.core.sync.CredentialStore
import dev.welles.notes.core.sync.QrScanner
import dev.welles.notes.core.sync.SyncController
import dev.welles.notes.core.sync.LiveSync
import dev.welles.notes.core.ui.AppUpdateIndicator
import dev.welles.notes.core.ui.AppUpdateSection
import dev.welles.notes.core.ui.SettingsSection
import dev.welles.notes.core.ui.TopBarAction
import dev.welles.notes.feature.stickies.widget.stickiesAndroidModule
import org.koin.dsl.bind

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
        if (startupError != null) return

        val sync = GlobalContext.get().get<SyncController>()
        val live = GlobalContext.get().get<LiveSync>()
        val updater = GlobalContext.get().getOrNull<AppUpdater>()
        SyncWorker.schedule(this)
        // Foreground: sync now and keep the live connection; background: only WorkManager.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                sync.requestSync()
                live.start()
                updater?.checkIfDue()
            }
            override fun onStop(owner: LifecycleOwner) = live.stop()
        })
    }

    /** Android-only parts: secure credential storage, client identity, QR scanner, self-update (release builds only: debug builds are signed with another key). */
    private fun extraModules(): List<Module> = listOf(
        module {
            single<CredentialStore> { AndroidCredentialStore(get()) }
            single { ClientInfo("android", BuildConfig.VERSION_NAME) }
            single<QrScanner> { GmsQrScanner(get()) }
        },
        stickiesAndroidModule,
    ) + listOfNotNull(
        if (BuildConfig.DEBUG) null else module {
            single<AppUpdater> { AndroidAppUpdater(get(), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) }
            single { AppUpdateIndicator(get()) } bind TopBarAction::class
            single { AppUpdateSection(get()) } bind SettingsSection::class
        },
    )
}
