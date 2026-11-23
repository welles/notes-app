package dev.welles.notes.feature.stickies.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.ui.NotesTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Background of the pinned post-its widget: on reconfiguring, and on Android < 12 also when placing it. */
class PinnedStickiesWidgetConfigActivity : ComponentActivity() {
    private val settings: LocalSettingsDao by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = configuredWidgetId(PinnedStickiesWidgetReceiver::class.java) ?: return finish()
        // Nothing here is required: leaving with "back" keeps the widget (Android < 12 would remove it on CANCELED).
        setWidgetResult(appWidgetId, ok = true)

        setContent {
            NotesTheme {
                var background by rememberSaveable { mutableStateOf<WidgetBackground?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { if (background == null) background = WidgetConfig.background(settings, appWidgetId) }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Angepinnte Post-its", style = MaterialTheme.typography.titleLarge)
                        background?.let { current ->
                            WidgetBackgroundChoice(current) { background = it }
                            Button(
                                onClick = {
                                    scope.launch {
                                        WidgetConfig.setBackground(settings, appWidgetId, current)
                                        finishConfiguration(appWidgetId, PinnedStickiesWidget())
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
}
