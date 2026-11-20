package dev.welles.notes.app

import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.core.sync.CredentialStore
import dev.welles.notes.core.sync.LiveSync
import dev.welles.notes.core.sync.NotesApi
import dev.welles.notes.core.sync.ServerConfig
import dev.welles.notes.core.sync.SyncController
import dev.welles.notes.core.sync.SyncEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MemoryCredentials(var config: ServerConfig?) : CredentialStore {
    override suspend fun load() = config
    override suspend fun save(config: ServerConfig) { this.config = config }
    override suspend fun clear() { config = null }
}

/** A complete simulated device: own database, sync controller and live sync (real clock, real coroutines). */
class Device(val name: String, api: (ServerConfig) -> NotesApi, url: String = "http://test", minBackoff: Duration = 50.milliseconds) {
    val db = TestDb().also { it.clock.live = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val credentials = MemoryCredentials(ServerConfig(url))
    val controller = SyncController(
        engine = SyncEngine(db.store, db.db.outboxDao(), db.db.syncStateDao(), db.tx),
        credentials = credentials,
        client = ClientInfo("desktop", "test"),
        apiFactory = api,
        outbox = db.db.outboxDao(),
        stateDao = db.db.syncStateDao(),
        contexts = db.contexts,
        scope = scope,
    )
    val live = LiveSync(controller, credentials, api, scope, minBackoff = minBackoff, maxBackoff = minBackoff * 4)

    fun close() {
        live.stop()
        scope.cancel()
        db.close()
    }
}
