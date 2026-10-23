package ru.securitytrip.maxapi.model.attachment

import ru.securitytrip.maxapi.model.keyboard.Keyboard

/** An inline keyboard attached to a message. */
public data class InlineKeyboardAttachment(
    val payload: Keyboard,
) : Attachment() {
    override val type: String get() = "inline_keyboard"
}
