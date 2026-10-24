package ru.securitytrip.maxapi.model.keyboard

import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate

/**
 * Sends a [MessageCallbackUpdate] with `payload` to the bot. Answer it with
 * `MaxClient.answerOnCallback` (or `CallbackContext.answer` in a bot handler).
 */
public data class CallbackButton(
    override val text: String,
    /** Data the bot receives when the button is pressed, up to 1024 characters. */
    val payload: String,
) : Button() {
    override val type: String get() = "callback"
}
