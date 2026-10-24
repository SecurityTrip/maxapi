package ru.securitytrip.maxapi.model.keyboard

import ru.securitytrip.maxapi.model.attachment.ContactAttachment

/** Asks the user to share their contact; the bot receives a message with a [ContactAttachment]. */
public data class RequestContactButton(
    override val text: String,
) : Button() {
    override val type: String get() = "request_contact"
}
