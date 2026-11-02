package ru.securitytrip.maxapi.dsl

/**
 * Text for messages in [ru.securitytrip.maxapi.model.message.TextFormat.HTML]. Text that users wrote,
 * such as their names, must be escaped before it goes into formatted text: otherwise they can add
 * links, mentions and formatting to a message the bot sends.
 *
 * ```kotlin
 * reply { html { text("Hi, "); bold(user.firstName); text("! "); link("Open the shop", shopUrl) } }
 * reply { html("Hi, <b>${Html.escape(user.firstName)}</b>!") }
 * ```
 */
public object Html {
    /** [text] with `&`, `<`, `>`, `"` and `'` replaced by entities, so that it shows as is. */
    @JvmStatic
    public fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    /** Starts building HTML text in Java: `Html.builder().text("Hi, ").bold(name).build()`. */
    @JvmStatic
    public fun builder(): HtmlBuilder = HtmlBuilder()
}

/**
 * Text for messages in [ru.securitytrip.maxapi.model.message.TextFormat.MARKDOWN]. Prefer [Html] for
 * text that users wrote: Max follows CommonMark 0.29, where a backslash makes punctuation literal, but
 * its documentation does not describe escaping.
 */
public object Markdown {
    private const val SPECIAL = "\\`*_~+^[]()#>!<|-.="

    /** [text] with a backslash before every character that Max markdown could read as markup. */
    @JvmStatic
    public fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            if (char in SPECIAL) append('\\')
            append(char)
        }
    }
}

/**
 * Builds HTML text in which everything passed in is escaped, so that only the formatting of the
 * builder is markup. Use it with [MessageBuilder.html]:
 *
 * ```kotlin
 * message { html { bold(order.title); text(" is ready, "); mention(user.name, user.userId) } }
 * ```
 */
@MaxDsl
public class HtmlBuilder {
    private val html = StringBuilder()

    /** Plain text. */
    public fun text(value: String): HtmlBuilder = apply { html.append(Html.escape(value)) }

    /** A line break. */
    public fun newline(): HtmlBuilder = apply { html.append('\n') }

    /** **Bold** text. */
    public fun bold(value: String): HtmlBuilder = tag("b", value)

    /** *Italic* text. */
    public fun italic(value: String): HtmlBuilder = tag("i", value)

    /** Underlined text. */
    public fun underline(value: String): HtmlBuilder = tag("u", value)

    /** Struck out text. */
    public fun strikethrough(value: String): HtmlBuilder = tag("s", value)

    /** Monospaced text. */
    public fun code(value: String): HtmlBuilder = tag("code", value)

    /** Highlighted text. */
    public fun highlighted(value: String): HtmlBuilder = tag("mark", value)

    /** A quote. */
    public fun quote(value: String): HtmlBuilder = tag("blockquote", value)

    /** A link with [text] to [url]. */
    public fun link(text: String, url: String): HtmlBuilder =
        apply { html.append("<a href=\"").append(Html.escape(url)).append("\">").append(Html.escape(text)).append("</a>") }

    /** A mention of the user [userId], shown as [name]: Max recommends the user's full name. */
    public fun mention(name: String, userId: Long): HtmlBuilder = link(name, "max://user/$userId")

    /** HTML that is already safe, e.g. built by another [HtmlBuilder]; it is not escaped. */
    public fun raw(value: String): HtmlBuilder = apply { html.append(value) }

    /** The HTML text. */
    public fun build(): String = html.toString()

    override fun toString(): String = build()

    private fun tag(name: String, value: String): HtmlBuilder =
        apply { html.append('<').append(name).append('>').append(Html.escape(value)).append("</").append(name).append('>') }
}
