package dev.welles.notes.core.data

import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.EntityRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A module that can create something from text (quick capture, share target, "convert to …"). */
interface CaptureTarget {
    val key: String
    val displayName: String
    val entityType: String
    /** `data` of the new entity, without `contextId` (set by the core). */
    suspend fun createData(content: CaptureContent, contextId: String): JsonObject
}

class CaptureTargets(val all: List<CaptureTarget>) {
    val default: CaptureTarget get() = all.first()
    /** "Convert to …" only appears with more than one target (not in v1). */
    val conversionAvailable: Boolean get() = all.size > 1
    fun get(key: String) = all.first { it.key == key }
}

/** Cross-module core actions – the same mechanics as on the server, as local transactions with one outbox batch. */
class EntityActions(
    private val store: LocalStore,
    private val targets: CaptureTargets,
    private val contexts: ContextRepository,
) {
    /** Quick capture into the active context. */
    suspend fun capture(content: CaptureContent, targetKey: String? = null, contextId: String? = null): EntityRef {
        val target = targetKey?.let { targets.get(it) } ?: targets.default
        val ctx = contextId ?: contexts.activeIdOrDefault()
        val data = target.createData(content, ctx).let { JsonObject(it + ("contextId" to JsonPrimitive(ctx))) }
        return EntityRef(target.entityType, store.create(target.entityType, data))
    }

    /** "Convert to …": export, create in target (same context), optionally delete the source – atomically. */
    suspend fun convert(source: EntityRef, targetKey: String, deleteSource: Boolean): EntityRef {
        val handler = store.handler(source.entityType)
        val target = targets.get(targetKey)
        return store.batch {
            val record = handler.get(source.id)?.takeIf { !it.deleted } ?: error("$source does not exist")
            val content = handler.export(record.data)?.copy(source = source) ?: error("${source.entityType} cannot be exported")
            val ctx = record.contextId ?: contexts.activeIdOrDefault()
            val data = target.createData(content, ctx).let { JsonObject(it + ("contextId" to JsonPrimitive(ctx))) }
            val id = create(target.entityType, data)
            if (deleteSource) delete(source.entityType, source.id)
            EntityRef(target.entityType, id)
        }
    }

    /** "Move to context …" for any context-scoped entity. */
    suspend fun moveToContext(refs: List<EntityRef>, contextId: String) = store.batch {
        for (r in refs) change(r.entityType, r.id, mapOf("contextId" to JsonPrimitive(contextId)))
    }

    suspend fun listInContext(contextId: String): List<EntityRef> =
        store.handlers.filter { it.contextScoped }.flatMap { h -> h.idsInContext(contextId).map { EntityRef(h.entityType, it) } }

    /** "Move everything to …". */
    suspend fun moveAll(from: String, to: String) = moveToContext(listInContext(from), to)
}
