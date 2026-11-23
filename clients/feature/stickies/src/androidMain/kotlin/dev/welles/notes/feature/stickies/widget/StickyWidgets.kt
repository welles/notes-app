package dev.welles.notes.feature.stickies.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.welles.notes.core.data.ContextRepository
import dev.welles.notes.core.data.LocalSetting
import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.sync.SyncObserver
import dev.welles.notes.core.ui.AppIntents
import dev.welles.notes.core.ui.NoteColors
import dev.welles.notes.feature.stickies.data.Sticky
import dev.welles.notes.feature.stickies.data.preview
import dev.welles.notes.feature.stickies.data.StickyRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.welles.notes.core.model.Contexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val NoteText = ColorProvider(NoteColors.text)

/** Background of a widget. Transparent: text directly on the wallpaper, in the colour that suits it (Glance has no text shadow). */
internal enum class WidgetBackground(val stored: String?) {
    Opaque(null), TransparentLight("light"), TransparentDark("dark");

    val transparent get() = this != Opaque

    companion object {
        fun of(stored: String?) = entries.firstOrNull { it.stored == stored && stored != null } ?: Opaque
    }
}

/** Text colours on the wallpaper (only used for transparent backgrounds). */
private fun WidgetBackground.onWallpaper() =
    if (this == WidgetBackground.TransparentDark) NoteText else ColorProvider(Color.White)

private fun WidgetBackground.onWallpaperVariant() =
    ColorProvider(if (this == WidgetBackground.TransparentDark) Color(0xCC202124) else Color(0xDDFFFFFF))

/** Per-widget settings – device-local (core_local_setting), never synchronized. */
internal object WidgetConfig {
    private const val PREFIX = "widget."

    /** Which post-it a single widget shows. */
    fun key(appWidgetId: Int) = "${PREFIX}sticky.$appWidgetId"

    /** [WidgetBackground.stored]; absent = opaque. */
    fun backgroundKey(appWidgetId: Int) = "${PREFIX}background.$appWidgetId"

    fun observeBackground(settings: LocalSettingsDao, appWidgetId: Int): Flow<WidgetBackground> =
        settings.observe(backgroundKey(appWidgetId)).map(WidgetBackground::of).distinctUntilChanged()

    suspend fun background(settings: LocalSettingsDao, appWidgetId: Int) = WidgetBackground.of(settings.get(backgroundKey(appWidgetId)))

    suspend fun setBackground(settings: LocalSettingsDao, appWidgetId: Int, background: WidgetBackground) {
        val stored = background.stored
        if (stored != null) settings.put(LocalSetting(backgroundKey(appWidgetId), stored)) else settings.remove(backgroundKey(appWidgetId))
    }

    /** Widgets removed from the home screen leave no settings behind. */
    suspend fun remove(settings: LocalSettingsDao, appWidgetId: Int) {
        settings.remove(key(appWidgetId))
        settings.remove(backgroundKey(appWidgetId))
    }

    /**
     * Removes settings of widgets that no longer exist: missed deletions, and settings restored from a backup of another
     * device, whose widget ids would otherwise apply to unrelated new widgets.
     */
    suspend fun removeOrphans(settings: LocalSettingsDao, existingIds: Set<Int>) {
        settings.withPrefix(PREFIX)
            .filter { it.key.substringAfterLast('.').toIntOrNull()?.let { id -> id !in existingIds } ?: false }
            .forEach { settings.remove(it.key) }
    }
}

private fun appIntent(context: Context, configure: Intent.() -> Unit): Intent =
    (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
        .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        .apply(configure)

/** "Stickies" principle: one post-it on the home screen in its colour. Tap opens it in the app. Scalable. */
class SingleStickyWidget : GlanceAppWidget(), KoinComponent {
    private val repository: StickyRepository by inject()
    private val contexts: ContextRepository by inject()
    private val settings: LocalSettingsDao by inject()

    override val sizeMode = SizeMode.Exact

    override suspend fun onDelete(context: Context, glanceId: GlanceId) =
        WidgetConfig.remove(settings, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))

    sealed interface State {
        data class Show(val sticky: Sticky) : State
        data object Deleted : State
        data class OtherContext(val sticky: Sticky) : State
        data object NotConfigured : State
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // Collected inside the Glance session, so the widget follows changes while the session is alive.
        val states = combine(settings.observe(WidgetConfig.key(appWidgetId)), contexts.activeContextId) { sid, active -> sid to (active ?: Contexts.DEFAULT_ID) }
            .flatMapLatest { (stickyId, active) ->
                if (stickyId == null) flowOf(State.NotConfigured)
                else repository.observe(stickyId, Unit).map { sticky ->
                    when {
                        sticky == null || sticky.deleted -> State.Deleted
                        sticky.contextId != active -> State.OtherContext(sticky)
                        else -> State.Show(sticky)
                    }
                }
            }
        val backgrounds = WidgetConfig.observeBackground(settings, appWidgetId)
        val initial = states.first()
        val initialBackground = backgrounds.first()
        provideContent {
            val state by states.collectAsState(initial)
            val background by backgrounds.collectAsState(initialBackground)
            GlanceTheme { Content(context, state, background) }
        }
    }

    @Composable
    private fun Content(context: Context, state: State, background: WidgetBackground) {
        when (state) {
            is State.Show -> {
                val s = state.sticky
                val open = actionStartActivity(appIntent(context) { putExtra(AppIntents.EXTRA_OPEN_STICKY, s.id) })
                if (background.transparent) {
                    // Without the paper, a narrow strip in the post-it's colour keeps it recognisable.
                    Row(GlanceModifier.fillMaxSize().padding(8.dp).clickable(open)) {
                        Box(GlanceModifier.width(4.dp).fillMaxHeight().cornerRadius(2.dp).background(NoteColors.accent(s.color))) {}
                        Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 8.dp)) { StickyText(s, background.onWallpaper()) }
                    }
                } else {
                    Column(GlanceModifier.fillMaxSize().cornerRadius(16.dp).background(NoteColors.background(s.color)).padding(12.dp).clickable(open)) {
                        StickyText(s, NoteText)
                    }
                }
            }
            State.Deleted -> Empty(context, "Gelöscht", background)
            is State.OtherContext -> Empty(context, "Anderer Kontext", background)
            State.NotConfigured -> Empty(context, "Kein Post-it", background)
        }
    }

    @Composable
    private fun StickyText(s: Sticky, color: ColorProvider) {
        val (heading, body) = s.preview()
        heading?.let { Text(it, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color), maxLines = 2) }
        if (body.isNotEmpty()) Text(body, style = TextStyle(fontSize = 14.sp, color = color))
    }

    @Composable
    private fun Empty(context: Context, title: String, background: WidgetBackground) {
        val frame = GlanceModifier.fillMaxSize()
        Column(
            (if (background.transparent) frame else frame.cornerRadius(16.dp).background(Color(0xFFE3E3E3))).padding(12.dp)
                .clickable(actionStartActivity(appIntent(context) {})),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = TextStyle(fontWeight = FontWeight.Medium, color = if (background.transparent) background.onWallpaper() else NoteText))
        }
    }
}

/** Overview: pinned post-its of the active context (scrollable) and a "+" for quick capture. */
class PinnedStickiesWidget : GlanceAppWidget(), KoinComponent {
    private val repository: StickyRepository by inject()
    private val contexts: ContextRepository by inject()
    private val settings: LocalSettingsDao by inject()

    override val sizeMode = SizeMode.Exact

    override suspend fun onDelete(context: Context, glanceId: GlanceId) =
        WidgetConfig.remove(settings, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = combine(contexts.contexts, contexts.activeContextId) { list, active ->
            list.firstOrNull { it.id == (active ?: Contexts.DEFAULT_ID) } ?: list.firstOrNull { it.isDefault }
        }.flatMapLatest { ctx ->
            if (ctx == null) flowOf("" to emptyList()) else repository.observePinned(ctx.id).map { ctx.name to it }
        }
        val backgrounds = WidgetConfig.observeBackground(settings, appWidgetId)
        val initial = data.first()
        val initialBackground = backgrounds.first()
        provideContent {
            val (name, pinned) = data.collectAsState(initial).value
            val background by backgrounds.collectAsState(initialBackground)
            GlanceTheme { Content(context, name, pinned, background) }
        }
    }

    @Composable
    private fun Content(context: Context, contextName: String, pinned: List<Sticky>, background: WidgetBackground) {
        // Transparent: only the frame disappears, the post-its keep their paper colour.
        val frame = GlanceModifier.fillMaxSize()
        val transparent = background.transparent
        val onFrame = if (transparent) background.onWallpaper() else GlanceTheme.colors.onSurface
        val onFrameVariant = if (transparent) background.onWallpaperVariant() else GlanceTheme.colors.onSurfaceVariant
        Column((if (transparent) frame else frame.cornerRadius(20.dp).background(GlanceTheme.colors.widgetBackground)).padding(8.dp)) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(appIntent(context) {}))) {
                    Text("Angepinnt", style = TextStyle(fontWeight = FontWeight.Bold, color = onFrame))
                    Text(contextName, style = TextStyle(fontSize = 12.sp, color = onFrameVariant))
                }
                Box(
                    GlanceModifier.size(40.dp).cornerRadius(20.dp).background(GlanceTheme.colors.primary)
                        .clickable(actionStartActivity(appIntent(context) { putExtra(AppIntents.EXTRA_CAPTURE, true) })),
                    contentAlignment = Alignment.Center,
                ) { Text("+", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary)) }
            }
            if (pinned.isEmpty()) {
                Text("—", modifier = GlanceModifier.padding(4.dp), style = TextStyle(color = onFrameVariant))
            } else {
                LazyColumn {
                    items(pinned, itemId = { it.id.hashCode().toLong() }) { s ->
                        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Column(
                                GlanceModifier.fillMaxWidth().cornerRadius(12.dp).background(NoteColors.background(s.color)).padding(10.dp)
                                    .clickable(actionStartActivity(appIntent(context) { putExtra(AppIntents.EXTRA_OPEN_STICKY, s.id) })),
                            ) {
                                val (heading, body) = s.preview()
                                heading?.let { Text(it, style = TextStyle(fontWeight = FontWeight.Bold, color = NoteText), maxLines = 1) }
                                if (body.isNotEmpty()) Text(body, style = TextStyle(fontSize = 13.sp, color = NoteText), maxLines = 4)
                            }
                            Spacer(GlanceModifier.height(0.dp))
                        }
                    }
                }
            }
        }
    }
}

class SingleStickyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleStickyWidget()
}

class PinnedStickiesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PinnedStickiesWidget()
}

/** Refreshes all post-it widgets (after local changes, context switches and every sync). */
class StickyWidgetUpdater(private val context: Context) : SyncObserver {
    /** See [WidgetConfig.removeOrphans]; skipped if the widget ids cannot be determined. */
    suspend fun removeOrphanedSettings(settings: LocalSettingsDao) {
        val ids = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            listOf(SingleStickyWidgetReceiver::class.java, PinnedStickiesWidgetReceiver::class.java)
                .flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }.toSet()
        }.getOrNull() ?: return
        WidgetConfig.removeOrphans(settings, ids)
    }

    suspend fun updateAll() {
        runCatching { SingleStickyWidget().updateAll(context) }
        runCatching { PinnedStickiesWidget().updateAll(context) }
    }

    /** Called inside the sync (also from the background worker), so widgets are fresh before the process may die. */
    override suspend fun onSynced(result: dev.welles.notes.core.sync.SyncResult) = updateAll()
}

internal suspend fun saveWidgetSticky(settings: LocalSettingsDao, appWidgetId: Int, stickyId: String) =
    settings.put(LocalSetting(WidgetConfig.key(appWidgetId), stickyId))
