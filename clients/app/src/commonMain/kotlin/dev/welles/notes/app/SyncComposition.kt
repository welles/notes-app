package dev.welles.notes.app

import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.core.sync.KtorNotesApi
import dev.welles.notes.core.sync.LiveSync
import dev.welles.notes.core.sync.SyncController
import dev.welles.notes.core.sync.SyncEngine
import dev.welles.notes.core.ui.SettingsSection
import dev.welles.notes.core.ui.SyncIndicator
import dev.welles.notes.core.ui.SyncSettingsSection
import dev.welles.notes.core.ui.TopBarAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.bind
import org.koin.dsl.module

/** Sync engine + controller. The platform provides [ClientInfo] and a [dev.welles.notes.core.sync.CredentialStore]. */
val syncModule = module {
    single { SyncEngine(get(), get(), get(), get(), getAll()) }
    single {
        val info = get<ClientInfo>()
        SyncController(
            engine = get(),
            credentials = get(),
            client = info,
            apiFactory = { config -> KtorNotesApi(config, info) },
            outbox = get(),
            stateDao = get(),
            contexts = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    single {
        val info = get<ClientInfo>()
        LiveSync(get(), get(), { config -> KtorNotesApi(config, info) }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    single { SyncIndicator(get()) } bind TopBarAction::class
    single { SyncSettingsSection(get()) } bind SettingsSection::class
}
