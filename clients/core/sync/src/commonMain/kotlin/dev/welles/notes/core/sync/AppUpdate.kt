package dev.welles.notes.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A released version N with its APK (`notes-N.apk` in the GitHub release `vN`). */
data class AppRelease(val version: Int, val apkUrl: String, val size: Long, val sha256: String?)

/** Releases are published by `.github/workflows/release.yml` as GitHub releases of the public repository. */
object AppReleases {
    const val LATEST_URL = "https://api.github.com/repos/welles/notes-app/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    internal data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    internal data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        /** "sha256:<hex>", set by GitHub for assets uploaded since mid-2025. */
        val digest: String? = null,
    )

    /** Release `vN` with asset `notes-N.apk`, or null if the release has no such APK (or is a draft/pre-release). */
    fun parse(text: String): AppRelease? {
        val release = runCatching { json.decodeFromString(GitHubRelease.serializer(), text) }.getOrNull() ?: return null
        if (release.draft || release.prerelease) return null
        val version = release.tagName.removePrefix("v").toIntOrNull() ?: return null
        val apk = release.assets.firstOrNull { it.name == "notes-$version.apk" } ?: return null
        if (!apk.url.startsWith("https://")) return null
        val sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        return AppRelease(version, apk.url, apk.size, sha256)
    }

    /** The latest release; throws on network/HTTP errors, null if it has no usable APK. */
    suspend fun latest(http: HttpClient): AppRelease? {
        val response = http.get(LATEST_URL) {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) error("GitHub: HTTP ${response.status.value}")
        return parse(response.bodyAsText())
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** The system setting "Install unknown apps" must be allowed for Notes first. */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    /** [progress] 0..1, null while the size is unknown. */
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateState
    /** Downloaded and verified; installing ends the app, so it starts only on request (or with the settings section on screen). */
    data class Ready(val release: AppRelease) : UpdateState
    /** Handed to the system installer, which asks for confirmation. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/** Optional self-update (Android release builds, installed from the GitHub release APK). */
interface AppUpdater {
    val currentVersion: String
    val state: StateFlow<UpdateState>

    /** Checks now (button in the settings). */
    fun check()

    /** Checks if the last check is long enough ago (app comes to the foreground). */
    fun checkIfDue()

    /** Downloads the available release ([UpdateState.Ready]); in that state: starts the installation. */
    fun install()
}
