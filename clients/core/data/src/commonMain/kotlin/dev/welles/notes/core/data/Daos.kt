package dev.welles.notes.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Query("SELECT * FROM core_outbox WHERE entityType = :type AND entityId = :id")
    suspend fun get(type: String, id: String): OutboxEntry?

    @Upsert
    suspend fun upsert(entry: OutboxEntry)

    @Query("SELECT * FROM core_outbox ORDER BY sequence")
    suspend fun all(): List<OutboxEntry>

    @Query("SELECT entityId FROM core_outbox WHERE entityType = :type")
    suspend fun idsOfType(type: String): List<String>

    @Query("DELETE FROM core_outbox WHERE entityType = :type AND entityId = :id")
    suspend fun delete(type: String, id: String)

    @Query("SELECT COUNT(*) FROM core_outbox")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM core_outbox WHERE waitingForServer = 1")
    fun observeWaitingCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM core_outbox")
    suspend fun maxSequence(): Long
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM core_sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM core_sync_state WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entry: SyncStateEntry)
}

@Dao
interface LocalSettingsDao {
    @Query("SELECT value FROM core_local_setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM core_local_setting WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(setting: LocalSetting)

    @Query("DELETE FROM core_local_setting WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM core_local_setting WHERE `key` LIKE :prefix || '%'")
    suspend fun withPrefix(prefix: String): List<LocalSetting>
}

@Dao
interface ContextDao {
    @Query("SELECT * FROM core_context WHERE deletedAt IS NULL ORDER BY position, name")
    fun observeAll(): Flow<List<ContextEntity>>

    @Query("SELECT * FROM core_context WHERE deletedAt IS NULL ORDER BY position, name")
    suspend fun all(): List<ContextEntity>

    @Query("SELECT * FROM core_context WHERE id = :id")
    suspend fun get(id: String): ContextEntity?

    @Upsert
    suspend fun upsert(entity: ContextEntity)

    @Query("DELETE FROM core_context WHERE id NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<String>)
}
