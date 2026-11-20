package dev.welles.notes.core.data

import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.Ids
import dev.welles.notes.core.model.NotesJson
import dev.welles.notes.core.model.SyncEnvelope
import dev.welles.notes.core.model.SyncTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entity in the local database, described generically by its full raw JSON. */
data class LocalRecord(val id: String, val version: Long, val deleted: Boolean, val data: JsonObject) {
    val contextId: String? get() = data.str("contextId")
}

/**
 * Per entity type: how a module stores its entities in Room. The sync engine and the core actions only talk to this.
 * [put] must read known fields tolerantly from [LocalRecord.data] and keep the full JSON as raw data.
 */
interface LocalEntityHandler {
    val entityType: String
    val contextScoped: Boolean
    suspend fun get(id: String): LocalRecord?
    suspend fun put(record: LocalRecord)
    /** Ids of non-deleted entities in a context (empty for types without context). */
    suspend fun idsInContext(contextId: String): List<String>
    /** Full resync: drop everything except entities with pending local changes. */
    suspend fun deleteAllExcept(keep: List<String>)
    /** Neutral content for "convert to …", null if not exportable. */
    fun export(data: JsonObject): CaptureContent? = null
}

/** Runs a block in one local database transaction (implemented with Room in the app composition). */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}

/**
 * The only way to change synchronized data locally. Every change is a JSON patch merged into the entity's raw JSON
 * (so fields of newer versions survive) and recorded in the outbox in the same transaction.
 */
class LocalStore(
    handlers: List<LocalEntityHandler>,
    private val outbox: OutboxDao,
    private val tx: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    private val byType = handlers.associateBy { it.entityType }

    val entityTypes: Set<String> get() = byType.keys

    fun handler(type: String): LocalEntityHandler = byType[type] ?: error("Unknown entity type $type")
    fun handlerOrNull(type: String): LocalEntityHandler? = byType[type]
    val handlers: Collection<LocalEntityHandler> get() = byType.values

    /** Several changes in one transaction and one push batch (e.g. "convert to …"). */
    suspend fun <R> batch(block: suspend Batch.() -> R): R = tx.run { Batch(Ids.newId(clock)).block() }

    suspend fun create(type: String, data: JsonObject): String = batch { create(type, data) }
    suspend fun change(type: String, id: String, patch: Map<String, JsonElement>) = batch { change(type, id, patch) }
    suspend fun delete(type: String, id: String) = batch { delete(type, id) }
    suspend fun restore(type: String, id: String) = batch { change(type, id, emptyMap()) }

    inner class Batch(private val batchId: String) {
        suspend fun create(type: String, data: JsonObject, id: String = Ids.newId(clock)): String {
            val now = SyncTime.now(clock)
            val full = data.merge(
                mapOf(
                    "id" to JsonPrimitive(id),
                    "createdAt" to JsonPrimitive(now),
                    "modifiedAt" to JsonPrimitive(now),
                    "deletedAt" to JsonNull,
                    "version" to JsonPrimitive(0),
                ),
            )
            handler(type).put(LocalRecord(id, 0, false, full))
            record(type, id, full, baseVersion = null, modifiedAt = now, deleted = false)
            return id
        }

        suspend fun change(type: String, id: String, patch: Map<String, JsonElement>) {
            val h = handler(type)
            val current = h.get(id) ?: error("$type/$id does not exist")
            val modifiedAt = nextModifiedAt(current.data)
            val data = current.data.merge(patch + mapOf("modifiedAt" to JsonPrimitive(modifiedAt), "deletedAt" to JsonNull))
            h.put(current.copy(deleted = false, data = data))
            record(type, id, data, current.version.takeIf { it > 0 }, modifiedAt, deleted = false)
        }

        suspend fun delete(type: String, id: String) {
            val h = handler(type)
            val current = h.get(id) ?: return
            val modifiedAt = nextModifiedAt(current.data)
            val data = current.data.merge(mapOf("modifiedAt" to JsonPrimitive(modifiedAt), "deletedAt" to JsonPrimitive(modifiedAt)))
            h.put(current.copy(deleted = true, data = data))
            record(type, id, data, current.version.takeIf { it > 0 }, modifiedAt, deleted = true)
        }

        private suspend fun record(type: String, id: String, data: JsonObject, baseVersion: Long?, modifiedAt: String, deleted: Boolean) {
            val existing = outbox.get(type, id)
            outbox.upsert(
                OutboxEntry(
                    entityType = type,
                    entityId = id,
                    payload = NotesJson.encodeToString(JsonObject.serializer(), data),
                    // Coalescing keeps the base of the first unsynced change.
                    baseVersion = if (existing != null) existing.baseVersion else baseVersion,
                    modifiedAt = modifiedAt,
                    deleted = deleted,
                    batchId = batchId,
                    waitingForServer = existing?.waitingForServer ?: false,
                    sequence = existing?.sequence ?: (outbox.maxSequence() + 1),
                ),
            )
        }
    }

    /** modifiedAt strictly increasing per entity, even for edits within the same millisecond. */
    private fun nextModifiedAt(data: JsonObject): String {
        val now = clock.now()
        val previous = data.str("modifiedAt")?.let { runCatching { SyncTime.parse(it) }.getOrNull() }
        return SyncTime.format(if (previous != null && now <= previous) previous + 1.milliseconds else now)
    }

    // --- used by the sync engine -----------------------------------------------------------------------------

    /** Applies server state unless the entity has pending local changes (those win locally until pushed). */
    suspend fun applyRemote(envelope: SyncEnvelope): Boolean {
        val h = byType[envelope.entityType] ?: return false
        if (outbox.get(envelope.entityType, envelope.id) != null) return true
        h.put(LocalRecord(envelope.id, envelope.version, envelope.deleted, envelope.data))
        return true
    }

    /**
     * After a push: the outbox row is removed and the server state applied – unless the entity was changed locally
     * while the push was in flight; then the row stays and is rebased onto the new server version.
     */
    suspend fun completePush(type: String, id: String, sentModifiedAt: String, server: SyncEnvelope?) = tx.run {
        val pending = outbox.get(type, id) ?: return@run
        if (pending.modifiedAt == sentModifiedAt) {
            outbox.delete(type, id)
            if (server != null) byType[type]?.put(LocalRecord(server.id, server.version, server.deleted, server.data))
        } else if (server != null) {
            outbox.upsert(pending.copy(baseVersion = server.version))
            byType[type]?.get(id)?.let { byType[type]?.put(it.copy(version = server.version)) }
        }
    }

    suspend fun markRejected(type: String, id: String, sentModifiedAt: String, waitingForServer: Boolean, error: String?) {
        val pending = outbox.get(type, id) ?: return
        outbox.upsert(pending.copy(waitingForServer = waitingForServer, lastError = error))
    }

    /** Full resync: forget all synced data except entities with pending changes. */
    suspend fun clearForFullResync() = tx.run {
        for (h in byType.values) h.deleteAllExcept(outbox.idsOfType(h.entityType))
    }
}
