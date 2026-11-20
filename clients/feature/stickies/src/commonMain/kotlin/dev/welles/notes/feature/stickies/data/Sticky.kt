package dev.welles.notes.feature.stickies.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import dev.welles.notes.core.data.LocalEntityHandler
import dev.welles.notes.core.data.LocalRecord
import dev.welles.notes.core.data.bool
import dev.welles.notes.core.data.double
import dev.welles.notes.core.data.str
import dev.welles.notes.core.model.CaptureContent
import dev.welles.notes.core.model.NoteColorNames
import dev.welles.notes.core.model.NotesJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

const val STICKY_TYPE = "stickies.sticky"

/** Post-it. Columns are a projection of [rawData] (full JSON incl. fields of newer versions). */
@Entity(tableName = "stickies_sticky", indices = [Index("contextId", "deletedAt")])
data class StickyEntity(
    @PrimaryKey val id: String,
    val contextId: String,
    val title: String?,
    val content: String,
    /** Stored unchanged, even if unknown (newer version); the UI falls back for display. */
    val color: String,
    val pinned: Boolean,
    val position: Double,
    val createdAt: String,
    val modifiedAt: String,
    val deletedAt: String?,
    val version: Long,
    val rawData: String,
)

@Dao
interface StickyDao {
    @Query("SELECT * FROM stickies_sticky WHERE contextId = :contextId AND deletedAt IS NULL ORDER BY pinned DESC, position")
    fun observeInContext(contextId: String): Flow<List<StickyEntity>>

    @Query("SELECT * FROM stickies_sticky WHERE contextId = :contextId AND deletedAt IS NULL AND pinned = 1 ORDER BY position")
    fun observePinned(contextId: String): Flow<List<StickyEntity>>

    @Query("SELECT * FROM stickies_sticky WHERE contextId = :contextId AND deletedAt IS NULL ORDER BY position")
    suspend fun listInContext(contextId: String): List<StickyEntity>

    @Query("SELECT * FROM stickies_sticky WHERE id = :id")
    suspend fun get(id: String): StickyEntity?

    @Query("SELECT * FROM stickies_sticky WHERE id = :id")
    fun observe(id: String): Flow<StickyEntity?>

    @Upsert
    suspend fun upsert(entity: StickyEntity)

    @Query("SELECT id FROM stickies_sticky WHERE contextId = :contextId AND deletedAt IS NULL")
    suspend fun idsInContext(contextId: String): List<String>

    @Query("DELETE FROM stickies_sticky WHERE id NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<String>)

    @Query("SELECT MIN(position) FROM stickies_sticky WHERE contextId = :contextId AND deletedAt IS NULL")
    suspend fun minPosition(contextId: String): Double?
}

data class Sticky(
    val id: String,
    val contextId: String,
    val title: String?,
    val content: String,
    val color: String,
    val pinned: Boolean,
    val position: Double,
    val deleted: Boolean,
)

fun StickyEntity.toModel() = Sticky(id, contextId, title, content, color, pinned, position, deletedAt != null)

class StickyHandler(private val dao: StickyDao) : LocalEntityHandler {
    override val entityType = STICKY_TYPE
    override val contextScoped = true

    override suspend fun get(id: String) = dao.get(id)?.let {
        LocalRecord(it.id, it.version, it.deletedAt != null, NotesJson.decodeFromString(JsonObject.serializer(), it.rawData))
    }

    override suspend fun put(record: LocalRecord) {
        val d = record.data
        dao.upsert(
            StickyEntity(
                id = record.id,
                contextId = d.str("contextId") ?: "",
                title = d.str("title")?.takeIf { it.isNotEmpty() },
                content = d.str("content") ?: "",
                color = d.str("color") ?: NoteColorNames.DEFAULT,
                pinned = d.bool("pinned"),
                position = d.double("position"),
                createdAt = d.str("createdAt") ?: "",
                modifiedAt = d.str("modifiedAt") ?: "",
                deletedAt = if (record.deleted) d.str("deletedAt") ?: d.str("modifiedAt") ?: "" else null,
                version = record.version,
                rawData = NotesJson.encodeToString(JsonObject.serializer(), d),
            ),
        )
    }

    override suspend fun idsInContext(contextId: String) = dao.idsInContext(contextId)
    override suspend fun deleteAllExcept(keep: List<String>) = dao.deleteAllExcept(keep)
    override fun export(data: JsonObject) = CaptureContent(data.str("title"), data.str("content") ?: "")
}
