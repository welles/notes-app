package dev.welles.notes.app

import dev.welles.notes.core.data.LocalEntityHandler
import dev.welles.notes.core.data.LocalRecord
import dev.welles.notes.core.data.str
import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.model.NotesJson
import dev.welles.notes.core.model.SyncEnvelope
import dev.welles.notes.feature.stickies.data.STICKY_TYPE
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LocalStoreTest {
    private val t = TestDb()

    @AfterTest
    fun close() = t.close()

    private fun remote(id: String, version: Long, vararg extra: Pair<String, Any?>) = SyncEnvelope(
        STICKY_TYPE, id, version, false,
        NotesJson.parseToJsonElement(
            """{"id":"$id","createdAt":"2026-11-20T09:00:00.000Z","modifiedAt":"2026-11-20T09:00:00.000Z","deletedAt":null,"version":$version,
               "contextId":"${Contexts.DEFAULT_ID}","title":"T","content":"server","color":"teal","pinned":false,"position":1.5,
               "expiresAt":"2027-01-01T00:00:00.000Z","checklist":[{"text":"a","done":true}]}""",
        ).jsonObject,
    )

    @Test
    fun create_records_outbox_entry_without_base_version() = runTest {
        val id = t.stickies.create(Contexts.DEFAULT_ID, "Text")
        val entry = assertNotNull(t.db.outboxDao().get(STICKY_TYPE, id))
        assertNull(entry.baseVersion)
        assertFalse(entry.deleted)
        val payload = NotesJson.parseToJsonElement(entry.payload).jsonObject
        assertEquals("Text", payload.str("content"))
        assertEquals(Contexts.DEFAULT_ID, payload.str("contextId"))
    }

    @Test
    fun edits_are_coalesced_and_keep_the_first_base_version() = runTest {
        val id = "019a8f00-0000-7000-8000-00000000a001"
        t.store.applyRemote(remote(id, 5))
        t.stickies.setPinned(id, true)
        t.stickies.setColor(id, "blue")

        val entries = t.db.outboxDao().all()
        assertEquals(1, entries.size)
        assertEquals(5, entries.single().baseVersion)
        val payload = NotesJson.parseToJsonElement(entries.single().payload).jsonObject
        assertEquals("blue", payload.str("color"))
        assertEquals(true, payload["pinned"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun unknown_fields_and_enum_values_survive_local_edits() = runTest {
        val id = "019a8f00-0000-7000-8000-00000000a002"
        t.store.applyRemote(remote(id, 7))
        assertEquals("teal", t.stickies.get(id)!!.color) // unknown colour kept in the column, display falls back

        t.stickies.update(id, "lokal geändert", "teal", false)

        val payload = NotesJson.parseToJsonElement(t.db.outboxDao().get(STICKY_TYPE, id)!!.payload).jsonObject
        assertEquals("lokal geändert", payload.str("content"))
        assertEquals("teal", payload.str("color"))
        assertEquals("2027-01-01T00:00:00.000Z", payload.str("expiresAt"))
        assertEquals(1, payload["checklist"]!!.jsonArray.size)
    }

    @Test
    fun modified_at_is_strictly_increasing_within_the_same_millisecond() = runTest {
        val id = t.stickies.create(Contexts.DEFAULT_ID, "a")
        val first = t.db.outboxDao().get(STICKY_TYPE, id)!!.modifiedAt
        t.stickies.setColor(id, "pink")
        val second = t.db.outboxDao().get(STICKY_TYPE, id)!!.modifiedAt
        assertTrue(second > first, "$second > $first")
    }

    @Test
    fun remote_changes_do_not_overwrite_pending_local_changes() = runTest {
        val id = "019a8f00-0000-7000-8000-00000000a003"
        t.store.applyRemote(remote(id, 3))
        t.stickies.update(id, "lokal", "teal", false)

        t.store.applyRemote(remote(id, 9))

        assertEquals("lokal", t.stickies.get(id)!!.content)
    }

    @Test
    fun push_completion_applies_server_state_or_rebases_when_edited_in_flight() = runTest {
        val id = t.stickies.create(Contexts.DEFAULT_ID, "a")
        val sent = t.db.outboxDao().get(STICKY_TYPE, id)!!
        val server = SyncEnvelope(STICKY_TYPE, id, 42, false, NotesJson.parseToJsonElement(sent.payload).jsonObject)

        // Edited while the push was in flight → stays in the outbox, rebased onto version 42.
        t.stickies.setColor(id, "green")
        t.store.completePush(STICKY_TYPE, id, sent.modifiedAt, server)
        assertEquals(42, t.db.outboxDao().get(STICKY_TYPE, id)!!.baseVersion)

        val second = t.db.outboxDao().get(STICKY_TYPE, id)!!
        t.store.completePush(STICKY_TYPE, id, second.modifiedAt, server.copy(version = 43))
        assertNull(t.db.outboxDao().get(STICKY_TYPE, id))
    }

    @Test
    fun delete_and_restore() = runTest {
        val id = t.stickies.create(Contexts.DEFAULT_ID, "a")
        t.stickies.delete(id)
        assertTrue(t.stickies.get(id)!!.deleted)
        assertTrue(t.db.outboxDao().get(STICKY_TYPE, id)!!.deleted)
        t.stickies.restore(id)
        assertFalse(t.stickies.get(id)!!.deleted)
        assertFalse(t.db.outboxDao().get(STICKY_TYPE, id)!!.deleted)
    }

    @Test
    fun capture_goes_to_the_active_context() = runTest {
        t.contexts.ensureDefaultContext()
        val work = t.contexts.create("Arbeit")
        t.contexts.setActive(work)

        val ref = t.actions.capture(CaptureContent("Idee", "Zeile 1\nZeile 2"))

        val sticky = t.stickies.get(ref.id)!!
        assertEquals(work, sticky.contextId)
        assertNull(sticky.title)
        assertEquals("# Idee\n\nZeile 1\nZeile 2", sticky.content)
    }

    @Test
    fun full_resync_keeps_only_pending_entities() = runTest {
        val synced = "019a8f00-0000-7000-8000-00000000a004"
        t.store.applyRemote(remote(synced, 3))
        val pending = t.stickies.create(Contexts.DEFAULT_ID, "offline")

        t.store.clearForFullResync()

        assertNull(t.stickies.get(synced))
        assertNotNull(t.stickies.get(pending))
    }

    @Test
    fun reorder_uses_midpoint_and_renormalises() = runTest {
        val c = t.stickies.create(Contexts.DEFAULT_ID, "c")
        val b = t.stickies.create(Contexts.DEFAULT_ID, "b")
        val a = t.stickies.create(Contexts.DEFAULT_ID, "a")
        suspend fun order() = t.stickies.observe(Contexts.DEFAULT_ID).first().map { it.id }
        assertEquals(listOf(a, b, c), order())

        t.stickies.reorder(c, after = a, before = b)
        assertEquals(listOf(a, c, b), order())

        repeat(60) {
            val o = order()
            t.stickies.reorder(o[2], after = o[0], before = o[1])
        }
        val positions = t.stickies.observe(Contexts.DEFAULT_ID).first().map { it.position }
        assertEquals(positions.sorted(), positions)
        assertEquals(3, positions.distinct().size)
    }
}

class ContextAndActionsTest {
    /** Test-only second module: proves capture targets and "convert to …" are generic on the client, too. */
    private val dummy = object : LocalEntityHandler {
        val rows = mutableMapOf<String, LocalRecord>()
        override val entityType = "test.dummy"
        override val contextScoped = true
        override suspend fun get(id: String) = rows[id]
        override suspend fun put(record: LocalRecord) { rows[record.id] = record }
        override suspend fun idsInContext(contextId: String) = rows.values.filter { !it.deleted && it.contextId == contextId }.map { it.id }
        override suspend fun deleteAllExcept(keep: List<String>) { rows.keys.retainAll(keep.toSet()) }
    }
    private val t = TestDb(listOf(dummy)) {
        listOf(object : dev.welles.notes.core.data.CaptureTarget {
            override val key = "dummy"
            override val displayName = "Dummy"
            override val entityType = "test.dummy"
            override suspend fun createData(content: CaptureContent, contextId: String) =
                JsonObject(mapOf("text" to JsonPrimitive((content.title?.let { "$it: " } ?: "") + content.text)))
        })
    }

    @AfterTest
    fun close() = t.close()

    @Test
    fun convert_creates_target_in_same_context_and_deletes_source_in_one_batch() = runTest {
        t.contexts.ensureDefaultContext()
        val work = t.contexts.create("Arbeit")
        val source = t.stickies.create(work, "# Titel\n\nText")
        assertTrue(t.targets.conversionAvailable)

        val target = t.actions.convert(dev.welles.notes.core.model.EntityRef(STICKY_TYPE, source), "dummy", deleteSource = true)

        val created = dummy.rows.getValue(target.id)
        assertEquals("Titel: Text", created.data.str("text"))
        assertEquals(work, created.contextId)
        assertTrue(t.stickies.get(source)!!.deleted)
        val outbox = t.db.outboxDao().all()
        val batchOfTarget = outbox.single { it.entityId == target.id }.batchId
        assertEquals(batchOfTarget, outbox.single { it.entityId == source }.batchId)
    }

    @Test
    fun contexts_can_only_be_deleted_when_empty_and_default_never() = runTest {
        t.contexts.ensureDefaultContext()
        val work = t.contexts.create("Arbeit")
        t.stickies.create(work, "x")
        t.actions.capture(CaptureContent(null, "y"), targetKey = "dummy", contextId = work)

        assertFalse(t.contexts.delete(Contexts.DEFAULT_ID))
        assertFalse(t.contexts.delete(work))
        assertEquals(2, t.contexts.countIn(work))

        t.actions.moveAll(work, Contexts.DEFAULT_ID)
        assertEquals(2, t.actions.listInContext(Contexts.DEFAULT_ID).size)
        assertTrue(t.contexts.delete(work))
        assertTrue(t.contexts.contexts.first().none { it.id == work })
    }

    @Test
    fun deleted_active_context_falls_back_to_default_with_notice() = runTest {
        t.contexts.ensureDefaultContext()
        val work = t.contexts.create("Arbeit")
        t.contexts.setActive(work)
        // Deleted on another device, arrives via sync.
        val record = t.store.handler(Contexts.TYPE).get(work)!!
        t.store.handler(Contexts.TYPE).put(record.copy(deleted = true))

        t.contexts.checkActiveStillExists()

        assertEquals(Contexts.DEFAULT_ID, t.contexts.activeIdOrDefault())
        assertEquals("Arbeit", t.contexts.fallbackNotice.first())
    }
}
