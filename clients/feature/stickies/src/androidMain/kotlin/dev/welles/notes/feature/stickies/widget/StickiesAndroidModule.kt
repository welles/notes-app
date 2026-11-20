package dev.welles.notes.feature.stickies.widget

import dev.welles.notes.core.data.LocalSettingsDao
import dev.welles.notes.core.sync.SyncObserver
import dev.welles.notes.feature.stickies.data.StickyDao
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

/** Android-only part of the Post-its module: home-screen widgets. */
val stickiesAndroidModule = module {
    single(createdAtStart = true) { StickyWidgetUpdater(androidContext()).also { it.watch(get(), get()) } } bind SyncObserver::class
}

/** Local changes (own edits, sync results, context switch) → refresh widgets, throttled. */
@OptIn(FlowPreview::class)
private fun StickyWidgetUpdater.watch(dao: StickyDao, settings: LocalSettingsDao) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        combine(dao.observeChangeMarker(), settings.observe("activeContextId")) { a, b -> a to b }
            .drop(1)
            .debounce(500.milliseconds)
            .collect { updateAll() }
    }
}
