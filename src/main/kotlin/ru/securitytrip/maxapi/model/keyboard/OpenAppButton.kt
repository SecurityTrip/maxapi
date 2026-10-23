package ru.securitytrip.maxapi.model.keyboard

/** Opens a mini app. */
public data class OpenAppButton(
    override val text: String,
    /** Public name of the bot the mini app belongs to. */
    val webApp: String,
    /** Data passed to the mini app, up to 512 characters of `[A-Za-z0-9_-]`. */
    val payload: String? = null,
    /** Identifier of the bot the mini app belongs to. */
    val contactId: Long? = null,
) : Button() {
    override val type: String get() = "open_app"
}
