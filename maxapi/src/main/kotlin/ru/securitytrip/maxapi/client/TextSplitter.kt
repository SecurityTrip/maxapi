package ru.securitytrip.maxapi.client

import ru.securitytrip.maxapi.model.markup.TextFormat

/**
 * Splits text that does not fit into one message (4000 characters, see [MaxLimits]) into parts
 * that do. [MaxClient.sendLongMessage] and `UpdateContext.replyLong` use it to send such text as
 * several messages.
 *
 * Text is cut where it breaks naturally: between paragraphs, else between lines, else between
 * words; a weaker break is used only when the stronger one would leave the part less than half
 * full. A word longer than a whole message is cut where it must, but never inside a surrogate pair
 * (an emoji stays whole), before a combining mark or around a zero-width joiner. Whitespace at a
 * cut is dropped.
 *
 * Markup stays valid in every part. In [TextFormat.MARKDOWN] text, links are never cut, nor are
 * escapes like `\*`; spans such as `**bold**`, `` `code` `` or ```` ```blocks``` ```` that cross a
 * cut are closed at the end of one part and opened again at the start of the next, and a quote
 * (`> `) or a heading (`# `) cut mid-line goes on in the next part. In [TextFormat.HTML] text, tags
 * and entities are never cut, and open tags are closed and opened again with their attributes. The
 * markup added this way counts towards the limit; markup that would take more than half of a part
 * (say, a link with a very long URL) is closed but not opened again.
 *
 * ```kotlin
 * TextSplitter.split(report, TextFormat.MARKDOWN).forEach { client.sendMessageToChat(chatId, NewMessageBody(it, format = TextFormat.MARKDOWN)) }
 * ```
 */
public object TextSplitter {
    /** The smallest [split] limit: smaller parts could not hold the markup that is opened again. */
    public const val MIN_LIMIT: Int = 16

    /**
     * Splits [text] into parts of at most [limit] characters (counted like [String.length]).
     * Text that fits is returned as the only part, unchanged.
     *
     * @param format how [text] is formatted; `null` for plain text, which has no markup to keep.
     * @throws IllegalArgumentException if [limit] is less than [MIN_LIMIT].
     */
    @JvmStatic
    @JvmOverloads
    public fun split(text: String, format: TextFormat? = null, limit: Int = MaxLimits.TEXT_LENGTH): List<String> {
        require(limit >= MIN_LIMIT) { "limit must be >= $MIN_LIMIT" }
        if (text.length <= limit) return listOf(text)
        val markup = when (format) {
            TextFormat.MARKDOWN -> Markdown(text).parse()
            TextFormat.HTML -> Html(text).parse()
            null -> Markup(text, emptyList())
        }
        return Splitter(text, markup, limit).split()
    }

    /** Markup that crosses a cut is closed with [closer] before it and opened with [opener] after it. */
    private class Span(val openStart: Int, val openEnd: Int, val closeStart: Int, val closeEnd: Int, val opener: String, val closer: String)

    /**
     * Spans of a text and the positions it must not be cut at: `blocked[i]` forbids a cut between
     * `text[i - 1]` and `text[i]`.
     */
    private class Markup(text: String, spans: List<Span>, val linePrefixes: Boolean = false) {
        /** Spans in the order they open, so the spans open at a cut are closed in reverse order. */
        val spans: List<Span> = spans.sortedBy { it.openStart }
        val blocked = BooleanArray(text.length + 1)

        init {
            for (i in 1 until text.length) if (joins(text, i)) blocked[i] = true
            for (span in spans) {
                // Cutting right after an opener or right before a closer would leave an empty span.
                block(span.openStart, span.openEnd + 1)
                block(span.closeStart - 1, span.closeEnd)
            }
        }

        /** Forbids cuts inside `[start, end)`. */
        fun block(start: Int, end: Int) {
            for (i in start + 1 until end) blocked[i] = true
        }
    }

    private class Splitter(private val text: String, private val markup: Markup, private val limit: Int) {
        private val parts = ArrayList<String>()

        fun split(): List<String> {
            var pos = 0
            var prefix = ""
            while (prefix.length + text.length - pos > limit) {
                val cut = findCut(pos, prefix)
                if (cut == null) {
                    // Nothing fits with the markup kept (e.g. a link longer than a message): cut plainly.
                    val end = hardEnd(pos, minOf(text.length, pos + limit))
                    parts += text.substring(pos, end)
                    pos = end
                    prefix = ""
                } else {
                    parts += prefix + text.substring(pos, cut.contentEnd) + cut.closers
                    pos = skipWhitespace(cut.end)
                    prefix = cut.nextPrefix
                }
            }
            if (pos < text.length) parts += prefix + text.substring(pos)
            return parts
        }

        private class Cut(val end: Int, val contentEnd: Int, val closers: String, val nextPrefix: String)

        /** The best cut of the part that starts at [pos]; `null` if no cut keeps the markup within [limit]. */
        private fun findCut(pos: Int, prefix: String): Cut? {
            val budget = limit - prefix.length
            val maxEnd = pos + budget
            val firstContent = skipWhitespace(pos)
            val spans = markup.spans.filter { it.openEnd < maxEnd && it.closeStart > pos }
            // best[level] is the rightmost cut at a break of at least that level.
            val best = arrayOfNulls<Cut>(PARAGRAPH + 1)
            var end = maxEnd
            while (end > firstContent && best[PARAGRAPH] == null) {
                val level = if (markup.blocked[end]) -1 else levelAt(end)
                // Cuts are recorded for every level up to theirs, so best[level] set means all lower ones are.
                if (level >= 0 && best[level] == null) {
                    val cut = cutAt(pos, end, level, prefix, spans)
                    if (cut != null) for (l in 0..level) if (best[l] == null) best[l] = cut
                }
                end--
            }
            for (level in PARAGRAPH downTo WORD) best[level]?.takeIf { it.end - pos >= budget / 2 }?.let { return it }
            return (PARAGRAPH downTo CHAR).firstNotNullOfOrNull { best[it] }
        }

        /** The cut at [end] with the markup that crosses it, or `null` if that markup does not fit. */
        private fun cutAt(pos: Int, end: Int, level: Int, prefix: String, spans: List<Span>): Cut? {
            val open = spans.filter { it.openEnd < end && end < it.closeStart }
            val closers = open.asReversed().joinToString("") { it.closer }
            val contentEnd = trimEnd(pos, end)
            if (prefix.length + contentEnd - pos + closers.length > limit) return null
            // Markup that would fill most of the next part is not opened again, or text would go a few characters a part.
            val opener = open.joinToString("") { it.opener }.takeIf { it.length + closers.length <= limit / 2 }.orEmpty()
            val linePrefix = if (markup.linePrefixes && level < LINE) linePrefixAt(end) else ""
            return Cut(end, contentEnd, closers, linePrefix + opener)
        }

        /** How strong the break at [end] is: [PARAGRAPH], [LINE], [WORD] or [CHAR]. */
        private fun levelAt(end: Int): Int {
            val afterLine = lineBreakEnd(text, end)
            return when {
                afterLine < 0 -> if (text[end].isWhitespace()) WORD else CHAR
                lineBreakEnd(text, skipBlanks(text, afterLine)) >= 0 -> PARAGRAPH
                else -> LINE
            }
        }

        /** The quote or heading marker of the line that contains [end], to go on with it in the next part. */
        private fun linePrefixAt(end: Int): String {
            val lineStart = text.lastIndexOf('\n', end - 1) + 1
            return LINE_PREFIX.find(text.substring(lineStart, minOf(end, lineStart + 8)))?.value.orEmpty()
        }

        /** A cut at most at [max] that does not split a surrogate pair or a character from its marks. */
        private fun hardEnd(pos: Int, max: Int): Int {
            var end = max
            while (end > pos + 1 && end < text.length && joins(text, end)) end--
            return end
        }

        private fun skipWhitespace(from: Int): Int {
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        private fun trimEnd(pos: Int, end: Int): Int {
            var i = end
            while (i > pos && text[i - 1].isWhitespace()) i--
            return i
        }
    }

    /** Spans and atoms of Max-flavored Markdown. */
    private class Markdown(private val text: String) {
        private val spans = ArrayList<Span>()
        private val atoms = ArrayList<IntRange>()
        private val open = ArrayList<Pair<String, Int>>()

        fun parse(): Markup {
            var i = 0
            while (i < text.length) {
                i = when {
                    text[i] == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> atom(i, i + 2)
                    text.startsWith(FENCE, i) -> code(i, FENCE)
                    text[i] == '`' -> code(i, "`")
                    text[i] == '[' -> link(i)
                    lineBreakEnd(text, i) >= 0 -> paragraphBreak(i)
                    else -> delimiter(i)
                }
            }
            return Markup(text, spans, linePrefixes = true).also { markup -> atoms.forEach { markup.block(it.first, it.last + 1) } }
        }

        private fun atom(start: Int, end: Int): Int {
            atoms += start until end
            return end
        }

        /** A code span or block: nothing inside it is markup. Inline code does not cross paragraphs. */
        private fun code(start: Int, fence: String): Int {
            val close = text.indexOf(fence, start + fence.length)
            if (close < 0 || (fence != FENCE && paragraphBreakBetween(start, close))) return start + fence.length
            spans += Span(start, start + fence.length, close, close + fence.length, fence, fence)
            return close + fence.length
        }

        /** `[text](url)` as an atom; a `[` that starts no link is plain text. */
        private fun link(start: Int): Int {
            val close = text.indexOf("](", start + 1)
            val end = if (close < 0) -1 else text.indexOf(')', close + 2)
            if (end < 0 || paragraphBreakBetween(start, end)) return start + 1
            return atom(start, end + 1)
        }

        /** Emphasis does not cross paragraphs, so a blank line forgets the unclosed delimiters. */
        private fun paragraphBreak(i: Int): Int {
            val next = lineBreakEnd(text, i)
            if (lineBreakEnd(text, skipBlanks(text, next)) >= 0) open.clear()
            return next
        }

        /** Pairs delimiters such as `**` by the flanking rules of CommonMark, simplified. */
        private fun delimiter(i: Int): Int {
            val delimiter = DELIMITERS.firstOrNull { text.startsWith(it, i) } ?: return i + 1
            val end = i + delimiter.length
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (end < text.length) text[end] else ' '
            val underscore = delimiter[0] == '_'
            val canOpen = !after.isWhitespace() && !(underscore && before.isLetterOrDigit())
            val canClose = !before.isWhitespace() && !(underscore && after.isLetterOrDigit())
            val opened = if (canClose) open.indexOfLast { it.first == delimiter } else -1
            if (opened >= 0) {
                val start = open[opened].second
                spans += Span(start, start + delimiter.length, i, end, delimiter, delimiter)
                open.subList(opened, open.size).clear()
            } else if (canOpen) {
                open += delimiter to i
            }
            return end
        }

        private fun paragraphBreakBetween(start: Int, end: Int): Boolean =
            PARAGRAPH_BREAK.find(text.substring(start, end)) != null
    }

    /** Tags and entities of the HTML subset of Max. */
    private class Html(private val text: String) {
        fun parse(): Markup {
            val spans = ArrayList<Span>()
            val atoms = ArrayList<IntRange>()
            val open = ArrayList<Triple<String, IntRange, String>>()
            var i = 0
            while (i < text.length) {
                val tag = if (text[i] == '<') TAG.find(text.substring(i, tagEnd(i))) else null
                val entity = if (text[i] == '&') ENTITY.find(text.substring(i, minOf(text.length, i + 12))) else null
                val match = tag ?: entity
                if (match == null) {
                    i++
                    continue
                }
                val range = i until i + match.value.length
                atoms += range
                if (tag != null) {
                    val name = tag.groupValues[2].lowercase()
                    if (tag.groupValues[1] == "/") {
                        val opened = open.indexOfLast { it.first == name }
                        if (opened >= 0) {
                            val start = open[opened]
                            spans += Span(start.second.first, start.second.last + 1, range.first, range.last + 1, start.third, "</$name>")
                            open.subList(opened, open.size).clear()
                        }
                    } else if (!tag.groupValues[3].endsWith("/")) {
                        open += Triple(name, range, tag.value)
                    }
                }
                i = range.last + 1
            }
            return Markup(text, spans).also { markup -> atoms.forEach { markup.block(it.first, it.last + 1) } }
        }

        private fun tagEnd(start: Int): Int = text.indexOf('>', start).let { if (it < 0) start + 1 else it + 1 }
    }

    private const val CHAR = 0
    private const val WORD = 1
    private const val LINE = 2
    private const val PARAGRAPH = 3

    private const val FENCE = "```"
    private val DELIMITERS = listOf("**", "__", "~~", "++", "^^", "*", "_")
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!~^<>|"
    private val PARAGRAPH_BREAK = Regex("\r?\n[ \t]*\r?\n")
    private val LINE_PREFIX = Regex("^(?:>+ ?|#{1,6} )")
    private val TAG = Regex("^<(/?)([A-Za-z][A-Za-z0-9]*)([^<>]*)>")
    private val ENTITY = Regex("^&(?:#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")

    /** Index after the line break at [i], or `-1` if there is none. */
    private fun lineBreakEnd(text: String, i: Int): Int = when {
        i >= text.length -> -1
        text[i] == '\n' -> i + 1
        text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> i + 2
        else -> -1
    }

    private fun skipBlanks(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /** Whether `text[i - 1]` and `text[i]` belong to one character as the user sees it. */
    private fun joins(text: String, i: Int): Boolean {
        val before = text[i - 1]
        val after = text[i]
        return (before.isHighSurrogate() && after.isLowSurrogate()) ||
            before == ZWJ || after == ZWJ || after in '︀'..'️' ||
            Character.getType(after).toByte() in MARKS ||
            (after.isHighSurrogate() && i + 1 < text.length && Character.toCodePoint(after, text[i + 1]) in SKIN_TONES)
    }

    private const val ZWJ = '‍'
    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK)
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
}
