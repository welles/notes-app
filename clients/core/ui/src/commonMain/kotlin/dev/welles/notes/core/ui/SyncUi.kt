package dev.welles.notes.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.sync.QrScanner
import dev.welles.notes.core.sync.ServerConfig
import dev.welles.notes.core.sync.SyncController
import dev.welles.notes.core.sync.SyncStatus
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/** Discreet sync state in the top bar: only visible when something needs attention or is running. */
class SyncIndicator(private val controller: SyncController) : TopBarAction {
    @Composable
    override fun Content(navigator: Navigator) {
        val state by controller.state.collectAsStateCompat()
        when (val s = state.status) {
            SyncStatus.Syncing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            is SyncStatus.Failed -> IconButton(onClick = { navigator.navigate(ROUTE_SETTINGS) }) {
                when {
                    s.offline -> Icon(Icons.Filled.CloudOff, "Offline – Änderungen werden später übertragen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.credentials -> Icon(Icons.Filled.CloudOff, "Zugangsdaten prüfen", tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Filled.SyncProblem, "Sync-Fehler", tint = MaterialTheme.colorScheme.error)
                }
            }
            SyncStatus.UpdateRequired -> IconButton(onClick = { navigator.navigate(ROUTE_SETTINGS) }) {
                Icon(Icons.Filled.SystemUpdate, "Update nötig, Sync pausiert", tint = MaterialTheme.colorScheme.error)
            }
            else -> if (state.updateRecommended) IconButton(onClick = { navigator.navigate(ROUTE_SETTINGS) }) { Icon(Icons.Filled.SystemUpdate, "Update empfohlen") }
        }
    }
}

/** Settings: server URL, Cloudflare service token, QR / JSON setup, connection test, status. */
class SyncSettingsSection(private val controller: SyncController) : SettingsSection {
    override val order = 10

    @Composable
    override fun Content(navigator: Navigator) {
        val state by controller.state.collectAsStateCompat()
        val scanner: QrScanner? = getKoin().getOrNull()
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf(ServerConfig.DEFAULT_URL) }
        var clientId by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var message by remember { mutableStateOf<String?>(null) }
        var pasting by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            controller.loadCredentials()?.let { url = it.url; clientId = it.clientId; secret = it.clientSecret }
        }

        fun apply(config: ServerConfig) {
            url = config.url; clientId = config.clientId; secret = config.clientSecret
            scope.launch {
                controller.saveCredentials(config)
                message = controller.testConnection(config).fold({ it }, { "Fehler: ${it.message}" })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Synchronisation", style = MaterialTheme.typography.titleMedium)
            Text(statusText(state.status, state.lastSync), style = MaterialTheme.typography.bodyMedium)
            if (state.pending > 0) Text("${state.pending} lokale Änderung(en) noch nicht übertragen", style = MaterialTheme.typography.bodySmall)
            if (state.waitingForServer > 0) Text("${state.waitingForServer} Änderung(en) warten auf ein Server-Update", style = MaterialTheme.typography.bodySmall)
            if (state.updateRecommended) Text("Update empfohlen: Der Server erwartet eine neuere App-Version.", color = MaterialTheme.colorScheme.tertiary)

            OutlinedTextField(url, { url = it }, label = { Text("Server-URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(clientId, { clientId = it }, label = { Text("CF-Access-Client-ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text("CF-Access-Client-Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
            Text("Leer lassen für lokale Entwicklung ohne Cloudflare.", style = MaterialTheme.typography.bodySmall)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { apply(ServerConfig(url, clientId, secret)) }) { Text("Speichern & verbinden") }
                OutlinedButton(onClick = { scope.launch { message = controller.testConnection(ServerConfig(url, clientId, secret)).fold({ it }, { "Fehler: ${it.message}" }) } }) {
                    Text("Testen")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (scanner != null) OutlinedButton(onClick = {
                    scope.launch {
                        val text = runCatching { scanner.scan() }.getOrNull()
                        val config = text?.let { ServerConfig.parseSetupJson(it) }
                        if (config != null) apply(config) else if (text != null) message = "QR-Code enthält keine gültige Notes-Konfiguration."
                    }
                }) { Text("QR-Code scannen") }
                OutlinedButton(onClick = { pasting = true }) { Text("JSON einfügen") }
                TextButton(onClick = { controller.requestSync() }) { Text("Jetzt synchronisieren") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (pasting) {
            var json by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { pasting = false },
                title = { Text("Konfiguration einfügen") },
                text = {
                    OutlinedTextField(json, { json = it }, placeholder = { Text("{\"url\": …, \"clientId\": …, \"clientSecret\": …}") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = {
                        val config = ServerConfig.parseSetupJson(json)
                        if (config != null) { pasting = false; apply(config) } else message = "Kein gültiges JSON."
                    }) { Text("Übernehmen") }
                },
                dismissButton = { TextButton(onClick = { pasting = false }) { Text("Abbrechen") } },
            )
        }
    }

    private fun statusText(status: SyncStatus, last: String?): String = when (status) {
        SyncStatus.NotConfigured -> "Noch nicht eingerichtet – Daten bleiben nur auf diesem Gerät."
        SyncStatus.Syncing -> "Synchronisiere …"
        SyncStatus.UpdateRequired -> "Update nötig, Sync pausiert. Lokale Daten bleiben erhalten."
        is SyncStatus.Failed -> when {
            status.offline -> "Offline – Änderungen bleiben gespeichert und werden später übertragen."
            status.credentials -> "Zugangsdaten prüfen: ${status.message}"
            else -> "Fehler: ${status.message} (wird erneut versucht)"
        }
        else -> last?.let { "Zuletzt synchronisiert: ${formatTime(it)}" } ?: "Noch nicht synchronisiert"
    }
}

/** "2026-11-20T10:05:00.000Z" → local "20.11.2026 11:05". */
internal fun formatTime(iso: String): String = runCatching {
    val local = kotlin.time.Instant.parse(iso).toLocalDateTimeString()
    local
}.getOrDefault(iso)

internal expect fun kotlin.time.Instant.toLocalDateTimeString(): String

