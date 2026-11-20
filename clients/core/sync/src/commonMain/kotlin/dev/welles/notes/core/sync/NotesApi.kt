package dev.welles.notes.core.sync

import dev.welles.notes.core.model.MetaResponse
import dev.welles.notes.core.model.NotesJson
import dev.welles.notes.core.model.PROTOCOL_VERSION
import dev.welles.notes.core.model.PullResponse
import dev.welles.notes.core.model.PushEnvelope
import dev.welles.notes.core.model.PushRequest
import dev.welles.notes.core.model.PushResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer

sealed class ApiException(message: String) : Exception(message) {
    /** 401/403 or Cloudflare Access login page instead of JSON → "Zugangsdaten prüfen". */
    class Unauthorized(detail: String) : ApiException("Zugangsdaten ungültig ($detail)")
    /** 426: this client's protocol is no longer supported – sync paused until update. */
    class ProtocolUnsupported : ApiException("Protokoll wird vom Server nicht mehr unterstützt")
    class Http(val status: Int, body: String) : ApiException("HTTP $status: ${body.take(200)}")
    class Network(cause: Throwable) : ApiException(cause.message ?: cause.toString())
}

interface NotesApi {
    suspend fun meta(): MetaResponse
    suspend fun pull(since: Long, limit: Int): PullResponse
    suspend fun push(changes: List<PushEnvelope>): PushResponse

    /** Live hints (capability `sync.events`): the latest server version, first on connect, then on every change. */
    fun events(): Flow<Long> = emptyFlow()
}

/** Ktor implementation. Redirects are not followed: Cloudflare's login redirect must surface as [ApiException.Unauthorized]. */
class KtorNotesApi(private val config: ServerConfig, private val client: ClientInfo, engineClient: HttpClient = HttpClient()) : NotesApi {
    private val http = engineClient.config {
        followRedirects = false
        expectSuccess = false
        install(ContentNegotiation) { json(NotesJson) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
        install(SSE)
        defaultRequest {
            header("Notes-Client", client.header)
            header("Notes-Protocol", PROTOCOL_VERSION.toString())
            if (config.hasServiceToken) {
                header("CF-Access-Client-Id", config.clientId.trim())
                header("CF-Access-Client-Secret", config.clientSecret.trim())
            }
        }
    }
    private val base = config.url.trimEnd('/')

    override suspend fun meta(): MetaResponse = call(MetaResponse.serializer()) { http.get("$base/api/meta") }

    override suspend fun pull(since: Long, limit: Int): PullResponse = call(PullResponse.serializer()) {
        http.get("$base/api/sync/changes") { parameter("since", since); parameter("limit", limit) }
    }

    override suspend fun push(changes: List<PushEnvelope>): PushResponse = call(PushResponse.serializer()) {
        http.post("$base/api/sync/push") { json(); setBody(PushRequest(changes)) }
    }

    override fun events(): Flow<Long> = flow {
        try {
            http.sse("$base/api/sync/events", request = {
                // Open-ended stream; heartbeats every 25 s keep the socket busy, so a dead connection shows up within 90 s.
                timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 90_000 }
            }) {
                incoming.collect { event ->
                    if (event.event == "changed") event.data?.trim()?.toLongOrNull()?.let { emit(it) }
                }
            }
        } catch (e: io.ktor.client.plugins.sse.SSEClientException) {
            val status = e.response?.status?.value
            throw if (status == 401 || status == 403 || status in 300..399) ApiException.Unauthorized("HTTP $status") else ApiException.Network(e)
        }
    }

    private fun HttpRequestBuilder.json() = contentType(ContentType.Application.Json)

    private suspend fun <T> call(serializer: KSerializer<T>, request: suspend () -> HttpResponse): T {
        val response = try {
            request()
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.Network(e)
        }
        val status = response.status.value
        val body = response.bodyAsText()
        when {
            status == 426 -> throw ApiException.ProtocolUnsupported()
            status == 401 || status == 403 -> throw ApiException.Unauthorized("HTTP $status")
            status in 300..399 -> throw ApiException.Unauthorized("Weiterleitung zu ${response.headers["Location"] ?: "Login"}")
            !response.status.isSuccess() -> throw ApiException.Http(status, body)
        }
        val type = response.headers["Content-Type"].orEmpty()
        if (!type.contains("json")) throw ApiException.Unauthorized("Antwort ist kein JSON (Cloudflare-Login?)")
        return NotesJson.decodeFromString(serializer, body)
    }
}
