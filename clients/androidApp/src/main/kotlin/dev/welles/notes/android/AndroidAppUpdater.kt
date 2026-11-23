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
    @Volatile private var committedAt = 0L

    init {
        // APKs of earlier updates (installed or abandoned)
        scope.launch { dir.listFiles()?.forEach { it.delete() } }
    }

    override fun check() = launch { checkNow(manual = true) }

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
        if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS) launch { checkNow(manual = false) }
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
            // The settings screen may not exist on every device/profile.
            val opened = runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            installAfterPermission = opened
            _state.value = if (opened) UpdateState.NeedsPermission(release)
            else UpdateState.Failed("Installation unbekannter Apps nicht erlaubt", release)
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
        // Still copying the APK into the session: leave it alone.
        if (job?.isActive == true) return
        val installer = context.packageManager.packageInstaller
        val id = sessionId
        // A session that is gone ended without its result reaching us. One still there may be waiting for the
        // installer or installing without confirmation (slow devices), so it is given up only after a while.
        if (id != null && installer.getSessionInfo(id) != null) {
            if (System.currentTimeMillis() - committedAt < STUCK_AFTER_MS) return
            runCatching { installer.abandonSession(id) }
        }
        sessionId = null
        _state.value = UpdateState.Available(release)
    }

    /** The installer needs a confirmation (from [UpdateInstallReceiver]). Blocked while Notes is in the background; [checkIfDue] retries. */
    internal fun onConfirmationNeeded(session: Int, intent: Intent) {
        if (session != sessionId) return
        confirmation = intent
        showConfirmation(intent)
    }

    private fun showConfirmation(intent: Intent) {
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Confirmation not shown", it) }
    }

    /** Result of the installation session (from [UpdateInstallReceiver]). */
    internal fun onInstallResult(session: Int, status: Int, message: String?) {
        // Late result of a session given up earlier
        if (session != sessionId) return
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

    /** Only a successful check counts for the hourly interval; offline, the next foreground tries again. */
    private suspend fun checkNow(manual: Boolean) {
        val previous = _state.value
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = AppReleases.latest(http)
            lastCheck = System.currentTimeMillis()
            if (release != null && release.version > versionCode) UpdateState.Available(release) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Update check failed", e)
            // A failed check keeps a known update; only a manual check without one shows the error.
            when {
                releaseOf(previous) != null -> previous
                manual -> UpdateState.Failed(e.message ?: e.toString())
                else -> previous
            }
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
                committedAt = System.currentTimeMillis()
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
        /** A session without confirmation or result after this long counts as stuck ("Fortsetzen" gives it up); a silent install can take a while on slow devices. */
        const val STUCK_AFTER_MS = 2 * 60_000L
    }
}

/** Status of the installation session: shows the system confirmation, reports the result to the updater. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val updater = GlobalContext.getOrNull()?.getOrNull<AppUpdater>() as? AndroidAppUpdater
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            // Not exported: the intent comes from the system installer via our own PendingIntent.
            if (confirm != null) updater?.onConfirmationNeeded(session, confirm)
            return
        }
        updater?.onInstallResult(session, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

private fun releaseOf(state: UpdateState): AppRelease? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.NeedsPermission -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.Installing -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}
