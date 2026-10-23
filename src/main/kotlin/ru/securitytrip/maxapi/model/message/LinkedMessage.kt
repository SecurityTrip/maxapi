package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.user.User

/** A forwarded message or the message being replied to. */
public data class LinkedMessage(
    val type: MessageLinkType,
    /** Author of the linked message; `null` if it was posted on behalf of a channel. */
    val sender: User? = null,
    /** Chat where the linked message was originally posted. */
    val chatId: Long? = null,
    val message: MessageBody,
)
