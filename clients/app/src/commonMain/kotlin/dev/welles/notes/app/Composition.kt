package dev.welles.notes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.welles.notes.core.data.CaptureTargets
import dev.welles.notes.core.data.ContextHandler
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.EntityActions
import dev.welles.notes.core.data.LocalEntityHandler
import dev.welles.notes.core.data.LocalStore
import dev.welles.notes.core.data.TransactionRunner
import dev.welles.notes.core.ui.ContextsViewModel
import dev.welles.notes.core.ui.NotesShell
import dev.welles.notes.core.ui.NotesTheme
import dev.welles.notes.core.ui.ShellViewModel
import dev.welles.notes.feature.stickies.stickiesModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Database + DAOs. The platform provides the builder (file location). */
fun databaseModule(builder: () -> RoomDatabase.Builder<NotesDatabase>, io: CoroutineDispatcher) = module {
    single {
        builder()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(io)
            .build()
    }
    single { get<NotesDatabase>().outboxDao() }
    single { get<NotesDatabase>().syncStateDao() }
    single { get<NotesDatabase>().localSettingsDao() }
    single { get<NotesDatabase>().contextDao() }
    single { get<NotesDatabase>().stickyDao() }
    single<TransactionRunner> { RoomTransactionRunner(get()) }
}

/** Core: generic store, contexts, capture and cross-module actions. */
val coreModule = module {
    single { ContextHandler(get()) } bind LocalEntityHandler::class
    single { LocalStore(getAll(), get(), get()) }
    single { ContextRepository(get(), get(), get()) }
    single { CaptureTargets(getAll()) }
    single { EntityActions(get(), get(), get()) }
    viewModelOf(::ShellViewModel)
    viewModelOf(::ContextsViewModel)
}

/** All modules of the app. A new feature module adds its Koin module here (docs/adding-a-module.md). */
fun notesModules(platform: Module, database: Module, extra: List<Module> = emptyList()): List<Module> =
    listOf(platform, database, coreModule, stickiesModule) + extra

/** Thrown when the local database was written by a newer app version (downgrade is not supported). */
class NewerDatabaseException(val found: Int) : Exception("Database version $found is newer than ${NotesDatabase.VERSION}")

/**
 * Checks the schema version before Room opens the file, so an older app never crashes on (or wipes) a newer database.
 */
fun checkDatabaseVersion(path: String) {
    val connection = runCatching { BundledSQLiteDriver().open(path) }.getOrNull() ?: return
    try {
        val version = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
        if (version > NotesDatabase.VERSION) throw NewerDatabaseException(version)
    } finally {
        connection.close()
    }
}

@Composable
fun NotesApp(
    startupError: Throwable? = null,
    captureRequests: Flow<Unit> = emptyFlow(),
    navigationRequests: Flow<String> = emptyFlow(),
) {
    NotesTheme {
        if (startupError != null) StartupErrorScreen(startupError)
        else NotesShell(captureRequests, navigationRequests)
    }
}

@Composable
private fun StartupErrorScreen(error: Throwable) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Notes kann nicht starten", style = MaterialTheme.typography.headlineSmall)
            if (error is NewerDatabaseException) {
                Text("Die lokalen Daten stammen von einer neueren Notes-Version (Datenbank v${error.found}, diese App kennt v${NotesDatabase.VERSION}).")
                Text("Bitte aktualisiere die App. Ein Downgrade wird nicht unterstützt; die Daten bleiben unverändert erhalten.")
            } else {
                Text(error.message ?: error.toString())
            }
        }
    }
}

