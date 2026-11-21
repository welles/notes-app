package dev.welles.notes.feature.stickies

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StickyNote2
import dev.welles.notes.core.data.CaptureTarget
import dev.welles.notes.core.data.LocalEntityHandler
import dev.welles.notes.core.ui.ClientFeature
import dev.welles.notes.core.ui.FeatureRoutes
import dev.welles.notes.feature.stickies.data.StickyCaptureTarget
import dev.welles.notes.feature.stickies.data.StickyHandler
import dev.welles.notes.feature.stickies.data.StickyRepository
import dev.welles.notes.feature.stickies.ui.ROUTE_EDIT
import dev.welles.notes.feature.stickies.ui.StickiesScreen
import dev.welles.notes.feature.stickies.ui.StickiesViewModel
import dev.welles.notes.feature.stickies.ui.EditRequest
import dev.welles.notes.feature.stickies.ui.StickyEditorViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

object StickiesFeature : ClientFeature {
    override val key = "stickies"
    override val title = "Post-its"
    override val icon = Icons.Outlined.StickyNote2
    override val route = "stickies"
    override val createRoute = ROUTE_EDIT

    override fun routes(builder: FeatureRoutes) {
        builder.screen(route) { _, nav -> StickiesScreen(nav) }
        builder.screen(ROUTE_EDIT) { args, nav -> StickiesScreen(nav, routeEdit = EditRequest(args["id"])) }
    }
}

/**
 * Everything the Post-its module registers – the single place it plugs into the app.
 * Needs a [dev.welles.notes.feature.stickies.data.StickyDao] from the app's database.
 */
val stickiesModule = module {
    single { StickyHandler(get()) } bind LocalEntityHandler::class
    single { StickyRepository(get(), get()) }
    single { StickyCaptureTarget(get()) } bind CaptureTarget::class
    single<ClientFeature> { StickiesFeature }
    viewModelOf(::StickiesViewModel)
    viewModelOf(::StickyEditorViewModel)
}
