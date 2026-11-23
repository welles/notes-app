package dev.welles.notes.feature.stickies.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The widget id this configuration activity was started for, or null if it is missing or does not belong to
 * [receiver] (the activities are exported, so any app could pass an arbitrary id).
 */
internal fun ComponentActivity.configuredWidgetId(receiver: Class<out GlanceAppWidgetReceiver>): Int? {
    val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        ?: AppWidgetManager.INVALID_APPWIDGET_ID
    if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null
    val ids = runCatching { AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, receiver)) }.getOrNull()
    return id.takeIf { ids?.contains(it) == true }
}

internal fun ComponentActivity.setWidgetResult(appWidgetId: Int, ok: Boolean) =
    setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

/** Settings are already stored (the running widget session observes them); this only refreshes a widget without a session. */
internal suspend fun ComponentActivity.finishConfiguration(appWidgetId: Int, widget: GlanceAppWidget) {
    runCatching { widget.update(this, GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)) }
    setWidgetResult(appWidgetId, ok = true)
    finish()
}

private fun WidgetBackground.label() = when (this) {
    WidgetBackground.Opaque -> "Deckend"
    WidgetBackground.TransparentLight -> "Transparent, helle Schrift"
    WidgetBackground.TransparentDark -> "Transparent, dunkle Schrift"
}

private fun WidgetBackground.hint() = when (this) {
    WidgetBackground.Opaque -> null
    WidgetBackground.TransparentLight -> "Für dunkle Hintergrundbilder"
    WidgetBackground.TransparentDark -> "Für helle Hintergrundbilder"
}

/** Background option shared by the widget configuration screens. */
@Composable
internal fun WidgetBackgroundChoice(selected: WidgetBackground, onSelect: (WidgetBackground) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text("Hintergrund", style = MaterialTheme.typography.titleSmall)
        WidgetBackground.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().selectable(option == selected, role = Role.RadioButton, onClick = { onSelect(option) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(option.label(), style = MaterialTheme.typography.bodyLarge)
                    option.hint()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
