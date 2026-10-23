package ru.securitytrip.maxapi.model.attachment

import ru.securitytrip.maxapi.dsl.MessageBuilder
import ru.securitytrip.maxapi.model.keyboard.Button

/**
 * An inline keyboard to attach. Build it with [ru.securitytrip.maxapi.dsl.keyboard] or
 * [ru.securitytrip.maxapi.dsl.KeyboardBuilder], or add rows directly in
 * [MessageBuilder.keyboardRow][ru.securitytrip.maxapi.dsl.MessageBuilder.keyboardRow].
 */
public data class InlineKeyboardAttachmentRequest(
    val payload: InlineKeyboardAttachmentRequestPayload,
) : AttachmentRequest() {
    override val type: String get() = "inline_keyboard"

    /** A keyboard with the given rows of buttons. */
    public constructor(buttons: List<List<Button>>) : this(InlineKeyboardAttachmentRequestPayload(buttons))
}

/** Rows of buttons; at least one button is required. */
public data class InlineKeyboardAttachmentRequestPayload(
    val buttons: List<List<Button>>,
)
