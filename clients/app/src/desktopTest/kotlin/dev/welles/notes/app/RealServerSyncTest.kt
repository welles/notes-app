package dev.welles.notes.app

import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.sync.ClientInfo
import dev.welles.notes.core.sync.KtorNotesApi
import dev.welles.notes.core.sync.ServerConfig
import dev.welles.notes.core.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * End-to-end: the real client sync engine against a real server (no fakes).
 * Runs only with -Pnotes.e2eServer=http://localhost:5080 (CloudflareAccess disabled on that server).
 * CI uses it across versions: released client vs. current server and current client vs. released server.
 */
class RealServerSyncTest {
    private val url = System.getProperty("notes.e2eServer").orEmpty()

    @Test
    fun two_devices_converge_with_conflict_copy() = runTest(timeout = 2.minutes) {
        if (url.isEmpty()) return@runTest
        val api = KtorNotesApi(ServerConfig(url), ClientInfo("desktop", "e2e"))
        val phone = TestDb()
        val laptop = TestDb()
        fun engine(t: TestDb) = SyncEngine(t.store, t.db.outboxDao(), t.db.syncStateDao(), t.tx)
        try {
            phone.contexts.ensureDefaultContext()
            laptop.contexts.ensureDefaultContext()
            // Own context per run, so repeated runs against the same server don't interfere.
            val ctx = phone.contexts.create("e2e-${dev.welles.notes.core.model.Ids.newId()}")
            val id = phone.stickies.create(ctx, "# E2E\n\noriginal")
            engine(phone).sync(api)
            engine(laptop).sync(api)
            assertEquals("# E2E\n\noriginal", laptop.stickies.get(id)!!.content)

            // Both edit "offline", the laptop later.
            phone.stickies.update(id, "# E2E\n\nvom Handy", "yellow", false)
            laptop.clock.now = phone.clock.now + kotlin.time.Duration.parse("1m")
            laptop.stickies.update(id, "# E2E\n\nvom Laptop", "blue", false)
            engine(phone).sync(api)
            engine(laptop).sync(api)
            engine(phone).sync(api)

            for (t in listOf(phone, laptop)) {
                val list = t.stickies.observe(ctx).first()
                assertEquals("# E2E\n\nvom Laptop", list.single { it.id == id }.content)
                // Current servers mark the heading ("# Konflikt: E2E"), older ones set the legacy title "Konflikt".
                assertTrue(list.any { it.text.startsWith("# Konflikt") && it.text.endsWith("vom Handy") }, "conflict copy")
                assertTrue(t.db.outboxDao().all().isEmpty())
            }
            assertTrue(phone.contexts.contexts.first().any { it.id == ctx } && Contexts.DEFAULT_ID.isNotEmpty())
        } finally {
            phone.close()
            laptop.close()
        }
    }
}
