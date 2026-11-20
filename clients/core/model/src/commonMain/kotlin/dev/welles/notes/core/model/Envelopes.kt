package dev.welles.notes.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Hand-written DTOs for the API contract in api/openapi.json (decision: generators struggle with OpenAPI 3.1).
 * A test (ApiContractTest) checks every field against the committed spec.
 * Rules (spec 5.2): additive only, unknown fields ignored when reading, unknown data preserved via raw JSON.
 */

/** Entity as delivered by pull. `data` is the full JSON of the entity. */
@Serializable
data class SyncEnvelope(
    val entityType: String,
    val id: String,
    val version: Long,
    val deleted: Boolean,
    val data: JsonObject,
)

/** A change sent to the server. `baseVersion` is null for new entities. `data` is merged on the server. */
@Serializable
data class PushEnvelope(
    val entityType: String,
    val id: String,
    val baseVersion: Long?,
    val modifiedAt: String,
    val deleted: Boolean,
    val data: JsonObject?,
)

@Serializable
data class PushRequest(val changes: List<PushEnvelope>)

@Serializable
enum class PushStatus {
    @SerialName("accepted") Accepted,
    @SerialName("conflict") Conflict,
    @SerialName("rejected") Rejected,
}

@Serializable
enum class ConflictWinner {
    @SerialName("client") Client,
    @SerialName("server") Server,
}

@Serializable
data class EntityRefDto(val entityType: String, val id: String)

@Serializable
data class PushEntryResult(
    val entityType: String,
    val id: String,
    val status: PushStatus,
    val reason: String? = null,
    val winner: ConflictWinner? = null,
    val conflictCopy: EntityRefDto? = null,
    val server: SyncEnvelope? = null,
)

@Serializable
data class PushResponse(val results: List<PushEntryResult>)

@Serializable
data class PullResponse(
    val changes: List<SyncEnvelope>,
    val cursor: Long,
    val hasMore: Boolean,
    val fullResyncRequired: Boolean,
)

@Serializable
data class ProtocolRange(val min: Int, val max: Int)

@Serializable
data class MetaResponse(
    val serverVersion: String,
    val protocol: ProtocolRange,
    val entityTypes: List<String>,
    val capabilities: List<String>,
    val minClientVersion: Map<String, String> = emptyMap(),
)

object RejectReasons {
    const val UNKNOWN_TYPE = "unknown_type"
    const val UNKNOWN_CONTEXT = "unknown_context"
    const val INVALID_DATA = "invalid_data"
}

/** Protocol version this client speaks (header `Notes-Protocol`). */
const val PROTOCOL_VERSION = 1
