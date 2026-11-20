package dev.welles.notes.feature.stickies.data

import dev.welles.notes.core.data.CaptureTarget
import dev.welles.notes.core.data.LocalStore
import dev.welles.notes.core.data.jsonOf
import dev.welles.notes.core.data.toJson
import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.NoteColorNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Post-it operations. Every write goes through [LocalStore] (raw JSON merge + outbox). */
class StickyRepository(private val dao: StickyDao, private val store: LocalStore) {

    fun observe(contextId: String): Flow<List<Sticky>> = dao.observeInContext(contextId).map { l -> l.map { it.toModel() } }
    fun observePinned(contextId: String): Flow<List<Sticky>> = dao.observePinned(contextId).map { l -> l.map { it.toModel() } }
    fun observe(id: String, @Suppress("UNUSED_PARAMETER") single: Unit = Unit): Flow<Sticky?> = dao.observe(id).map { it?.toModel() }
    suspend fun get(id: String): Sticky? = dao.get(id)?.toModel()

    suspend fun create(contextId: String, title: String?, content: String, color: String = NoteColorNames.DEFAULT, pinned: Boolean = false): String =
        store.create(STICKY_TYPE, newData(contextId, title, content, color, pinned))

    suspend fun newData(contextId: String, title: String?, content: String, color: String = NoteColorNames.DEFAULT, pinned: Boolean = false): JsonObject =
        jsonOf(
            "contextId" to contextId,
            "title" to title?.trim()?.takeIf { it.isNotEmpty() },
            "content" to content,
            "color" to color,
            "pinned" to pinned,
            "position" to topPosition(contextId),
        )

    /** Only changed fields are written; title null clears it. */
    suspend fun update(id: String, title: String?, content: String, color: String, pinned: Boolean) {
        val current = dao.get(id) ?: return
        val patch = buildMap<String, JsonElement> {
            val t = title?.trim()?.takeIf { it.isNotEmpty() }
            if (t != current.title) put("title", t.toJson())
            if (content != current.content) put("content", JsonPrimitive(content))
            if (color != current.color) put("color", JsonPrimitive(color))
            if (pinned != current.pinned) put("pinned", JsonPrimitive(pinned))
        }
        if (patch.isNotEmpty() || current.deletedAt != null) store.change(STICKY_TYPE, id, patch)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = store.change(STICKY_TYPE, id, mapOf("pinned" to JsonPrimitive(pinned)))
    suspend fun setColor(id: String, color: String) = store.change(STICKY_TYPE, id, mapOf("color" to JsonPrimitive(color)))
    suspend fun delete(id: String) = store.delete(STICKY_TYPE, id)
    suspend fun restore(id: String) = store.restore(STICKY_TYPE, id)

    /**
     * Moves a post-it between two neighbours (midpoint). Renormalises all positions of the context
     * in one batch when the gap becomes too small.
     */
    suspend fun reorder(id: String, after: String?, before: String?) {
        val sticky = dao.get(id) ?: return
        val list = dao.listInContext(sticky.contextId).filter { it.id != id }
        val a = after?.let { x -> list.firstOrNull { it.id == x } }
        val b = before?.let { x -> list.firstOrNull { it.id == x } }
        if (a != null && b != null && kotlin.math.abs(b.position - a.position) < MIN_GAP) {
            val ordered = list.toMutableList().apply { add(indexOf(a) + 1, sticky) }
            store.batch {
                ordered.forEachIndexed { i, s ->
                    if (s.position != i.toDouble() || s.id == id) change(STICKY_TYPE, s.id, mapOf("position" to JsonPrimitive(i.toDouble())))
                }
            }
            return
        }
        val position = when {
            a != null && b != null -> (a.position + b.position) / 2
            a != null -> a.position + 1
            b != null -> b.position - 1
            else -> 0.0
        }
        if (position != sticky.position) store.change(STICKY_TYPE, id, mapOf("position" to JsonPrimitive(position)))
    }

    private suspend fun topPosition(contextId: String): Double = (dao.minPosition(contextId) ?: 1.0) - 1

    private companion object {
        const val MIN_GAP = 1e-6
    }
}

class StickyCaptureTarget(private val repository: StickyRepository) : CaptureTarget {
    override val key = "stickies"
    override val displayName = "Post-it"
    override val entityType = STICKY_TYPE
    override suspend fun createData(content: CaptureContent, contextId: String) =
        repository.newData(contextId, content.title, content.text)
}
