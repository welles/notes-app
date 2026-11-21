package dev.welles.notes.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A client module's single registration point in the shell (navigation entry + screen). Registered via Koin.
 * The shell shows a navigation bar only once there is more than one feature.
 */
interface ClientFeature {
    val key: String
    val title: String
    val icon: ImageVector

    /** Start route of the module; it may register further routes in [routes]. */
    val route: String

    /**
     * Route of the module's own "new" screen. If set, the "+" button (and quick capture from outside, when the
     * module is the default capture target) opens it instead of the generic capture dialog.
     */
    val createRoute: String? get() = null

    fun routes(builder: FeatureRoutes)
}

/** Lets a feature add its screens to the shell's navigation graph without knowing navigation-compose. */
interface FeatureRoutes {
    fun screen(route: String, content: @Composable (args: Map<String, String>, navigator: Navigator) -> Unit)
}

interface Navigator {
    fun navigate(route: String)
    fun back()
}

/** A block on the settings screen. Core and modules contribute sections via Koin (e.g. sync connection). */
interface SettingsSection {
    val order: Int
    @Composable
    fun Content(navigator: Navigator)
}

/** Snackbar host of the shell (e.g. "Post-it gelöscht – Rückgängig"). */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar host") }

/** Intent extras understood by the app's main entry (used by Android widgets and shortcuts). */
object AppIntents {
    const val EXTRA_CAPTURE = "dev.welles.notes.CAPTURE"
    const val EXTRA_OPEN_STICKY = "dev.welles.notes.OPEN_STICKY"
}
