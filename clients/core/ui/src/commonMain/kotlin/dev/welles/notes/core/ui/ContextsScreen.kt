package dev.welles.notes.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Manage contexts: create, rename, colour, order, delete (only when empty), "move everything to …". */
@Composable
fun ContextsScreen() {
    val vm: ContextsViewModel = koinViewModel()
    val rows by vm.rows.collectAsStateCompat()
    val active = LocalActiveContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ContextsViewModel.Row?>(null) }
    var movingFrom by remember { mutableStateOf<ContextsViewModel.Row?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Kontexte", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp)) }
        itemsIndexed(rows, key = { _, r -> r.context.id }) { index, row ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { editing = row }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(NoteColors.accent(row.context.color), size = 14)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(row.context.name, style = MaterialTheme.typography.titleMedium)
                    val hints = buildList {
                        add("${row.count} Einträge")
                        if (row.context.isDefault) add("Standard")
                        if (row.context.id == active.id) add("aktiv auf diesem Gerät")
                    }
                    Text(hints.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.move(index, -1) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, "Nach oben") }
                IconButton(onClick = { vm.move(index, 1) }, enabled = index < rows.lastIndex) { Icon(Icons.Filled.ArrowDownward, "Nach unten") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Umbenennen / Farbe …") }, onClick = { menu = false; editing = row })
                    DropdownMenuItem(text = { Text("Alles verschieben nach …") }, enabled = row.count > 0, onClick = { menu = false; movingFrom = row })
                    DropdownMenuItem(
                        text = { Text(if (row.context.isDefault) "Löschen (Standard nicht möglich)" else if (row.count > 0) "Löschen (nur wenn leer)" else "Löschen") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        enabled = !row.context.isDefault && row.count == 0,
                        onClick = {
                            menu = false
                            vm.delete(row.context.id) { ok -> if (!ok) scope.launch { snackbar.showSnackbar("Der Kontext ist nicht leer und wurde nicht gelöscht.") } }
                        },
                    )
                }
            }
            HorizontalDivider()
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("Neuer Kontext") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.create(newName); newName = "" }, enabled = newName.isNotBlank()) { Text("Anlegen") }
            }
        }
    }

    editing?.let { row ->
        var name by remember(row) { mutableStateOf(row.context.name) }
        var color by remember(row) { mutableStateOf(row.context.color) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Kontext bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    ColorSwatches(color, { color = it }, accent = true)
                    TextButton(onClick = { color = null }) { Text("Keine Farbe") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name != row.context.name) vm.rename(row.context.id, name)
                    if (color != row.context.color) vm.setColor(row.context.id, color)
                    editing = null
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Abbrechen") } },
        )
    }

    movingFrom?.let { from ->
        MoveToContextDialog(rows.map { it.context }, exclude = from.context.id, title = "Alles aus „${from.context.name}“ verschieben nach …",
            onPick = { vm.moveAll(from.context.id, it); movingFrom = null }, onDismiss = { movingFrom = null })
    }
}
