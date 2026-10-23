package ru.securitytrip.maxapi.model.chat

/** Request body of `POST /chats/{chatId}/members`: up to 100 user identifiers. */
public data class UserIdsList(
    val userIds: List<Long>,
)
