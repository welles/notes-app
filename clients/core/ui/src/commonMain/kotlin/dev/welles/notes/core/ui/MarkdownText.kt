package dev.welles.notes.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import dev.welles.notes.core.model.Markdown

/** Styles of the basic Markdown of notes (see [Markdown]), relative to the surrounding text. */
object MarkdownStyles {
    fun heading(level: Int) = SpanStyle(
        fontWeight = FontWeight.Bold,
        fontSize = when (level) { 1 -> 1.25.em; 2 -> 1.1.em; else -> 1.em },
    )
    val strong = SpanStyle(fontWeight = FontWeight.Bold)
    val emphasis = SpanStyle(fontStyle = FontStyle.Italic)
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = .07f), fontSize = .92.em)
    val link = SpanStyle(textDecoration = TextDecoration.Underline)
    val done = SpanStyle(textDecoration = TextDecoration.LineThrough, color = NoteColors.text.copy(alpha = .6f))
    val marker = SpanStyle(color = NoteColors.text.copy(alpha = .7f))

    /** Markup characters in the editor: still visible, but faded. */
    val syntax = SpanStyle(color = NoteColors.text.copy(alpha = .4f))

    fun of(style: Markdown.Style) = when (style) {
        Markdown.Style.Strong -> strong
        Markdown.Style.Emphasis -> emphasis
        Markdown.Style.Strike -> strike
    }
}

/** Rendered Markdown (markup removed, links clickable) for display, e.g. on post-it cards. */
fun markdownAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    Markdown.parse(text).forEachIndexed { i, block ->
        if (i > 0) append('\n')
        when (block) {
            is Markdown.Heading -> withStyle(MarkdownStyles.heading(block.level)) { inlines(block.inlines) }
            is Markdown.ListItem -> {
                append("    ".repeat(block.indent))
                withStyle(MarkdownStyles.marker) {
                    append(when (block.checked) { null -> block.marker; true -> "☑"; false -> "☐" })
                }
                append(' ')
                if (block.checked == true) withStyle(MarkdownStyles.done) { inlines(block.inlines) } else inlines(block.inlines)
            }
            is Markdown.Rule -> withStyle(MarkdownStyles.syntax) { append("────────────") }
            is Markdown.Line -> inlines(block.inlines)
        }
    }
}

private fun AnnotatedString.Builder.inlines(nodes: List<Markdown.Inline>) {
    for (n in nodes) when (n) {
        is Markdown.Text -> append(n.text)
        is Markdown.Code -> withStyle(MarkdownStyles.code) { append(n.text) }
        is Markdown.Styled -> withStyle(MarkdownStyles.of(n.style)) { inlines(n.children) }
        is Markdown.Link -> withLink(LinkAnnotation.Url(n.url, TextLinkStyles(MarkdownStyles.link))) { inlines(n.children) }
    }
}

/**
 * Styles Markdown in place while typing: the text stays exactly as typed (identity offsets),
 * headings get bigger, emphasis is applied and markup characters are faded.
 */
object MarkdownHighlight : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text), OffsetMapping.Identity)

    fun highlight(source: String): AnnotatedString = buildAnnotatedString {
        append(source)
        for (block in Markdown.parse(source)) when (block) {
            is Markdown.Heading -> {
                addStyle(MarkdownStyles.heading(block.level), block.start, block.end)
                addStyle(MarkdownStyles.syntax, block.start, block.contentStart)
                inlines(block.inlines)
            }
            is Markdown.ListItem -> {
                addStyle(MarkdownStyles.marker, block.start, block.contentStart)
                if (block.checked == true) addStyle(MarkdownStyles.done, block.contentStart, block.end)
                inlines(block.inlines)
            }
            is Markdown.Rule -> addStyle(MarkdownStyles.syntax, block.start, block.end)
            is Markdown.Line -> inlines(block.inlines)
        }
    }

    private fun AnnotatedString.Builder.inlines(nodes: List<Markdown.Inline>) {
        for (n in nodes) when (n) {
            is Markdown.Text -> Unit
            is Markdown.Code -> {
                addStyle(MarkdownStyles.code, n.start, n.end)
                addStyle(MarkdownStyles.syntax, n.start, n.start + 1)
                addStyle(MarkdownStyles.syntax, n.end - 1, n.end)
            }
            is Markdown.Styled -> {
                addStyle(MarkdownStyles.of(n.style), n.contentStart, n.contentEnd)
                addStyle(MarkdownStyles.syntax, n.start, n.contentStart)
                addStyle(MarkdownStyles.syntax, n.contentEnd, n.end)
                inlines(n.children)
            }
            is Markdown.Link -> {
                addStyle(MarkdownStyles.link, n.contentStart, n.contentEnd)
                if (n.contentStart != n.start) {
                    addStyle(MarkdownStyles.syntax, n.start, n.contentStart)
                    addStyle(MarkdownStyles.syntax, n.contentEnd, n.end)
                }
                inlines(n.children)
            }
        }
    }
}
