package dev.welles.notes.core.model

/**
 * The basic Markdown of notes – the same subset as the server's `Notes.Core/Web/Markdown.cs`.
 *
 * Blocks are single lines (line breaks are kept as written, like in a plaintext note):
 * headings `#`–`######`, list items `-`/`*`/`+`, numbered items `1.`/`1)`, tasks `- [ ]`/`- [x]`,
 * rules `---`, everything else is a paragraph line. Inline: `**bold**`, `*italic*`/`_italic_`,
 * `~~strike~~`, `` `code` ``, `[text](https://…)`, bare `http(s)://` links and `\`-escapes.
 *
 * Every node keeps its range in the source, so an editor can style the raw text in place.
 */
object Markdown {
    sealed interface Block {
        val start: Int
        val end: Int
    }

    /** Level 1–3 (deeper headings are shown as level 3). */
    data class Heading(val level: Int, override val start: Int, override val end: Int, val contentStart: Int, val inlines: List<Inline>) : Block

    /** [marker] is `•`-like for bullets, the number as written (`3.`) for numbered items. [checked] is set for tasks. */
    data class ListItem(
        val indent: Int,
        val ordered: Boolean,
        val marker: String,
        val checked: Boolean?,
        override val start: Int,
        override val end: Int,
        val contentStart: Int,
        val inlines: List<Inline>,
    ) : Block

    data class Rule(override val start: Int, override val end: Int) : Block

    /** A plain line (possibly empty). */
    data class Line(override val start: Int, override val end: Int, val inlines: List<Inline>) : Block

    sealed interface Inline {
        val start: Int
        val end: Int
    }

    data class Text(val text: String, override val start: Int, override val end: Int) : Inline

    enum class Style { Strong, Emphasis, Strike }

    data class Styled(
        val style: Style,
        val children: List<Inline>,
        override val start: Int,
        override val end: Int,
        val contentStart: Int,
        val contentEnd: Int,
    ) : Inline

    data class Code(val text: String, override val start: Int, override val end: Int) : Inline

    /** For bare links, the content range is the whole link. */
    data class Link(
        val url: String,
        val children: List<Inline>,
        override val start: Int,
        override val end: Int,
        val contentStart: Int,
        val contentEnd: Int,
    ) : Inline

    private val heading = Regex("""^(#{1,6})[ \t]+""")
    private val task = Regex("""^([ \t]*)[-*+][ \t]+\[([ xX])][ \t]+""")
    private val bullet = Regex("""^([ \t]*)[-*+][ \t]+""")
    private val ordered = Regex("""^([ \t]*)(\d{1,9}[.)])[ \t]+""")
    private val rule = Regex("""^[ \t]*(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var lineStart = 0
        while (true) {
            val nl = text.indexOf('\n', lineStart)
            var lineEnd = if (nl < 0) text.length else nl
            if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
            blocks += parseLine(text, lineStart, lineEnd)
            if (nl < 0) break
            lineStart = nl + 1
        }
        return blocks
    }

    private fun parseLine(text: String, start: Int, end: Int): Block {
        val line = text.substring(start, end)
        heading.find(line)?.let { m ->
            val c = start + m.value.length
            return Heading(minOf(m.groupValues[1].length, 3), start, end, c, parseInline(text, c, end))
        }
        if (rule.matches(line)) return Rule(start, end)
        task.find(line)?.let { m ->
            val c = start + m.value.length
            return ListItem(indentOf(m.groupValues[1]), false, "", m.groupValues[2] != " ", start, end, c, parseInline(text, c, end))
        }
        bullet.find(line)?.let { m ->
            val c = start + m.value.length
            return ListItem(indentOf(m.groupValues[1]), false, "•", null, start, end, c, parseInline(text, c, end))
        }
        ordered.find(line)?.let { m ->
            val c = start + m.value.length
            return ListItem(indentOf(m.groupValues[1]), true, m.groupValues[2], null, start, end, c, parseInline(text, c, end))
        }
        return Line(start, end, parseInline(text, start, end))
    }

    private fun indentOf(ws: String): Int = ws.sumOf { if (it == '\t') 4 else 1 }.let { minOf(it / 2, 6) }

    /** Parses `text[from, to)`; all ranges are absolute positions in [text]. */
    fun parseInline(text: String, from: Int, to: Int): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        var plainStart = from
        var i = from

        fun flush(at: Int) {
            if (plain.isNotEmpty()) out += Text(plain.toString(), plainStart, at)
            plain.clear()
        }

        fun emit(node: Inline) {
            flush(node.start)
            out += node
            i = node.end
            plainStart = i
        }

        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to && text[i + 1] in ESCAPABLE) {
                if (plain.isEmpty()) plainStart = i
                plain.append(text[i + 1])
                i += 2
                continue
            }
            val node = when (c) {
                '`' -> code(text, i, to)
                '*' -> if (text.startsWith("**", i)) styled(text, i, to, "**", Style.Strong) else styled(text, i, to, "*", Style.Emphasis)
                '_' -> if (text.startsWith("__", i)) styled(text, i, to, "__", Style.Strong) else styled(text, i, to, "_", Style.Emphasis)
                '~' -> if (text.startsWith("~~", i)) styled(text, i, to, "~~", Style.Strike) else null
                '[' -> link(text, i, to)
                'h' -> autolink(text, i, to)
                else -> null
            }
            if (node != null) {
                emit(node)
                continue
            }
            if (plain.isEmpty()) plainStart = i
            plain.append(c)
            i++
        }
        flush(to)
        return out
    }

    private const val ESCAPABLE = "\\`*_~[]()#+-.!>"

    private fun code(text: String, i: Int, to: Int): Inline? {
        val close = text.indexOf('`', i + 1)
        if (close < 0 || close >= to || close == i + 1) return null
        return Code(text.substring(i + 1, close), i, close + 1)
    }

    private fun styled(text: String, i: Int, to: Int, delim: String, style: Style): Inline? {
        val n = delim.length
        val cs = i + n
        if (cs >= to || text[cs].isWhitespace()) return null
        val wordDelim = delim[0] == '_'
        if (wordDelim && i > 0 && text[i - 1].isLetterOrDigit()) return null
        var j = cs + 1
        while (j + n <= to) {
            if (text.startsWith(delim, j) && !text[j - 1].isWhitespace() &&
                // a single delimiter must not be half of a double one
                (n == 2 || ((j + 1 >= to || text[j + 1] != delim[0]) && text[j - 1] != delim[0])) &&
                (!wordDelim || j + n >= to || !text[j + n].isLetterOrDigit())
            ) {
                return Styled(style, parseInline(text, cs, j), i, j + n, cs, j)
            }
            j++
        }
        return null
    }

    private fun link(text: String, i: Int, to: Int): Inline? {
        val mid = text.indexOf("](", i + 1)
        if (mid < 0 || mid + 2 >= to || mid == i + 1) return null
        val close = text.indexOf(')', mid + 2)
        if (close < 0 || close >= to) return null
        val url = text.substring(mid + 2, close).trim()
        if (!isSafeUrl(url) || url.any { it.isWhitespace() }) return null
        return Link(url, parseInline(text, i + 1, mid), i, close + 1, i + 1, mid)
    }

    private fun autolink(text: String, i: Int, to: Int): Inline? {
        if (!text.startsWith("http://", i) && !text.startsWith("https://", i)) return null
        if (i > 0 && text[i - 1].isLetterOrDigit()) return null
        var j = i
        while (j < to && !text[j].isWhitespace()) j++
        while (j > i && text[j - 1] in TRAILING) j--
        val url = text.substring(i, j)
        if (url.length <= url.indexOf("//") + 2) return null
        return Link(url, listOf(Text(url, i, j)), i, j, i, j)
    }

    private const val TRAILING = ".,;:!?)\"'"

    fun isSafeUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")

    /** Text without markup, e.g. for widgets and window titles. */
    fun toPlainText(text: String): String = parse(text).joinToString("\n") { b ->
        when (b) {
            is Heading -> plain(b.inlines)
            is ListItem -> "  ".repeat(b.indent) + when (b.checked) { null -> b.marker; true -> "☑"; false -> "☐" } + " " + plain(b.inlines)
            is Rule -> "—"
            is Line -> plain(b.inlines)
        }
    }

    fun plain(inlines: List<Inline>): String = buildString {
        for (n in inlines) when (n) {
            is Text -> append(n.text)
            is Code -> append(n.text)
            is Styled -> append(plain(n.children))
            is Link -> append(plain(n.children))
        }
    }
}
