package dev.welles.notes.app

import dev.welles.notes.core.data.jsonOf
import dev.welles.notes.core.data.str
import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.model.EntityRef
import dev.welles.notes.core.model.NotesJson
import dev.welles.notes.core.model.ProtocolRange
import dev.welles.notes.core.sync.ApiException
import dev.welles.notes.core.sync.SyncEngine
import dev.welles.notes.feature.stickies.data.STICKY_TYPE
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SyncEngineTest {
    private val t = TestDb()
    private val server = FakeServer()
    private fun engine(pageSize: Int = 500, maxPush: Int = 200) =
        SyncEngine(t.store, t.db.outboxDao(), t.db.syncStateDao(), t.tx, emptyList(), pageSize, maxPush)

    @AfterTest
    fun close() = t.close()

    private fun sticky(content: String, ctx: String = Contexts.DEFAULT_ID) = jsonOf(
        "createdAt" to "2026-11-20T08:00:00.000Z", "deletedAt" to null, "contextId" to ctx, "title" to null,
        "content" to content, "color" to "yellow", "pinned" to false, "position" to 0.0,
    )

    @Test
    fun local_changes_are_pushed_and_server_state_applied() = runTest {
        val id = t.stickies.create(Contexts.DEFAULT_ID, "T", "lokal")

        engine().sync(server)

        assertTrue(t.db.outboxDao().all().isEmpty())
        val row = assertNotNull(server.rows[STICKY_TYPE to id])
        assertEquals("lokal", row.data.str("content"))
        assertEquals(row.version, t.stickyHandler.get(id)!!.version)
    }

    @Test
    fun pull_pages_until_done_and_persists_cursor() = runTest {
        repeat(5) { server.put(STICKY_TYPE, "019a8f00-0000-7000-8000-00000000b00$it", sticky("s$it")) }

        engine(pageSize = 2).sync(server)

        assertEquals(5, t.stickies.observe(Contexts.DEFAULT_ID).first().size)
        assertEquals(server.rows.values.maxOf { it.version }.toString(), t.db.syncStateDao().get(SyncEngine.KEY_CURSOR))
    }

    @Test
    fun unknown_types_in_pull_are_skipped_and_cursor_advances_then_update_triggers_full_resync() = runTest {
        server.entityTypes = listOf("core.context", "stickies.sticky", "todos.todo")
        server.put("todos.todo", "019a8f00-0000-7000-8000-00000000c001", jsonOf("text" to "neu"))
        server.put(STICKY_TYPE, "019a8f00-0000-7000-8000-00000000c002", sticky("x"))

        engine().sync(server)
        assertEquals(server.rows.values.maxOf { it.version }.toString(), t.db.syncStateDao().get(SyncEngine.KEY_CURSOR))

        // Simulate: the previous app version knew fewer types → this (updated) client does a full resync.
        t.db.syncStateDao().put(dev.welles.notes.core.data.SyncStateEntry(SyncEngine.KEY_KNOWN_TYPES, "[\"core.context\"]"))
        val result = engine().sync(server)
        assertTrue(result.fullResync)
    }

    @Test
    fun type_rejected_by_older_server_stays_in_outbox_and_is_retried_later() = runTest {
        server.entityTypes = listOf("core.context") // old server without the Post-its module
        val id = t.stickies.create(Contexts.DEFAULT_ID, null, "wartet")

        val first = engine().sync(server)

        val entry = assertNotNull(t.db.outboxDao().get(STICKY_TYPE, id))
        assertTrue(entry.waitingForServer)
        assertEquals(1, first.waitingForServer)
        assertTrue(server.pushes.flatten().none { it.id == id }, "not sent to a server that does not know the type")

        server.entityTypes = listOf("core.context", "stickies.sticky") // server updated
        engine().sync(server)
        assertNull(t.db.outboxDao().get(STICKY_TYPE, id))
        assertNotNull(server.rows[STICKY_TYPE to id])
    }

    @Test
    fun rejected_entry_from_push_result_is_kept() = runTest {
        // Server meta claims the type but rejects it in the push (e.g. module removed meanwhile).
        val id = t.stickies.create(Contexts.DEFAULT_ID, null, "x")
        val rejecting = object : dev.welles.notes.core.sync.NotesApi by server {
            override suspend fun push(changes: List<dev.welles.notes.core.model.PushEnvelope>) =
                dev.welles.notes.core.model.PushResponse(changes.map {
                    dev.welles.notes.core.model.PushEntryResult(it.entityType, it.id, dev.welles.notes.core.model.PushStatus.Rejected, "unknown_type")
                })
        }
        engine().sync(rejecting)
        assertTrue(t.db.outboxDao().get(STICKY_TYPE, id)!!.waitingForServer)
    }

    @Test
    fun batch_entries_travel_in_one_push_and_contexts_come_first() = runTest {
        t.contexts.ensureDefaultContext()
        // Many single changes, then a context created offline with content in it.
        repeat(5) { t.stickies.create(Contexts.DEFAULT_ID, null, "n$it") }
        val ctx = t.contexts.create("Reise")
        val inCtx = t.stickies.create(ctx, null, "im neuen Kontext")
        val source = t.stickies.create(Contexts.DEFAULT_ID, null, "wird umgewandelt")
        // "Move to context" of two entities = one batch
        t.actions.moveToContext(listOf(EntityRef(STICKY_TYPE, source), EntityRef(STICKY_TYPE, inCtx)), Contexts.DEFAULT_ID)

        engine(maxPush = 3).sync(server)

        val firstPush = server.pushes.first()
        assertEquals(Contexts.TYPE, firstPush.first().entityType, "context goes first")
        val pushOfSource = server.pushes.indexOfFirst { p -> p.any { it.id == source } }
        val pushOfInCtx = server.pushes.indexOfFirst { p -> p.any { it.id == inCtx } }
        assertEquals(pushOfSource, pushOfInCtx, "entries of one batch are in the same push")
        assertTrue(server.pushes.all { it.size <= 3 || it.map { e -> e.id }.toSet().size == it.size })
        assertTrue(t.db.outboxDao().all().isEmpty())
    }

    @Test
    fun full_resync_keeps_and_pushes_pending_changes() = runTest {
        server.put(STICKY_TYPE, "019a8f00-0000-7000-8000-00000000d001", sticky("alt"))
        engine().sync(server)
        // Server purged tombstones meanwhile; we were offline and created something.
        server.rows.remove(STICKY_TYPE to "019a8f00-0000-7000-8000-00000000d001")
        server.fullResyncOnce = true
        val pending = t.stickies.create(Contexts.DEFAULT_ID, null, "offline")

        val result = engine().sync(server)

        assertTrue(result.fullResync)
        assertNull(t.stickies.get("019a8f00-0000-7000-8000-00000000d001"))
        assertNotNull(t.stickies.get(pending))
        assertNotNull(server.rows[STICKY_TYPE to pending])
    }

    @Test
    fun unsupported_protocol_leaves_local_data_untouched() = runTest {
        val id = t.stickies.create(Contexts.DEFAULT_ID, null, "x")
        server.protocol = ProtocolRange(2, 3)

        assertFailsWith<ApiException.ProtocolUnsupported> { engine().sync(server) }

        assertNotNull(t.db.outboxDao().get(STICKY_TYPE, id))
        assertTrue(server.pushes.isEmpty())
    }

    @Test
    fun conflict_result_applies_winning_server_state() = runTest {
        val id = "019a8f00-0000-7000-8000-00000000e001"
        server.put(STICKY_TYPE, id, sticky("v1"))
        engine().sync(server)
        t.stickies.update(id, null, "lokal", "yellow", false)
        server.put(STICKY_TYPE, id, sticky("server neuer"), modifiedAt = "2099-01-01T00:00:00.000Z")

        engine().sync(server)

        assertEquals("server neuer", t.stickies.get(id)!!.content)
        assertTrue(t.db.outboxDao().all().isEmpty())
    }

    @Test
    fun capture_then_sync_sends_the_context_id() = runTest {
        t.contexts.ensureDefaultContext()
        val ref = t.actions.capture(CaptureContent(null, "schnell"))
        engine().sync(server)
        assertEquals(Contexts.DEFAULT_ID, server.rows[STICKY_TYPE to ref.id]!!.data.str("contextId"))
    }
}

/** All contract fixtures: known types round-trip through a local edit with future fields preserved; unknown types are skipped. */
class ContractFixtureTest {
    private val root = File(System.getProperty("notes.repoRoot") ?: "../..", "api/fixtures")

    @Test
    fun all_fixtures_round_trip() = runTest {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue(files.size >= 7, "fixtures found in $root")
        for (file in files) {
            val t = TestDb()
            val server = FakeServer(listOf("core.context", "stickies.sticky", "todos.todo"))
            val engine = SyncEngine(t.store, t.db.outboxDao(), t.db.syncStateDao(), t.tx)
            try {
                val doc = NotesJson.parseToJsonElement(file.readText()).jsonObject
                val envelope = doc["envelope"]!!.jsonObject
                val type = envelope["entityType"]!!.jsonPrimitive.content
                val id = envelope["id"]!!.jsonPrimitive.content
                val data = envelope["data"]!!.jsonObject
                server.put(type, id, data, data.str("modifiedAt")!!, envelope["deleted"]!!.jsonPrimitive.content.toBoolean())
                engine.sync(server)

                val handler = t.store.handlerOrNull(type)
                if (handler == null) {
                    assertEquals("rejected_unknown_type", doc["expect"]!!.jsonPrimitive.content, file.name)
                    continue
                }
                // Edit one known field locally (restores tombstones, like an edit would) and push.
                val field = if (type == Contexts.TYPE) "name" else "content"
                t.store.change(type, id, mapOf(field to JsonPrimitive("geändert")))
                engine.sync(server)

                val pushed = server.pushes.last().single { it.id == id }.data!!
                for ((key, value) in data) {
                    if (key in setOf(field, "modifiedAt", "deletedAt", "version")) continue
                    assertEquals(value, pushed[key], "${file.name}: field '$key' must survive")
                }
                assertEquals("geändert", pushed.str(field))
            } finally {
                t.close()
            }
        }
    }
}


