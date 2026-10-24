package ru.securitytrip.maxapi.model.markup

import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/**
 * Markup language of a message text.
 *
 * Supported formatting: bold, italic, strikethrough, underline, monospace, highlight,
 * quotes, headers, links and user mentions. See
 * [text formatting](https://dev.max.ru/docs-api) in the API docs for the exact syntax.
 */
public enum class TextFormat(@get:JsonValue override val value: String) : ApiEnum {
    /** Max-flavored Markdown: `**bold**`, `*italic*`, `~~strike~~`, `++underline++`, `^^highlight^^`, ... */
    MARKDOWN("markdown"),

    /** A subset of HTML: `<b>`, `<i>`, `<s>`, `<u>`, `<a>`, `<code>`, `<mark>`, `<blockquote>`, `<h1>`. */
    HTML("html"),
}
