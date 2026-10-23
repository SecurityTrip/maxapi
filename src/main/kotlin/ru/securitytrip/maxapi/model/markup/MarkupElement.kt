package ru.securitytrip.maxapi.model.markup

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import ru.securitytrip.maxapi.model.message.NewMessageBody

/**
 * Formatting of a range of a received message text: characters `[from, from + length)`.
 *
 * The server reports formatting this way regardless of whether the message was sent with
 * Markdown, HTML or from a client app. To format outgoing messages, set
 * [NewMessageBody.format] instead. Unknown types become [UnknownMarkup].
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
    defaultImpl = UnknownMarkup::class,
)
@JsonSubTypes(
    JsonSubTypes.Type(StrongMarkup::class, name = "strong"),
    JsonSubTypes.Type(EmphasizedMarkup::class, name = "emphasized"),
    JsonSubTypes.Type(MonospacedMarkup::class, name = "monospaced"),
    JsonSubTypes.Type(LinkMarkup::class, name = "link"),
    JsonSubTypes.Type(StrikethroughMarkup::class, name = "strikethrough"),
    JsonSubTypes.Type(UnderlineMarkup::class, name = "underline"),
    JsonSubTypes.Type(UserMentionMarkup::class, name = "user_mention"),
    JsonSubTypes.Type(HeadingMarkup::class, name = "heading"),
    JsonSubTypes.Type(HighlightedMarkup::class, name = "highlighted"),
    JsonSubTypes.Type(QuoteMarkup::class, name = "quote"),
)
public sealed class MarkupElement {
    /** Wire type of the element, e.g. `"strong"`. */
    public abstract val type: String

    /** Start index of the range in the text, zero-based. */
    public abstract val from: Int

    /** Length of the range. */
    public abstract val length: Int
}

/** Bold text. */
public data class StrongMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "strong"
}

/** Italic text. */
public data class EmphasizedMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "emphasized"
}

/** Inline code or a code block. */
public data class MonospacedMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "monospaced"
}

/** Strikethrough text. */
public data class StrikethroughMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "strikethrough"
}

/** Underlined text. */
public data class UnderlineMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "underline"
}

/** A header. */
public data class HeadingMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "heading"
}

/** Highlighted text (red by default). */
public data class HighlightedMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "highlighted"
}

/** A quote. */
public data class QuoteMarkup(override val from: Int, override val length: Int) : MarkupElement() {
    override val type: String get() = "quote"
}
