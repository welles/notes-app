package dev.welles.notes.app

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.welles.notes.core.data.ContextDao
import dev.welles.notes.core.data.ContextEntity
import dev.welles.notes.core.data.LocalSetting
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.data.OutboxDao
import dev.welles.notes.core.data.OutboxEntry
import dev.welles.notes.core.data.SyncStateDao
import dev.welles.notes.core.data.SyncStateEntry
import dev.welles.notes.core.data.TransactionRunner
import dev.welles.notes.feature.stickies.data.StickyDao
import dev.welles.notes.feature.stickies.data.StickyEntity

/**
 * The one local database. Core tables (`core_…`) plus every module's tables (`stickies_…`).
 * A new module adds its entities here and a DAO accessor (see docs/adding-a-module.md).
 */
@Database(
    entities = [OutboxEntry::class, SyncStateEntry::class, LocalSetting::class, ContextEntity::class, StickyEntity::class],
    version = NotesDatabase.VERSION,
    exportSchema = true,
)
@ConstructedBy(NotesDatabaseConstructor::class)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun localSettingsDao(): LocalSettingsDao
    abstract fun contextDao(): ContextDao
    abstract fun stickyDao(): StickyDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "notes.db"
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object NotesDatabaseConstructor : RoomDatabaseConstructor<NotesDatabase> {
    override fun initialize(): NotesDatabase
}

class RoomTransactionRunner(private val db: NotesDatabase) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R =
        db.useWriterConnection { it.immediateTransaction { block() } }
}
