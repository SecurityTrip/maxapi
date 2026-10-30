package ru.securitytrip.maxapi

import ru.securitytrip.maxapi.client.MaxLimits
import ru.securitytrip.maxapi.client.TextSplitter
import ru.securitytrip.maxapi.model.markup.TextFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextSplitterTest {
    /** Splits and checks what every split must hold: parts fit, are not blank, and keep characters whole. */
    private fun split(text: String, limit: Int, format: TextFormat? = null): List<String> {
        val parts = TextSplitter.split(text, format, limit)
        for (part in parts) {
            assertTrue(part.length <= limit, "Part of ${part.length} > $limit: $part")
            assertTrue(part.isNotBlank(), "Blank part in $parts")
            assertTrue(!part.first().isLowSurrogate() && !part.last().isHighSurrogate(), "Broken surrogate pair: $part")
        }
        return parts
    }

    private fun words(count: Int, word: String = "word"): String = List(count) { word }.joinToString(" ")

    // ------------------------------------------------------------ plain text

    @Test
    fun `text that fits is returned unchanged`() {
        val text = "a".repeat(MaxLimits.TEXT_LENGTH)
        assertEquals(listOf(text), TextSplitter.split(text))
        assertEquals(listOf("  **x"), TextSplitter.split("  **x", TextFormat.MARKDOWN))
        assertEquals(listOf(""), TextSplitter.split(""))
        assertEquals(2, TextSplitter.split(text + "b").size)
    }

    @Test
    fun `the limit must leave room for markup`() {
        assertFailsWith<IllegalArgumentException> { TextSplitter.split("text", null, TextSplitter.MIN_LIMIT - 1) }
        assertEquals(listOf("a".repeat(16), "a"), TextSplitter.split("a".repeat(17), null, TextSplitter.MIN_LIMIT))
    }

    @Test
    fun `paragraphs come first`() {
        val text = "x".repeat(25) + "\n\n" + "y".repeat(10) + "\n" + "z".repeat(10)
        assertEquals(listOf("x".repeat(25), "y".repeat(10) + "\n" + "z".repeat(10)), split(text, 40))
    }

    @Test
    fun `lines come next when the paragraph would leave the part less than half full`() {
        val text = "x".repeat(5) + "\n\n" + "y".repeat(20) + "\n" + "z".repeat(20)
        assertEquals(listOf("x".repeat(5) + "\n\n" + "y".repeat(20), "z".repeat(20)), split(text, 40))
    }

    @Test
    fun `words come last`() {
        val text = "aaaa\n" + words(10)
        assertEquals(listOf("aaaa\n" + words(7), words(3)), split(text, 40))
    }

    @Test
    fun `an early break beats cutting a word`() {
        assertEquals(listOf("ab", "cd" + "x".repeat(38), "x".repeat(12)), split("ab cd" + "x".repeat(50), 40))
    }

    @Test
    fun `long words are cut where they must`() {
        assertEquals(listOf("x".repeat(40), "x".repeat(40), "x".repeat(20)), split("x".repeat(100), 40))
    }

    @Test
    fun `whitespace at cuts is dropped, leading whitespace of the text is kept`() {
        val parts = split("  " + "a".repeat(30) + " \t \n\n   " + "b".repeat(30) + "   ", 40)
        assertEquals(listOf("  " + "a".repeat(30), "b".repeat(30) + "   "), parts)
        assertEquals(listOf("a".repeat(30)), split("a".repeat(30) + " ".repeat(30), 40))
    }

    @Test
    fun `windows line breaks are breaks too`() {
        val text = "x".repeat(25) + "\r\n \r\n" + "y".repeat(10) + "\r\n" + "z".repeat(20)
        assertEquals(listOf("x".repeat(25), "y".repeat(10) + "\r\n" + "z".repeat(20)), split(text, 40))
        assertEquals(listOf("x".repeat(30), "y".repeat(30)), split("x".repeat(30) + "\r\n" + "y".repeat(30), 40))
        // A lone \r is whitespace, not a line break.
        assertEquals(listOf("x".repeat(30) + " " + "\ry".repeat(4), "y" + "\ry".repeat(10)), split("x".repeat(30) + " " + "\ry".repeat(15), 40))
    }

    // ------------------------------------------------------------ characters

    @Test
    fun `emoji and other surrogate pairs stay whole`() {
        val parts = split("😀".repeat(30), 41)
        assertEquals(listOf("😀".repeat(20), "😀".repeat(10)), parts)
    }

    @Test
    fun `joined emoji, marks and selectors stay whole`() {
        val family = "👨‍👩‍👧"
        assertEquals(8, family.length)
        assertEquals(List(5) { family.repeat(2) }, split(family.repeat(10), 20))
        assertEquals(listOf("é".repeat(20), "é".repeat(10)), split("é".repeat(30), 41))
        assertEquals(List(5) { "👍🏽".repeat(4) }, split("👍🏽".repeat(20), 18))
        assertEquals(listOf("❤️".repeat(20), "❤️".repeat(10)), split("❤️".repeat(30), 41))
    }

    // -------------------------------------------------------------- markdown

    @Test
    fun `markdown spans are closed and opened again`() {
        val text = "**" + words(20) + "**"
        val parts = split(text, 40, TextFormat.MARKDOWN)
        assertTrue(parts.size > 2)
        assertTrue(parts.all { it.startsWith("**") && it.endsWith("**") && !it.startsWith("** ") && !it.endsWith(" **") }, "$parts")
        assertEquals(words(20), parts.joinToString(" ") { it.removeSurrounding("**") })
    }

    @Test
    fun `nested spans are closed in reverse order`() {
        val text = "**bold _" + words(10) + "_ end**"
        val parts = split(text, 30, TextFormat.MARKDOWN)
        assertEquals("**bold _word word word word_**", parts[0])
        assertTrue(parts[1].startsWith("**_word"), parts[1])
        assertTrue(parts.last().endsWith("_ end**"), parts.last())
    }

    @Test
    fun `every delimiter of Max is known`() {
        for (delimiter in listOf("__", "~~", "++", "^^", "*", "_")) {
            val parts = split(delimiter + words(12) + delimiter, 30, TextFormat.MARKDOWN)
            assertTrue(parts.all { it.startsWith(delimiter) && it.endsWith(delimiter) }, "$delimiter: $parts")
        }
    }

    @Test
    fun `stray delimiters are plain text`() {
        val stray = words(5, "5 * 3") + " a_b c_d C++ x**"
        val parts = split(stray + " " + words(8), 40, TextFormat.MARKDOWN)
        assertEquals(stray + " " + words(8), parts.joinToString(" "))
    }

    @Test
    fun `emphasis does not cross paragraphs`() {
        val text = "a*b " + words(6) + "\n\n" + words(6) + " c*d"
        val parts = split(text, 40, TextFormat.MARKDOWN)
        assertEquals(listOf("a*b " + words(6), words(6) + " c*d"), parts)
    }

    @Test
    fun `code spans and blocks are closed and opened again`() {
        val inline = split("`" + words(12) + "`", 30, TextFormat.MARKDOWN)
        assertTrue(inline.all { it.startsWith("`") && it.endsWith("`") }, "$inline")
        val block = split("```" + words(12) + "```", 30, TextFormat.MARKDOWN)
        assertTrue(block.all { it.startsWith("```") && it.endsWith("```") }, "$block")
        // Nothing inside code is markup.
        val code = split("`" + words(12, "**a") + "`", 30, TextFormat.MARKDOWN)
        assertTrue(code.none { it.contains("**`") }, "$code")
    }

    @Test
    fun `unclosed code and code across paragraphs are plain text`() {
        val text = "```" + words(6) + " `" + words(6) + "\n\n" + words(3) + "` [x " + words(3)
        assertEquals(text.replace("\n\n", " ").split(" ").filter { it.isNotEmpty() }.joinToString(" "),
            split(text, 30, TextFormat.MARKDOWN).joinToString(" ").replace("\n\n", " "))
    }

    @Test
    fun `links are never cut`() {
        val link = "[a link to the docs](https://dev.max.ru/docs)"
        val parts = split(words(5) + " " + link + " " + words(5), 60, TextFormat.MARKDOWN)
        assertTrue(parts.any { it.contains(link) }, "$parts")
        // A link longer than a message has to be cut.
        val long = "[" + "😀".repeat(30) + "](u)"
        assertEquals(39, split(long, 40, TextFormat.MARKDOWN)[0].length)
        // Brackets without a link are plain text.
        val plain = split("[a] " + words(8) + " [b](c " + words(4), 30, TextFormat.MARKDOWN)
        assertEquals("[a] " + words(8) + " [b](c " + words(4), plain.joinToString(" "))
        // A link does not cross paragraphs.
        assertEquals("[x](\n\n" + words(5), split("[x](\n\n" + words(10) + ")", 30, TextFormat.MARKDOWN)[0])
    }

    @Test
    fun `escapes are never cut and are not delimiters`() {
        val text = "\\*" + words(12) + "\\*"
        val parts = split(text, 30, TextFormat.MARKDOWN)
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf("\\\\".repeat(10), "\\\\".repeat(5)), split("\\\\".repeat(15), 21, TextFormat.MARKDOWN))
        assertEquals(listOf("a".repeat(29) + "\\", "a"), split("a".repeat(29) + "\\a", 30, TextFormat.MARKDOWN))
    }

    @Test
    fun `quotes and headings go on in the next part`() {
        val quote = split("> " + words(12), 30, TextFormat.MARKDOWN)
        assertTrue(quote.all { it.startsWith("> ") }, "$quote")
        val heading = split("## " + words(12), 30, TextFormat.MARKDOWN)
        assertTrue(heading.all { it.startsWith("## ") }, "$heading")
        // A cut between lines needs no marker.
        val lines = split("> " + words(5) + "\n" + words(5), 30, TextFormat.MARKDOWN)
        assertEquals(listOf("> " + words(5), words(5)), lines)
        // Plain text has no markers.
        val plain = split("> " + words(12), 30)
        assertTrue(!plain[1].startsWith(">"), "$plain")
    }

    // ------------------------------------------------------------------ html

    @Test
    fun `html tags are closed and opened again with their attributes`() {
        val text = "<b>" + words(12) + "</b>"
        val parts = split(text, 30, TextFormat.HTML)
        assertTrue(parts.all { it.startsWith("<b>") && it.endsWith("</b>") }, "$parts")
        val link = split("<a href=\"https://max.ru\"><i>" + words(20) + "</i></a>", 80, TextFormat.HTML)
        assertTrue(link.all { it.startsWith("<a href=\"https://max.ru\"><i>") && it.endsWith("</i></a>") }, "$link")
        val upper = split("<B>" + words(12) + "</B>", 30, TextFormat.HTML)
        assertTrue(upper.drop(1).all { it.startsWith("<B>") } && upper.all { it.endsWith("</b>") || it.endsWith("</B>") }, "$upper")
    }

    @Test
    fun `tags and entities are never cut`() {
        val parts = split("&amp;".repeat(10), 22, TextFormat.HTML)
        assertEquals(listOf("&amp;".repeat(4), "&amp;".repeat(4), "&amp;".repeat(2)), parts)
        assertEquals(listOf("&#128512;&#x1F600;", "&#128512;&#x1F600;"), split("&#128512;&#x1F600;".repeat(2), 20, TextFormat.HTML))
        val tags = split("<code>" + "x".repeat(10) + "</code>" + "<code>" + "y".repeat(10) + "</code>", 23, TextFormat.HTML)
        assertEquals(listOf("<code>" + "x".repeat(10) + "</code>", "<code>" + "y".repeat(10) + "</code>"), tags)
    }

    @Test
    fun `stray html is plain text`() {
        val text = "a < b and c > d, <br/> x &amp y </i> " + words(6) + " <b"
        val parts = split(text, 30, TextFormat.HTML)
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.none { it.contains("</br>") || it.contains("<i>") }, "$parts")
    }

    @Test
    fun `markup too long to open again is dropped rather than overflowing`() {
        val href = "<a href=\"https://example.com/" + "u".repeat(14) + "\">"
        assertEquals(45, href.length)
        val parts = split(href + words(15) + "</a>", 50, TextFormat.HTML)
        assertTrue(parts[0].startsWith(href) && parts[0].endsWith("</a>"), "$parts")
        assertTrue(parts.drop(1).none { it.startsWith(href) }, "$parts")
    }

    @Test
    fun `a whole message of markup falls back to a plain cut`() {
        val text = "<b>" + "x".repeat(40) + "</b>"
        val parts = split(text, 20, TextFormat.HTML)
        assertEquals(text, parts.joinToString("").replace("</b><b>", ""))
    }
}
