package ru.securitytrip.maxapi.model.markup

/** A link. */
public data class LinkMarkup(
    override val from: Int,
    override val length: Int,
    /** Link target, 1–2048 characters. */
    val url: String,
) : MarkupElement() {
    override val type: String get() = "link"
}
