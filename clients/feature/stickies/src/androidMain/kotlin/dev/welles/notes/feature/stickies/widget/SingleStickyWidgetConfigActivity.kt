package dev.welles.notes.feature.stickies.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.ui.NoteColors
import dev.welles.notes.core.ui.NotesTheme
import dev.welles.notes.feature.stickies.data.Sticky
import dev.welles.notes.feature.stickies.data.StickyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Shown when placing a single post-it widget: choose the post-it (from the active context). */
class SingleStickyWidgetConfigActivity : ComponentActivity() {
    private val repository: StickyRepository by inject()
    private val contexts: ContextRepository by inject()
    private val settings: LocalSettingsDao by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        setContent {
            NotesTheme {
                var stickies by remember { mutableStateOf<List<Sticky>?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { stickies = repository.observe(contexts.activeIdOrDefault()).first() }
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { Text("Welches Post-it soll das Widget zeigen?", style = MaterialTheme.typography.titleLarge) }
                        if (stickies?.isEmpty() == true) item { Text("Im aktiven Kontext gibt es noch keine Post-its.") }
                        items(stickies.orEmpty(), key = { it.id }) { s ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = NoteColors.background(s.color), contentColor = NoteColors.text),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        saveWidgetSticky(settings, appWidgetId, s.id)
                                        val glanceId = GlanceAppWidgetManager(this@SingleStickyWidgetConfigActivity).getGlanceIdBy(appWidgetId)
                                        SingleStickyWidget().update(this@SingleStickyWidgetConfigActivity, glanceId)
                                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                        finish()
                                    }
                                },
                            ) {
                                androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) {
                                    s.title?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                                    Text(s.content, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
