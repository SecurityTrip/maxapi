package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.dsl.MessageBuilder
import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.markup.TextFormat

/**
 * A message to send, or the new content of a message being edited.
 *
 * Build it with the Kotlin DSL [ru.securitytrip.maxapi.dsl.message], with [builder] from Java,
 * or with the constructor directly:
 *
 * ```kotlin
 * val body = message {
 *     markdown("**Hello**, choose an option:")
 *     keyboard { row { callback("Yes", "yes"); callback("No", "no") } }
 * }
 * ```
 */
public data class NewMessageBody @JvmOverloads constructor(
    /** Text, up to 4000 characters. */
    val text: String? = null,
    /**
     * Attachments: media obtained from `MaxClient.upload*`, keyboards, stickers, locations and so on.
     *
     * When editing, `null` keeps the current attachments and an empty list removes all of them.
     */
    val attachments: List<AttachmentRequest>? = null,
    /** Makes the message a reply to or a forward of another message. */
    val link: NewMessageLink? = null,
    /**
     * Controls push notifications when sending or editing. The server defaults to `true`.
     * For channels, use `true` or omit the field. In direct and group chats, support for
     * `false` depends on the recipient's app and, on Android in groups, whether the app is open.
     */
    val notify: Boolean? = null,
    /** How to interpret formatting in `text`; `null` sends plain text. */
    val format: TextFormat? = null,
) {
    public companion object {
        /** A fluent builder, convenient from Java. */
        @JvmStatic
        public fun builder(): MessageBuilder = MessageBuilder()

        /** A message with just [text]. */
        @JvmStatic
        public fun text(text: String): NewMessageBody = NewMessageBody(text = text)
    }
}

/** Link to another message for a reply or a forward. */
public data class NewMessageLink(
    val type: MessageLinkType,
    /** [MessageBody.mid] of the original message. */
    val mid: String,
)
