package ru.securitytrip.maxapi.model.keyboard

/** Opens `url`. */
public data class LinkButton(
    override val text: String,
    /** Up to 2048 characters. */
    val url: String,
) : Button() {
    override val type: String get() = "link"
}
