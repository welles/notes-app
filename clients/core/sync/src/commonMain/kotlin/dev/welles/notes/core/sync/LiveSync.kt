package dev.welles.notes.core.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live sync while the app is in the foreground: keeps a Server-Sent-Events connection open and triggers the normal
 * sync when the server reports a version newer than ours. Servers without the capability `sync.events`
 * are left alone (polling as before).
 */
class LiveSync(
    private val controller: SyncController,
    private val credentials: CredentialStore,
    private val apiFactory: (ServerConfig) -> NotesApi,
    private val scope: CoroutineScope,
    private val minBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 10.seconds,
) {
    private var job: Job? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    private suspend fun run() {
        var backoff = minBackoff
        while (scope.isActive) {
            val config = credentials.load()
            // Capabilities are known after the first successful sync.
            val capabilities = controller.state.first { it.capabilities.isNotEmpty() || config == null }.capabilities
            if (config == null || "sync.events" !in capabilities) {
                delay(60.seconds)
                continue
            }
            try {
                apiFactory(config).events().collect { version ->
                    _connected.value = true
                    backoff = minBackoff
                    if (version > controller.cursor()) controller.requestSync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // network gone, server restarted, proxy timeout … → reconnect with backoff
            }
            _connected.value = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoff)
        }
    }
}
