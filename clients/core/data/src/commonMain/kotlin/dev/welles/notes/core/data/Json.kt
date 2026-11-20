package dev.welles.notes.core.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Tolerant field access on raw entity JSON: wrong or missing values fall back to defaults (data from the server is already validated). */
fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.bool(name: String, default: Boolean = false): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.double(name: String, default: Double = 0.0): Double = (this[name] as? JsonPrimitive)?.doubleOrNull ?: default
fun JsonObject.long(name: String, default: Long = 0): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: default

/** Merge semantics like the server: keys of [patch] replace (an explicit null stays as JSON null), everything else is kept. */
fun JsonObject.merge(patch: Map<String, JsonElement>): JsonObject = JsonObject(this + patch)

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> error("Unsupported JSON value: $this")
}
