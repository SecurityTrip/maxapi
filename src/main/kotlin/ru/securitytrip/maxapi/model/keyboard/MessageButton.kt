package ru.securitytrip.maxapi.model.keyboard

/** Sends a message with the button `text` to the chat on behalf of the user. */
public data class MessageButton(
    override val text: String,
) : Button() {
    override val type: String get() = "message"
}
