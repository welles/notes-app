package dev.welles.notes.app

import dev.welles.notes.core.sync.CredentialStore
import dev.welles.notes.core.sync.ServerConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Credentials in the Secret Service (gnome-keyring, KWallet, KeePassXC …) via `secret-tool` (libsecret).
 * Without a Secret Service: fallback file with permissions 0600 under $XDG_CONFIG_HOME/notes/, with a warning.
 */
class DesktopCredentialStore(private val configDir: File = DesktopDirs.config) : CredentialStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val fallback = File(configDir, "credentials.json")
    private val attributes = listOf("application", "dev.welles.notes", "key", "server")

    override suspend fun load(): ServerConfig? = withContext(Dispatchers.IO) {
        if (fallback.exists()) return@withContext runCatching { json.decodeFromString(ServerConfig.serializer(), fallback.readText()) }.getOrNull()
        run(listOf("secret-tool", "lookup") + attributes)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(ServerConfig.serializer(), it) }.getOrNull() }
    }

    override suspend fun save(config: ServerConfig): Unit = withContext(Dispatchers.IO) {
        val text = json.encodeToString(ServerConfig.serializer(), config)
        if (run(listOf("secret-tool", "store", "--label=Notes (${config.url})") + attributes, stdin = text) != null) {
            fallback.delete()
        } else {
            System.err.println("WARNUNG: Kein Secret Service erreichbar – Zugangsdaten werden in ${fallback.path} (0600) gespeichert.")
            fallback.parentFile.mkdirs()
            if (!fallback.exists()) Files.createFile(fallback.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.setPosixFilePermissions(fallback.toPath(), PosixFilePermissions.fromString("rw-------"))
            fallback.writeText(text)
        }
        Unit
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        fallback.delete()
        run(listOf("secret-tool", "clear") + attributes)
        Unit
    }

    /** Runs a command; returns stdout on exit code 0, null otherwise (also if the tool is missing). */
    private fun run(command: List<String>, stdin: String? = null): String? = runCatching {
        val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        val output = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); return null }
        if (p.exitValue() == 0) output else null
    }.getOrNull()
}
