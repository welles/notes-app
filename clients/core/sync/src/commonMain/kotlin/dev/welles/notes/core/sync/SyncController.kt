package dev.welles.notes.core.sync

import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.OutboxDao
import dev.welles.notes.core.data.SyncStateDao
import dev.welles.notes.core.data.SyncStateEntry
import dev.welles.notes.core.model.SyncTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Ok(val at: String) : SyncStatus
    data class Failed(val message: String, val credentials: Boolean, val offline: Boolean = false) : SyncStatus
    /** Protocol no longer supported (HTTP 426): sync paused, local data and outbox untouched. */
    data object UpdateRequired : SyncStatus
}

data class SyncUiState(
    val status: SyncStatus = SyncStatus.Idle,
    val lastSync: String? = null,
    val pending: Int = 0,
    val waitingForServer: Int = 0,
    /** Own version below the server's minClientVersion: non-blocking hint. */
    val updateRecommended: Boolean = false,
    val capabilities: Set<String> = emptySet(),
)

/**
 * Runs syncs one at a time, throttles triggers after local changes, retries failures with backoff
 * and exposes a status for the UI. Platform schedulers (WorkManager, desktop timer) just call [syncNow]/[requestSync].
 */
@OptIn(FlowPreview::class)
class SyncController(
    private val engine: SyncEngine,
    private val credentials: CredentialStore,
    private val client: ClientInfo,
    private val apiFactory: (ServerConfig) -> NotesApi,
    private val outbox: OutboxDao,
    private val stateDao: SyncStateDao,
    private val contexts: ContextRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()
    private var retryJob: Job? = null
    private var failures = 0

    // Triggers are coalesced: while a sync runs, at most one more is queued.
    private val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch { triggers.collect { syncNow() } }
    }


    init {
        scope.launch { outbox.observeCount().collect { n -> _state.value = _state.value.copy(pending = n) } }
        scope.launch { outbox.observeWaitingCount().collect { n -> _state.value = _state.value.copy(waitingForServer = n) } }
        scope.launch { _state.value = _state.value.copy(lastSync = stateDaoLastSync()) }
        // After local changes: sync, throttled (~2 s).
        scope.launch {
            outbox.observeCount().drop(1).distinctUntilChanged().filter { it > 0 }.debounce(2.seconds).collect { requestSync() }
        }
        scope.launch { if (credentials.load() == null) _state.value = _state.value.copy(status = SyncStatus.NotConfigured) }
    }

    private suspend fun stateDaoLastSync() = stateDao.get(SyncEngine.KEY_LAST_SYNC)

    /** Fire-and-forget trigger (app start, focus, local change, pull-to-refresh, live hint). */
    fun requestSync() {
        triggers.tryEmit(Unit)
    }

    /** Server version this device has fully pulled. */
    suspend fun cursor(): Long = stateDao.get(SyncEngine.KEY_CURSOR)?.toLongOrNull() ?: 0

    /** Runs one sync now; returns true on success. Used by background workers too. */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val config = credentials.load()
        if (config == null) {
            _state.value = _state.value.copy(status = SyncStatus.NotConfigured)
            return true
        }
        _state.value = _state.value.copy(status = SyncStatus.Syncing)
        try {
            val result = engine.sync(apiFactory(config))
            contexts.checkActiveStillExists()
            val now = SyncTime.now(clock)
            stateDao.put(SyncStateEntry(SyncEngine.KEY_LAST_SYNC, now))
            failures = 0
            retryJob?.cancel()
            _state.value = _state.value.copy(
                status = SyncStatus.Ok(now),
                lastSync = now,
                updateRecommended = result.meta.minClientVersion[client.platform]?.let { isOlder(client.version, it) } ?: false,
                capabilities = result.meta.capabilities.toSet(),
            )
            true
        } catch (e: ApiException.ProtocolUnsupported) {
            _state.value = _state.value.copy(status = SyncStatus.UpdateRequired)
            false
        } catch (e: ApiException.Unauthorized) {
            _state.value = _state.value.copy(status = SyncStatus.Failed(e.message ?: "Zugangsdaten prüfen", credentials = true))
            false
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = SyncStatus.Failed(e.message ?: e.toString(), credentials = false, offline = e is ApiException.Network))
            scheduleRetry()
            false
        }
    }

    /** Exponential backoff: 10 s, 20 s, 40 s … up to 10 min. */
    private fun scheduleRetry() {
        failures++
        val wait: Duration = (10.seconds * (1 shl (failures - 1).coerceAtMost(6))).coerceAtMost(10.minutes)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            syncNow()
        }
    }

    /** Desktop: sync periodically while the app runs. */
    fun startPeriodic(every: Duration) = scope.launch {
        while (true) {
            delay(every)
            syncNow()
        }
    }

    suspend fun testConnection(config: ServerConfig): Result<String> = runCatching {
        val api = apiFactory(config)
        val meta = api.meta()
        api.pull(0, 1)
        "Verbunden mit Notes ${meta.serverVersion} (${meta.entityTypes.size} Typen)"
    }

    suspend fun saveCredentials(config: ServerConfig) {
        credentials.save(config.copy(url = config.url.trim().trimEnd('/'), clientId = config.clientId.trim(), clientSecret = config.clientSecret.trim()))
        _state.value = _state.value.copy(status = SyncStatus.Idle)
        requestSync()
    }

    suspend fun loadCredentials(): ServerConfig? = credentials.load()

    companion object {
        /** Compares dotted versions (1.4.0 < 1.10.0); suffixes like -dev are ignored. */
        fun isOlder(version: String, minimum: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(version)
            val b = parts(minimum)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x < y
            }
            return false
        }
    }
}
