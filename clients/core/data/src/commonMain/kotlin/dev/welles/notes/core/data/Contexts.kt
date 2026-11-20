package dev.welles.notes.core.data

import dev.welles.notes.core.model.Contexts
import dev.welles.notes.core.model.NotesJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class NotesContext(val id: String, val name: String, val color: String?, val position: Double) {
    val isDefault: Boolean get() = id == Contexts.DEFAULT_ID
}

class ContextHandler(private val dao: ContextDao) : LocalEntityHandler {
    override val entityType = Contexts.TYPE
    override val contextScoped = false

    override suspend fun get(id: String) = dao.get(id)?.let {
        LocalRecord(it.id, it.version, it.deletedAt != null, NotesJson.decodeFromString(JsonObject.serializer(), it.rawData))
    }

    override suspend fun put(record: LocalRecord) {
        val d = record.data
        dao.upsert(
            ContextEntity(
                id = record.id,
                name = d.str("name") ?: "",
                color = d.str("color"),
                position = d.double("position"),
                createdAt = d.str("createdAt") ?: "",
                modifiedAt = d.str("modifiedAt") ?: "",
                deletedAt = if (record.deleted) d.str("deletedAt") ?: d.str("modifiedAt") ?: "" else null,
                version = record.version,
                rawData = NotesJson.encodeToString(JsonObject.serializer(), d),
            ),
        )
    }

    override suspend fun idsInContext(contextId: String) = emptyList<String>()
    override suspend fun deleteAllExcept(keep: List<String>) = dao.deleteAllExcept(keep + Contexts.DEFAULT_ID)
}

/** Contexts and the device's active context (device-local, never synchronized). */
class ContextRepository(
    private val dao: ContextDao,
    private val settings: LocalSettingsDao,
    private val store: LocalStore,
) {
    val contexts: Flow<List<NotesContext>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /** Null until the user picked one on first start. */
    val activeContextId: Flow<String?> = settings.observe(ACTIVE_KEY)

    /**
     * The active context, resolved against the list. If it was deleted elsewhere, falls back to the default context
     * (the UI shows a notice via [fallbackNotice]).
     */
    val active: Flow<NotesContext?> = combine(contexts, activeContextId) { list, id ->
        if (id == null) null else list.firstOrNull { it.id == id } ?: list.firstOrNull { it.isDefault }
    }

    val fallbackNotice: Flow<String?> = settings.observe(FALLBACK_NOTICE_KEY)

    /** The default context exists everywhere with its fixed id; created locally (not pushed) so offline first starts work. */
    suspend fun ensureDefaultContext() {
        if (dao.get(Contexts.DEFAULT_ID) != null) return
        store.handler(Contexts.TYPE).put(
            LocalRecord(
                Contexts.DEFAULT_ID, 0, false,
                jsonOf(
                    "id" to Contexts.DEFAULT_ID, "createdAt" to "2026-01-01T00:00:00.000Z", "modifiedAt" to "2026-01-01T00:00:00.000Z",
                    "deletedAt" to null, "version" to 0, "name" to Contexts.DEFAULT_NAME, "color" to null, "position" to 0.0,
                ),
            ),
        )
    }

    suspend fun activeIdOrDefault(): String {
        val id = settings.get(ACTIVE_KEY) ?: return Contexts.DEFAULT_ID
        return if (dao.get(id)?.deletedAt == null && dao.get(id) != null) id else Contexts.DEFAULT_ID
    }

    suspend fun setActive(id: String) {
        settings.put(LocalSetting(ACTIVE_KEY, id))
        settings.remove(FALLBACK_NOTICE_KEY)
    }

    /** Called after sync: if the active context disappeared, switch to the default and leave a notice. */
    suspend fun checkActiveStillExists() {
        val id = settings.get(ACTIVE_KEY) ?: return
        val ctx = dao.get(id)
        if (ctx == null || ctx.deletedAt != null) {
            settings.put(LocalSetting(ACTIVE_KEY, Contexts.DEFAULT_ID))
            settings.put(LocalSetting(FALLBACK_NOTICE_KEY, ctx?.name ?: "Kontext"))
        }
    }

    suspend fun dismissFallbackNotice() = settings.remove(FALLBACK_NOTICE_KEY)

    suspend fun create(name: String, color: String? = null): String {
        val position = (dao.all().maxOfOrNull { it.position } ?: 0.0) + 1
        return store.create(Contexts.TYPE, jsonOf("name" to name.trim(), "color" to color, "position" to position))
    }

    suspend fun rename(id: String, name: String) = store.change(Contexts.TYPE, id, mapOf("name" to JsonPrimitive(name.trim())))
    suspend fun setColor(id: String, color: String?) = store.change(Contexts.TYPE, id, mapOf("color" to color.toJson()))
    suspend fun setPosition(id: String, position: Double) = store.change(Contexts.TYPE, id, mapOf("position" to JsonPrimitive(position)))

    /** All non-deleted entities of all modules in a context. */
    suspend fun countIn(id: String): Int = store.handlers.filter { it.contextScoped }.sumOf { it.idsInContext(id).size }

    suspend fun canDelete(id: String): Boolean = id != Contexts.DEFAULT_ID && countIn(id) == 0

    /** Deletes an empty, non-default context. Returns false otherwise. */
    suspend fun delete(id: String): Boolean {
        if (!canDelete(id)) return false
        store.delete(Contexts.TYPE, id)
        if (settings.get(ACTIVE_KEY) == id) setActive(Contexts.DEFAULT_ID)
        return true
    }

    private fun ContextEntity.toModel() = NotesContext(id, name, color, position)

    companion object {
        const val ACTIVE_KEY = "activeContextId"
        const val FALLBACK_NOTICE_KEY = "activeContextFallback"
    }
}
