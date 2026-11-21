package dev.welles.notes.core.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MarkdownTest {
    /** Same cases as the server's MarkdownTests – both parsers must agree. */
    @Test
    fun shared_cases_match_the_server() {
        val cases = NotesJson.parseToJsonElement(
            File(System.getProperty("notes.repoRoot") ?: "../..", "api/markdown-cases.json").readText(),
        ).jsonObject["cases"]!!.jsonArray
        for (c in cases) {
            val md = c.jsonObject["markdown"]!!.jsonPrimitive.content
            assertEquals(c.jsonObject["plain"]!!.jsonPrimitive.content, Markdown.toPlainText(md), "input: $md")
        }
    }

    @Test
    fun nodes_keep_their_source_ranges() {
        val text = "# Titel\nein **fettes** [Wort](https://x.org)"
        val (heading, line) = Markdown.parse(text)
        assertIs<Markdown.Heading>(heading)
        assertEquals(2, heading.contentStart)
        assertEquals("Titel", text.substring(heading.contentStart, heading.end))

        assertIs<Markdown.Line>(line)
        val bold = line.inlines[1]
        assertIs<Markdown.Styled>(bold)
        assertEquals("**fettes**", text.substring(bold.start, bold.end))
        assertEquals("fettes", text.substring(bold.contentStart, bold.contentEnd))
        val link = line.inlines[3]
        assertIs<Markdown.Link>(link)
        assertEquals("https://x.org", link.url)
        assertEquals("Wort", text.substring(link.contentStart, link.contentEnd))
        assertEquals(text.length, link.end)
    }

    @Test
    fun tasks_know_whether_they_are_done() {
        val items = Markdown.parse("- [ ] a\n- [x] b").map { it as Markdown.ListItem }
        assertEquals(listOf(false, true), items.map { it.checked })
    }
}
