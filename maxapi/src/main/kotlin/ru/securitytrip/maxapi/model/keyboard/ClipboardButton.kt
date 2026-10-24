package ru.securitytrip.maxapi.model.keyboard

/** Copies `payload` to the user's clipboard. */
public data class ClipboardButton(
    override val text: String,
    /** Text to copy, up to 1024 characters. */
    val payload: String,
) : Button() {
    override val type: String get() = "clipboard"
}
