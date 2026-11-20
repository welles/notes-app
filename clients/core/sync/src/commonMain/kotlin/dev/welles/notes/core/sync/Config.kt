package dev.welles.notes.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Connection to the server. Without client id/secret the Cloudflare headers are omitted (local development). */
@Serializable
data class ServerConfig(
    val url: String = DEFAULT_URL,
    val clientId: String = "",
    val clientSecret: String = "",
) {
    val hasServiceToken: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {
        const val DEFAULT_URL = "https://notes.welles.app"

        /** The JSON from the QR code / clipboard: {"url": …, "clientId": …, "clientSecret": …}. */
        fun parseSetupJson(text: String): ServerConfig? = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text.trim())
        }.getOrNull()?.takeIf { it.url.startsWith("http") }
    }
}

/** Secure storage of the connection (Android Keystore / Secret Service). Implemented per platform. */
interface CredentialStore {
    suspend fun load(): ServerConfig?
    suspend fun save(config: ServerConfig)
    suspend fun clear()
}

/** Who we are, sent as `Notes-Client: android/1.4.0`. */
data class ClientInfo(val platform: String, val version: String) {
    val header: String get() = "$platform/$version"
}

/** Optional QR code scanner (Android with Google Play services). */
interface QrScanner {
    suspend fun scan(): String?
}
