package dev.welles.notes.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

/** Global reference to any entity of any module. */
data class EntityRef(val entityType: String, val id: String)

/** Module-neutral content: every module can export it, every capture target can create from it. */
data class CaptureContent(val title: String?, val text: String, val source: EntityRef? = null)

object Contexts {
    const val TYPE = "core.context"

    /** Fixed, well-known id of the default context "Privat" (same as on the server). */
    const val DEFAULT_ID = "019a0000-0000-7000-8000-000000000001"
    const val DEFAULT_NAME = "Privat"
}

/** Shared colour names (post-its and context accents). Unknown names are kept, the UI falls back for display. */
object NoteColorNames {
    val all = listOf("yellow", "green", "blue", "pink", "purple", "orange", "gray")
    const val DEFAULT = "yellow"
}

object Ids {
    /** UUIDv7: time-ordered, generated on the client, globally unique across modules. */
    fun newId(clock: Clock = Clock.System): String {
        val ms = clock.now().toEpochMilliseconds()
        val random = Uuid.random().toByteArray()
        val bytes = ByteArray(16)
        for (i in 0 until 6) bytes[i] = (ms ushr (8 * (5 - i))).toByte()
        for (i in 6 until 16) bytes[i] = random[i]
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }
}

object SyncTime {
    /** ISO-8601 UTC with millisecond precision – the format used everywhere in sync. */
    fun format(instant: Instant): String {
        val ms = instant.toEpochMilliseconds()
        return Instant.fromEpochMilliseconds(ms).toString().let { s ->
            // Instant.toString omits zero fractions; normalise to exactly 3 digits.
            val base = s.removeSuffix("Z")
            val dot = base.indexOf('.')
            if (dot < 0) "$base.000Z" else base.substring(0, dot) + "." + base.substring(dot + 1).padEnd(3, '0').take(3) + "Z"
        }
    }

    fun parse(value: String): Instant = Instant.parse(value)

    fun now(clock: Clock = Clock.System): String = format(clock.now())
}

/** JSON settings for the API: tolerant reading (spec 5.2), explicit nulls are meaningful (null clears a field). */
val NotesJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
    coerceInputValues = false
}
