package dev.welles.notes.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf

/** Minimal back stack of route strings like `stickies/edit?id=…`. */
class NavStack(start: String) : Navigator {
    val entries = mutableStateListOf(start)
    val current: String get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1

    override fun navigate(route: String) {
        // e.g. a top bar icon leading to settings while settings is shown
        if (route == current) return
        entries.add(route)
    }

    override fun back() {
        if (entries.size > 1) entries.removeAt(entries.lastIndex)
    }

    fun resetTo(route: String) {
        entries.clear()
        entries.add(route)
    }
}

fun routePath(route: String): String = route.substringBefore('?')

fun routeArgs(route: String): Map<String, String> =
    route.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

internal class RouteTable : FeatureRoutes {
    val screens = mutableMapOf<String, @Composable (Map<String, String>, Navigator) -> Unit>()
    override fun screen(route: String, content: @Composable (args: Map<String, String>, navigator: Navigator) -> Unit) {
        screens[route] = content
    }
}
