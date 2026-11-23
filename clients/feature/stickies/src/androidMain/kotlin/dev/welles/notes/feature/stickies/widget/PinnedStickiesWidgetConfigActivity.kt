package dev.welles.notes.feature.stickies.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.ui.NotesTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Shown when reconfiguring the pinned post-its widget (on Android < 12 also when placing it): background. */
class PinnedStickiesWidgetConfigActivity : ComponentActivity() {
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
                var transparent by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { transparent = WidgetConfig.isTransparent(settings, appWidgetId) }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Angepinnte Post-its", style = MaterialTheme.typography.titleLarge)
                        TransparentBackgroundSwitch(transparent) { transparent = it }
                        Button(
                            onClick = {
                                scope.launch {
                                    WidgetConfig.setTransparent(settings, appWidgetId, transparent)
                                    val glanceId = GlanceAppWidgetManager(this@PinnedStickiesWidgetConfigActivity).getGlanceIdBy(appWidgetId)
                                    PinnedStickiesWidget().update(this@PinnedStickiesWidgetConfigActivity, glanceId)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                    finish()
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Fertig") }
                    }
                }
            }
        }
    }
}

/** Option shared by the widget configuration screens. */
@Composable
internal fun TransparentBackgroundSwitch(transparent: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(transparent, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Transparenter Hintergrund", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Text steht direkt auf dem Hintergrundbild",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = transparent, onCheckedChange = null)
    }
}
