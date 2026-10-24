package ru.securitytrip.maxapi.model.chat

import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequestPayload

/**
 * Changes to apply with `MaxClient.editChat`. Fields left `null` are not changed.
 *
 * ```kotlin
 * client.editChat(chatId, ChatPatch(title = "New title", notify = false))
 * ```
 */
public data class ChatPatch @JvmOverloads constructor(
    /** New icon: an external URL, a token of an existing image or freshly uploaded photo tokens. */
    val icon: PhotoAttachmentRequestPayload? = null,
    /** New title, 1–200 characters. */
    val title: String? = null,
    /** New description, up to 16000 characters. An empty string removes the description. */
    val description: String? = null,
    /** Identifier of a message to pin. To unpin, use `MaxClient.unpinMessage`. */
    val pin: String? = null,
    /** Whether members are notified with a system message. Defaults to `true` on the server. */
    val notify: Boolean? = null,
)
