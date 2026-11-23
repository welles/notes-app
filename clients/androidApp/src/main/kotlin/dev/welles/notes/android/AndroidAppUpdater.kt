package dev.welles.notes.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.welles.notes.core.sync.AppRelease
import dev.welles.notes.core.sync.AppReleases
import dev.welles.notes.core.sync.AppUpdater
import dev.welles.notes.core.sync.UpdateState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Self-update from the GitHub releases: checks `releases/latest`, downloads `notes-N.apk`, verifies size, SHA-256
 * and package name, and hands it to [PackageInstaller]. Android itself checks the signature (same key as installed)
 * and asks for confirmation, unless Notes installed the current version itself (Android 12+).
 */
class AndroidAppUpdater(
    private val context: Context,
    private val versionCode: Int,
    override val currentVersion: String,
) : AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient {
        install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 }
        defaultRequest { header("User-Agent", "notes-android/$currentVersion") }
    }
    private val dir = File(context.cacheDir, "updates")
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state

    private var job: Job? = null
    private var lastCheck = 0L
    /** The user was sent to "Install unknown apps"; on the next return to the foreground continue (if granted) or not at all. */
    @Volatile private var installAfterPermission = false
    /** Session handed to the installer and its confirmation (relaunched if it could not be shown or was left with Home). */
    @Volatile private var sessionId: Int? = null
    @Volatile private var confirmation: Intent? = null

    init {
        // APKs of earlier updates (installed or abandoned)
        scope.launch { dir.listFiles()?.forEach { it.delete() } }
    }

    override fun check() = launch { checkNow() }

    override fun checkIfDue() {
        val s = _state.value
        // Back in the foreground: show a confirmation that was blocked in the background or left with Home.
        // Delayed: after "Abbrechen" in the dialog the app may be back before the ABORTED status arrives.
        if (s is UpdateState.Installing) {
            val pending = confirmation ?: return
            scope.launch {
                delay(1_500)
                if (_state.value === s && confirmation === pending) showConfirmation(pending)
            }
            return
        }
        if (s is UpdateState.Downloading) return
        if (installAfterPermission) {
            installAfterPermission = false
            if (s is UpdateState.NeedsPermission && canInstall()) return install()
        }
        if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS) launch { checkNow() }
    }

    override fun install() {
        if (_state.value is UpdateState.Installing) return resumeInstallation()
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        } ?: return
        if (job?.isActive == true) return
        if (!canInstall()) {
            installAfterPermission = true
            _state.value = UpdateState.NeedsPermission(release)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        installAfterPermission = false
        launch {
            try {
                val file = download(release)
                _state.value = UpdateState.Installing(release)
                commit(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.toString(), release)
            }
        }
    }

    /** Shows the confirmation again, or gives up a session that never asked for one. */
    private fun resumeInstallation() {
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        confirmation?.let { return showConfirmation(it) }
        sessionId?.let { id -> runCatching { context.packageManager.packageInstaller.abandonSession(id) } }
        sessionId = null
        _state.value = UpdateState.Available(release)
    }

    /** The installer needs a confirmation (from [UpdateInstallReceiver]). Blocked while Notes is in the background; [checkIfDue] retries. */
    internal fun onConfirmationNeeded(intent: Intent) {
        confirmation = intent
        showConfirmation(intent)
    }

    private fun showConfirmation(intent: Intent) {
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Confirmation not shown", it) }
    }

    /** Result of the installation session (from [UpdateInstallReceiver]). */
    internal fun onInstallResult(status: Int, message: String?) {
        sessionId = null
        confirmation = null
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(release)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateState.Failed("Signatur passt nicht zur installierten App", release)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateState.Failed(message ?: "Nicht kompatibel mit diesem Gerät", release)
            else -> UpdateState.Failed(message ?: "Installation fehlgeschlagen ($status)", release)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch { block() }
    }

    private fun canInstall() = context.packageManager.canRequestPackageInstalls()

    private suspend fun checkNow() {
        lastCheck = System.currentTimeMillis()
        val previous = _state.value
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = AppReleases.latest(http)
            if (release != null && release.version > versionCode) UpdateState.Available(release) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Update check failed", e)
            // An automatic check offline should not turn a known update into an error.
            if (previous is UpdateState.Available) previous else UpdateState.Failed(e.message ?: e.toString())
        }
    }

    private suspend fun download(release: AppRelease): File {
        dir.mkdirs()
        val file = File(dir, "notes-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        _state.value = UpdateState.Downloading(release, null)
        http.prepareGet(release.apkUrl).execute { response ->
            if (!response.status.isSuccess()) error("Download: HTTP ${response.status.value}")
            val total = response.contentLength() ?: release.size.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var shownPercent = -1
                while (true) {
                    val n = channel.readAvailable(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total != null) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != shownPercent) {
                            shownPercent = percent
                            _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
            }
        }
        try {
            if (release.size > 0 && file.length() != release.size) error("Download unvollständig")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && sha256 != release.sha256) error("Prüfsumme stimmt nicht")
            val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
            if (archive?.packageName != context.packageName) error("Keine Notes-APK")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        sessionId = id
        confirmation = null
        try {
            installer.openSession(id).use { session ->
                session.openWrite("notes.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds status and confirmation intent as extras.
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            sessionId = null
            throw e
        }
    }

    private companion object {
        const val TAG = "NotesUpdate"
        /** Automatic checks (app comes to the foreground) at most once an hour; GitHub allows 60 requests/hour. */
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }
}

/** Status of the installation session: shows the system confirmation, reports the result to the updater. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val updater = GlobalContext.getOrNull()?.getOrNull<AppUpdater>() as? AndroidAppUpdater
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            // Not exported: the intent comes from the system installer via our own PendingIntent.
            if (confirm != null) updater?.onConfirmationNeeded(confirm)
            return
        }
        updater?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
