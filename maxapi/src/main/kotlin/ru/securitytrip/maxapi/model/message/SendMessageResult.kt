package ru.securitytrip.maxapi.model.message

/** Response of `POST /messages`. */
public data class SendMessageResult(
    val message: Message,
)
