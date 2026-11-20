package dev.welles.notes.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Generic outbox: one row per entity with unsynced local changes (several changes are coalesced).
 * `baseVersion` is the server version the first unsynced change was based on (null = never on the server).
 * Rows with the same `batchId` are sent in the same push (e.g. "convert to …").
 */
@Entity(tableName = "core_outbox", primaryKeys = ["entityType", "entityId"], indices = [Index("batchId")])
data class OutboxEntry(
    val entityType: String,
    val entityId: String,
    /** Full raw JSON of the entity at the time of the change (unknown fields of newer versions included). */
    val payload: String,
    val baseVersion: Long?,
    val modifiedAt: String,
    val deleted: Boolean,
    val batchId: String,
    /** Server rejected the type (older server): keep and retry on later syncs. */
    @ColumnInfo(defaultValue = "0") val waitingForServer: Boolean = false,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val sequence: Long = 0,
)

/** Key/value state of the sync engine (cursor, known entity types, last server meta). Device-local. */
@Entity(tableName = "core_sync_state")
data class SyncStateEntry(@PrimaryKey val key: String, val value: String)

/** Device-local settings that are never synchronized (active context, window states, widget config, …). */
@Entity(tableName = "core_local_setting")
data class LocalSetting(@PrimaryKey val key: String, val value: String)

/** Context (`core.context`). Columns are a projection of [rawData], which is the source of truth for sync. */
@Entity(tableName = "core_context")
data class ContextEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String?,
    val position: Double,
    val createdAt: String,
    val modifiedAt: String,
    val deletedAt: String?,
    val version: Long,
    val rawData: String,
)
