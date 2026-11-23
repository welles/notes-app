package dev.welles.notes.feature.stickies.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.ui.NoteColors
import dev.welles.notes.core.ui.NotesTheme
import dev.welles.notes.feature.stickies.data.Sticky
import dev.welles.notes.feature.stickies.data.StickyRepository
import dev.welles.notes.feature.stickies.data.preview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Shown when placing (or reconfiguring) a single post-it widget: choose the post-it (from the active context) and the background. */
class SingleStickyWidgetConfigActivity : ComponentActivity() {
    private val repository: StickyRepository by inject()
    private val contexts: ContextRepository by inject()
    private val settings: LocalSettingsDao by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = configuredWidgetId(SingleStickyWidgetReceiver::class.java) ?: return finish()
        // When placing, a post-it must be chosen; "back" cancels the placement.
        setWidgetResult(appWidgetId, ok = false)

        setContent {
            NotesTheme {
                var stickies by remember { mutableStateOf<List<Sticky>?>(null) }
                var background by rememberSaveable { mutableStateOf<WidgetBackground?>(null) }
                /** The post-it shown so far; null when placing. */
                var currentId by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    currentId = settings.get(WidgetConfig.key(appWidgetId))
                    if (background == null) background = WidgetConfig.background(settings, appWidgetId)
                    stickies = repository.observe(contexts.activeIdOrDefault()).first()
                }
                fun save(stickyId: String?) = scope.launch {
                    stickyId?.let { saveWidgetSticky(settings, appWidgetId, it) }
                    background?.let { WidgetConfig.setBackground(settings, appWidgetId, it) }
                    finishConfiguration(appWidgetId, SingleStickyWidget())
                }
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { Text("Post-it", style = MaterialTheme.typography.titleLarge) }
                        background?.let { current -> item { WidgetBackgroundChoice(current) { background = it } } }
                        if (currentId != null) {
                            // Reconfiguring: change only the background and keep the post-it.
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Button(onClick = { save(null) }, enabled = background != null) { Text("Fertig") }
                                }
                            }
                        }
                        if (stickies?.isNotEmpty() == true) {
                            item { Text(if (currentId != null) "Anderes Post-it wählen" else "Post-it wählen", style = MaterialTheme.typography.titleSmall) }
                        }
                        if (stickies?.isEmpty() == true) item { Text("Keine Post-its") }
                        items(stickies.orEmpty(), key = { it.id }) { s ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = NoteColors.background(s.color), contentColor = NoteColors.text),
                                border = if (s.id == currentId) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = background != null) { save(s.id) },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    val (heading, body) = s.preview()
                                    heading?.let { Text(it, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    if (body.isNotEmpty()) Text(body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
