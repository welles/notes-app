package dev.welles.notes.feature.stickies.data

import dev.welles.notes.core.model.Markdown

/**
 * Post-its have no title of their own any more, only Markdown text (same rules as the server's `StickyText`).
 * The `title` field stays in the protocol for older versions: where one is set, it is shown as a heading
 * above the text, and the next edit moves it into the text for good.
 */
object StickyText {
    /** The text as shown and edited: a legacy title becomes a level-1 heading. */
    fun combine(title: String?, content: String): String = when {
        title.isNullOrBlank() -> content
        content.isEmpty() -> "# ${title.trim()}"
        else -> "# ${title.trim()}\n\n$content"
    }

    /** Splits a leading level-1 heading off the text (for "convert to …"); null title if there is none. */
    fun splitHeading(text: String): Pair<String?, String> {
        val end = text.indexOf('\n')
        val first = if (end < 0) text else text.substring(0, end)
        if (!first.startsWith("# ") || first.substring(2).isBlank()) return null to text
        val rest = if (end < 0) "" else text.substring(end + 1).trimStart('\n', '\r')
        return first.substring(2).trim() to rest
    }
}

/**
 * For places that can only show plain strings (Android widgets): a leading heading on its own
 * (shown bold), the rest as plain text without markup.
 */
fun Sticky.preview(): Pair<String?, String> {
    val blocks = Markdown.parse(text)
    val first = blocks.indexOfFirst { !(it is Markdown.Line && it.inlines.isEmpty()) }
    val heading = blocks.getOrNull(first) as? Markdown.Heading ?: return null to Markdown.toPlainText(text).trim('\n')
    val rest = text.substring(minOf(heading.end + 1, text.length))
    return Markdown.plain(heading.inlines) to Markdown.toPlainText(rest).trim('\n')
}
