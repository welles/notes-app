package dev.welles.notes.app

import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.sync.ApiException
import dev.welles.notes.core.sync.KtorNotesApi
import dev.welles.notes.core.sync.NotesApi
import dev.welles.notes.core.sync.ServerConfig
import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.feature.stickies.data.STICKY_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Fake server whose live stream can be cut; each connection first sends the current version (like the real one). */
private class LiveFakeServer : NotesApi by FakeServer() {
    val inner = FakeServer()
    val pushes = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    var connections = 0
    var failNextConnects = 0

    override suspend fun meta() = inner.meta().copy(capabilities = listOf("sync.v1", "sync.events"))
    override suspend fun pull(since: Long, limit: Int) = inner.pull(since, limit)
    override suspend fun push(changes: List<dev.welles.notes.core.model.PushEnvelope>) =
        inner.push(changes).also { pushes.tryEmit(inner.rows.values.maxOf { it.version }) }

    override fun events(): Flow<Long> = flow {
        connections++
        if (failNextConnects > 0) { failNextConnects--; throw ApiException.Network(RuntimeException("connection refused")) }
        emit(inner.rows.values.maxOfOrNull { it.version } ?: 1)
        pushes.collect { emit(it) }
    }
}

class LiveSyncTest {
    private suspend fun awaitContent(d: Device, id: String, content: String) = withTimeout(10.seconds) {
        while (d.db.stickies.get(id)?.content != content) delay(10.milliseconds)
    }

    @Test
    fun change_on_one_device_arrives_on_the_other_without_polling() = runBlocking {
        val server = LiveFakeServer()
        val a = Device("a", { server })
        val b = Device("b", { server })
        try {
            a.controller.syncNow(); b.controller.syncNow()
            b.live.start()
            withTimeout(5.seconds) { b.live.connected.first { it } }

            val id = a.db.stickies.create(Contexts.DEFAULT_ID, "live")
            a.controller.syncNow()
            awaitContent(b, id, "live")
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun reconnects_with_backoff_and_catches_up_on_missed_changes() = runBlocking {
        val server = LiveFakeServer()
        val a = Device("a", { server })
        val b = Device("b", { server })
        try {
            a.controller.syncNow(); b.controller.syncNow()
            server.failNextConnects = 3 // server "down" for the first attempts
            // Change made while b is not connected – must arrive via the initial event after reconnecting.
            val id = a.db.stickies.create(Contexts.DEFAULT_ID, "verpasst")
            a.controller.syncNow()
            b.live.start()

            awaitContent(b, id, "verpasst")
            assertTrue(server.connections >= 4, "connections: ${server.connections}")
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun no_live_connection_to_servers_without_the_capability() = runBlocking {
        val server = LiveFakeServer()
        val old = object : NotesApi by server {
            override suspend fun meta() = server.inner.meta() // only sync.v1
        }
        val b = Device("b", { old })
        try {
            b.controller.syncNow()
            b.live.start()
            delay(300.milliseconds)
            assertEquals(0, server.connections)
        } finally {
            b.close()
        }
    }

    /**
     * Measurement against a real server (-Pnotes.e2eServer=…): 20 edits on device A, time until device B has them.
     * Prints median / p95 / max.
     */
    @Test
    fun latency_against_real_server() = runBlocking {
        val url = System.getProperty("notes.e2eServer").orEmpty()
        if (url.isEmpty()) return@runBlocking
        val api = { c: ServerConfig -> KtorNotesApi(c, ClientInfo("desktop", "e2e")) }
        val a = Device("a", api, url)
        val b = Device("b", api, url)
        try {
            a.db.contexts.ensureDefaultContext(); b.db.contexts.ensureDefaultContext()
            val id = a.db.stickies.create(Contexts.DEFAULT_ID, "0")
            a.controller.syncNow(); b.controller.syncNow()
            b.live.start()
            withTimeout(10.seconds) { b.live.connected.first { it } }

            val samples = (1..20).map { i ->
                a.db.stickies.update(id, "$i", "yellow", false)
                val start = TimeSource.Monotonic.markNow()
                a.controller.syncNow()
                awaitContent(b, id, "$i")
                start.elapsedNow().inWholeMilliseconds
            }.sorted()
            println("LIVE-SYNC LATENCY ms (push on A → applied on B), n=20: median=${samples[10]} p95=${samples[18]} max=${samples.last()} all=$samples")
            assertTrue(samples.last() < 3000, "max ${samples.last()} ms")
            assertTrue(a.db.db.outboxDao().all().none { it.entityType == STICKY_TYPE })
        } finally {
            a.close(); b.close()
        }
    }
}
