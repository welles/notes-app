package dev.welles.notes.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.sync.AppUpdater
import dev.welles.notes.core.sync.UpdateState

/** Top bar: a new app version is available (or being installed). */
class AppUpdateIndicator(private val updater: AppUpdater) : TopBarAction {
    @Composable
    override fun Content(navigator: Navigator) {
        val state by updater.state.collectAsStateCompat()
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Downloading -> s.release
            else -> null
        } ?: return
        IconButton(onClick = { navigator.navigate(ROUTE_SETTINGS) }) {
            Icon(Icons.Filled.Download, "Version ${release.version} verfügbar", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Settings: installed version, check for updates, download and install. */
class AppUpdateSection(private val updater: AppUpdater) : SettingsSection {
    override val order = 90

    @Composable
    override fun Content(navigator: Navigator) {
        val state by updater.state.collectAsStateCompat()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App-Version", style = MaterialTheme.typography.titleMedium)
            Text("Installiert: ${updater.currentVersion}", style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                UpdateState.Idle -> {}
                UpdateState.Checking -> Text("Suche …", style = MaterialTheme.typography.bodySmall)
                UpdateState.UpToDate -> Text("Aktuell", style = MaterialTheme.typography.bodySmall)
                is UpdateState.Available -> Text("Version ${s.release.version} verfügbar", color = MaterialTheme.colorScheme.primary)
                is UpdateState.NeedsPermission -> Text("Installation aus dieser Quelle erlauben", color = MaterialTheme.colorScheme.tertiary)
                is UpdateState.Downloading -> {
                    Text("Lade Version ${s.release.version} …", style = MaterialTheme.typography.bodySmall)
                    val progress = s.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> Text("Installiere Version ${s.release.version} …", style = MaterialTheme.typography.bodySmall)
                is UpdateState.Failed -> Text("Fehler: ${s.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val s = state) {
                    is UpdateState.Available -> Button(onClick = updater::install) { Text("Aktualisieren") }
                    is UpdateState.NeedsPermission -> Button(onClick = updater::install) { Text("Erlauben") }
                    is UpdateState.Failed -> if (s.release != null) Button(onClick = updater::install) { Text("Erneut versuchen") }
                    else -> {}
                }
                val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
                OutlinedButton(onClick = updater::check, enabled = !busy) { Text("Nach Updates suchen") }
            }
        }
    }
}
