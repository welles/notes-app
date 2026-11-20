package dev.welles.notes.core.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The hand-written DTOs must match api/openapi.json exactly (field names, enum values). */
@OptIn(ExperimentalSerializationApi::class)
class ApiContractTest {
    private val spec = NotesJson.parseToJsonElement(
        File(System.getProperty("notes.repoRoot") ?: "../..", "api/openapi.json").readText(),
    ).jsonObject["components"]!!.jsonObject["schemas"]!!.jsonObject

    private val dtos: Map<String, KSerializer<*>> = mapOf(
        "SyncEnvelope" to SyncEnvelope.serializer(),
        "PushEnvelope" to PushEnvelope.serializer(),
        "PushRequest" to PushRequest.serializer(),
        "PushEntryResult" to PushEntryResult.serializer(),
        "PushResponse" to PushResponse.serializer(),
        "PullResponse" to PullResponse.serializer(),
        "MetaResponse" to MetaResponse.serializer(),
        "ProtocolRange" to ProtocolRange.serializer(),
        "EntityRef" to EntityRefDto.serializer(),
        "PushStatus" to PushStatus.serializer(),
        "ConflictWinner" to ConflictWinner.serializer(),
    )

    @Test
    fun dtos_match_the_openapi_spec() {
        for ((name, serializer) in dtos) {
            val schema = spec[name]?.jsonObject ?: error("Schema $name missing in api/openapi.json")
            val d = serializer.descriptor
            if (d.kind == SerialKind.ENUM) {
                val expected = schema["enum"]!!.jsonArray.filter { it !is kotlinx.serialization.json.JsonNull }.map { it.jsonPrimitive.content }.toSet()
                assertEquals(expected, (0 until d.elementsCount).map { d.getElementName(it) }.toSet(), "enum $name")
            } else {
                val expected = schema["properties"]!!.jsonObject.keys
                assertEquals(expected, (0 until d.elementsCount).map { d.getElementName(it) }.toSet(), "properties of $name")
            }
        }
    }
}
