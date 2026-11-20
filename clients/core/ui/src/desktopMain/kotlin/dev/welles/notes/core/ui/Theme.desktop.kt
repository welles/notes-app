package dev.welles.notes.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme? = null

/**
 * Compose cannot read the theme of Linux desktops itself. Detect it from the usual hints:
 * GTK_THEME (…:dark), the freedesktop color-scheme via gsettings, or KDE's colour scheme name.
 */
@Composable
actual fun systemDarkMode(): Boolean {
    val composeGuess = isSystemInDarkTheme()
    return remember { LinuxDarkMode.detect() } ?: composeGuess
}

internal object LinuxDarkMode {
    fun detect(): Boolean? {
        System.getenv("NOTES_DARK")?.let { return it == "1" || it.equals("true", true) }
        System.getenv("GTK_THEME")?.let { if (it.contains("dark", true)) return true }
        runCatching {
            val p = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0) return out.contains("dark", true)
        }
        runCatching {
            val p = ProcessBuilder("gsettings", "get", "org.cinnamon.desktop.interface", "gtk-theme").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0 && out.isNotBlank()) return out.contains("dark", true)
        }
        runCatching {
            val home = System.getProperty("user.home")
            val kdeglobals = java.io.File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "kdeglobals")
            if (kdeglobals.exists()) {
                val scheme = kdeglobals.readLines().firstOrNull { it.startsWith("ColorScheme=") }
                if (scheme != null) return scheme.contains("dark", true)
            }
        }
        return null
    }
}
