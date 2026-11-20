package dev.welles.notes.app

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.welles.notes.core.data.CaptureTarget
import dev.welles.notes.core.data.CaptureTargets
import dev.welles.notes.core.data.ContextHandler
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.EntityActions
import dev.welles.notes.core.data.LocalEntityHandler
import dev.welles.notes.core.data.LocalStore
import dev.welles.notes.feature.stickies.data.StickyCaptureTarget
import dev.welles.notes.feature.stickies.data.StickyHandler
import dev.welles.notes.feature.stickies.data.StickyRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers

class FixedClock(var now: Instant = Instant.parse("2026-11-20T10:00:00Z")) : Clock {
    override fun now() = now
}

/** Real Room database in memory with the production handlers; optional extra handlers/targets (e.g. a test-only type). */
class TestDb(extraHandlers: List<LocalEntityHandler> = emptyList(), extraTargets: (StickyRepository) -> List<CaptureTarget> = { emptyList() }) {
    val clock = FixedClock()
    val db: NotesDatabase = Room.inMemoryDatabaseBuilder<NotesDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    val tx = RoomTransactionRunner(db)
    val stickyHandler = StickyHandler(db.stickyDao())
    val store = LocalStore(listOf(ContextHandler(db.contextDao()), stickyHandler) + extraHandlers, db.outboxDao(), tx, clock)
    val stickies = StickyRepository(db.stickyDao(), store)
    val contexts = ContextRepository(db.contextDao(), db.localSettingsDao(), store)
    val targets = CaptureTargets(listOf(StickyCaptureTarget(stickies)) + extraTargets(stickies))
    val actions = EntityActions(store, targets, contexts)

    fun close() = db.close()
}
