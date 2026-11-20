package dev.welles.notes.app

import dev.welles.notes.core.model.ConflictWinner
import dev.welles.notes.core.model.MetaResponse
import dev.welles.notes.core.model.ProtocolRange
import dev.welles.notes.core.model.PullResponse
import dev.welles.notes.core.model.PushEntryResult
import dev.welles.notes.core.model.PushEnvelope
import dev.welles.notes.core.model.PushResponse
import dev.welles.notes.core.model.PushStatus
import dev.welles.notes.core.model.RejectReasons
import dev.welles.notes.core.model.SyncEnvelope
import dev.welles.notes.core.sync.ApiException
import dev.welles.notes.core.sync.NotesApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Minimal in-memory server with the protocol's semantics (versions, merge, LWW, paging, unknown types). */
class FakeServer(var entityTypes: List<String> = listOf("core.context", "stickies.sticky")) : NotesApi {
    data class Row(val type: String, val id: String, val version: Long, val deleted: Boolean, val data: JsonObject, val modifiedAt: String)

    val rows = linkedMapOf<Pair<String, String>, Row>()
    private var version = 1L
    var protocol = ProtocolRange(1, 1)
    var fullResyncOnce = false
    val pushes = mutableListOf<List<PushEnvelope>>()

    override suspend fun meta(): MetaResponse {
        if (1 < protocol.min || 1 > protocol.max) throw ApiException.ProtocolUnsupported()
        return MetaResponse("test", protocol, entityTypes, listOf("sync.v1"), emptyMap())
    }

    /** Simulates a change made elsewhere (other device / web). */
    fun put(type: String, id: String, data: JsonObject, modifiedAt: String = "2026-11-20T09:00:00.000Z", deleted: Boolean = false): Row {
        val v = ++version
        val full = JsonObject(data + mapOf("id" to JsonPrimitive(id), "version" to JsonPrimitive(v), "modifiedAt" to JsonPrimitive(modifiedAt)))
        return Row(type, id, v, deleted, full, modifiedAt).also { rows.remove(type to id); rows[type to id] = it }
    }

    override suspend fun pull(since: Long, limit: Int): PullResponse {
        if (fullResyncOnce && since > 0) {
            fullResyncOnce = false
            return PullResponse(emptyList(), since, false, true)
        }
        val changes = rows.values.filter { it.version > since }.sortedBy { it.version }
        val page = changes.take(limit)
        return PullResponse(page.map { it.envelope() }, page.lastOrNull()?.version ?: since, changes.size > limit, false)
    }

    override suspend fun push(changes: List<PushEnvelope>): PushResponse {
        pushes += changes
        return PushResponse(changes.map { e ->
            if (e.entityType !in entityTypes) return@map PushEntryResult(e.entityType, e.id, PushStatus.Rejected, RejectReasons.UNKNOWN_TYPE)
            val existing = rows[e.entityType to e.id]
            val merged = JsonObject((existing?.data ?: JsonObject(emptyMap())) + (e.data ?: JsonObject(emptyMap())))
            if (existing == null || existing.version == e.baseVersion) {
                val row = put(e.entityType, e.id, if (e.deleted) JsonObject(merged + ("deletedAt" to JsonPrimitive(e.modifiedAt))) else JsonObject(merged + ("deletedAt" to JsonNull)), e.modifiedAt, e.deleted)
                PushEntryResult(e.entityType, e.id, PushStatus.Accepted, server = row.envelope())
            } else if (e.modifiedAt > existing.modifiedAt) {
                val row = put(e.entityType, e.id, merged, e.modifiedAt, e.deleted)
                PushEntryResult(e.entityType, e.id, PushStatus.Conflict, winner = ConflictWinner.Client, server = row.envelope())
            } else {
                PushEntryResult(e.entityType, e.id, PushStatus.Conflict, winner = ConflictWinner.Server, server = existing.envelope())
            }
        })
    }

    private fun Row.envelope() = SyncEnvelope(type, id, version, deleted, data)
}
