package dev.welles.notes.core.sync

import dev.welles.notes.core.data.LocalStore
import dev.welles.notes.core.data.OutboxDao
import dev.welles.notes.core.data.OutboxEntry
import dev.welles.notes.core.data.SyncStateDao
import dev.welles.notes.core.data.SyncStateEntry
import dev.welles.notes.core.data.TransactionRunner
import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.model.MetaResponse
import dev.welles.notes.core.model.NotesJson
import dev.welles.notes.core.model.PROTOCOL_VERSION
import dev.welles.notes.core.model.PushEnvelope
import dev.welles.notes.core.model.PushStatus
import dev.welles.notes.core.model.RejectReasons
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/** Called after every successful sync (e.g. to refresh Android widgets). */
fun interface SyncObserver {
    suspend fun onSynced(result: SyncResult)
}

data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    val changedTypes: Set<String>,
    val fullResync: Boolean,
    val waitingForServer: Int,
    val meta: MetaResponse,
)

/**
 * Generic sync engine (spec 5.1): push the outbox, then pull until `hasMore = false`.
 * Knows only envelopes; storing is delegated to the modules' handlers via [LocalStore].
 */
class SyncEngine(
    private val store: LocalStore,
    private val outbox: OutboxDao,
    private val state: SyncStateDao,
    private val tx: TransactionRunner,
    private val observers: List<SyncObserver> = emptyList(),
    private val pageSize: Int = 500,
    private val maxPushSize: Int = 200,
) {
    suspend fun sync(api: NotesApi): SyncResult {
        val meta = api.meta()
        if (PROTOCOL_VERSION < meta.protocol.min || PROTOCOL_VERSION > meta.protocol.max) throw ApiException.ProtocolUnsupported()
        saveMeta(meta)

        // An update that brought a new module: reload everything so entities skipped earlier arrive.
        val known = state.get(KEY_KNOWN_TYPES)?.let { NotesJson.decodeFromString(ListSerializer(String.serializer()), it).toSet() }
        var fullResync = known != null && !known.containsAll(store.entityTypes)
        if (fullResync) state.put(SyncStateEntry(KEY_CURSOR, "0"))

        val pushed = push(api, meta.entityTypes.toSet())
        val pull = pull(api)
        fullResync = fullResync || pull.second

        state.put(SyncStateEntry(KEY_KNOWN_TYPES, NotesJson.encodeToString(ListSerializer(String.serializer()), store.entityTypes.sorted())))
        val waiting = outbox.all().count { it.waitingForServer }
        val result = SyncResult(pushed, pull.first.size, pull.first, fullResync, waiting, meta)
        observers.forEach { runCatching { it.onSynced(result) } }
        return result
    }

    private suspend fun push(api: NotesApi, serverTypes: Set<String>): Int {
        val all = outbox.all()
        // Types the server does not know (older server): keep, don't send.
        for (e in all.filter { it.entityType !in serverTypes }) store.markRejected(e.entityType, e.entityId, e.modifiedAt, true, RejectReasons.UNKNOWN_TYPE)
        val sendable = all.filter { it.entityType in serverTypes }
        var count = 0
        for (chunk in chunks(sendable)) {
            val response = api.push(chunk.map { it.toEnvelope() })
            val sent = chunk.associateBy { it.entityType to it.entityId }
            for (r in response.results) {
                val entry = sent[r.entityType to r.id] ?: continue
                when (r.status) {
                    PushStatus.Rejected -> store.markRejected(r.entityType, r.id, entry.modifiedAt, r.reason == RejectReasons.UNKNOWN_TYPE, r.reason)
                    else -> {
                        store.completePush(r.entityType, r.id, entry.modifiedAt, r.server)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Contexts first (a context created offline must reach the server before its content), then by sequence;
     * entries of one batch always travel in the same push.
     */
    private fun chunks(entries: List<OutboxEntry>): List<List<OutboxEntry>> {
        val batches = entries.groupBy { it.batchId }.values
            .sortedWith(compareBy({ b -> if (b.any { it.entityType == Contexts.TYPE }) 0 else 1 }, { b -> b.minOf { it.sequence } }))
        val result = mutableListOf<MutableList<OutboxEntry>>()
        for (batch in batches) {
            val current = result.lastOrNull()
            if (current == null || current.size + batch.size > maxPushSize) result.add(batch.toMutableList()) else current.addAll(batch)
        }
        return result
    }

    /** Returns changed types and whether a full resync happened. */
    private suspend fun pull(api: NotesApi): Pair<Set<String>, Boolean> {
        val changed = mutableSetOf<String>()
        var fullResync = false
        var cursor = state.get(KEY_CURSOR)?.toLongOrNull() ?: 0
        while (true) {
            val page = api.pull(cursor, pageSize)
            if (page.fullResyncRequired) {
                if (fullResync) error("Server keeps requiring a full resync")
                fullResync = true
                store.clearForFullResync()
                cursor = 0
                state.put(SyncStateEntry(KEY_CURSOR, "0"))
                continue
            }
            tx.run {
                for (envelope in page.changes) {
                    // Unknown types (newer server module) are skipped; the cursor moves on anyway.
                    if (store.applyRemote(envelope)) changed += envelope.entityType
                }
                // Cursor only after the page is applied – in the same transaction.
                state.put(SyncStateEntry(KEY_CURSOR, page.cursor.toString()))
            }
            cursor = page.cursor
            if (!page.hasMore) return changed to fullResync
        }
    }

    private suspend fun saveMeta(meta: MetaResponse) =
        state.put(SyncStateEntry(KEY_META, NotesJson.encodeToString(MetaResponse.serializer(), meta)))

    private fun OutboxEntry.toEnvelope() = PushEnvelope(
        entityType = entityType,
        id = entityId,
        baseVersion = baseVersion,
        modifiedAt = modifiedAt,
        deleted = deleted,
        data = NotesJson.decodeFromString(JsonObject.serializer(), payload),
    )

    companion object {
        const val KEY_CURSOR = "cursor"
        const val KEY_KNOWN_TYPES = "knownEntityTypes"
        const val KEY_META = "serverMeta"
        const val KEY_LAST_SYNC = "lastSyncAt"
    }
}
