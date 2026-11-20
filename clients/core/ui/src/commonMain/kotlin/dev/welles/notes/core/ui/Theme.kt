package dev.welles.notes.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Material You on Android 12+ (dynamic colours), otherwise null → Notes' own scheme. */
@Composable
expect fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme?

/** Whether the system uses dark mode (desktop: follows the desktop environment where detectable). */
@Composable
expect fun systemDarkMode(): Boolean

private val Light = lightColorScheme(primary = Color(0xFF4F5BD5), secondary = Color(0xFF6B6F80))
private val Dark = darkColorScheme(primary = Color(0xFF8C96FF), secondary = Color(0xFFB8BBCC))

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val dark = systemDarkMode()
    MaterialTheme(colorScheme = dynamicColorSchemeOrNull(dark) ?: if (dark) Dark else Light, content = content)
}

/** Post-it colours: pastel backgrounds with always-dark text, so they stay recognisable in dark mode too. */
object NoteColors {
    private val colors = mapOf(
        "yellow" to (Color(0xFFFFF3A3) to Color(0xFFE6C300)),
        "green" to (Color(0xFFCCF0B3) to Color(0xFF43A047)),
        "blue" to (Color(0xFFC7DEFA) to Color(0xFF1E88E5)),
        "pink" to (Color(0xFFFBD0E3) to Color(0xFFD81B60)),
        "purple" to (Color(0xFFDDCDF7) to Color(0xFF7E57C2)),
        "orange" to (Color(0xFFFDD5A8) to Color(0xFFF57C00)),
        "gray" to (Color(0xFFE3E3E3) to Color(0xFF757575)),
    )
    private val labels = mapOf(
        "yellow" to "Gelb", "green" to "Grün", "blue" to "Blau", "pink" to "Pink", "purple" to "Lila", "orange" to "Orange", "gray" to "Grau",
    )
    val names: List<String> = colors.keys.toList()
    val text = Color(0xFF202124)

    /** Unknown colour names (newer versions) are displayed as yellow but never changed. */
    fun background(name: String?): Color = colors[name]?.first ?: colors.getValue("yellow").first
    fun accent(name: String?): Color = colors[name]?.second ?: colors.getValue("gray").second
    fun label(name: String): String = labels[name] ?: name
}
