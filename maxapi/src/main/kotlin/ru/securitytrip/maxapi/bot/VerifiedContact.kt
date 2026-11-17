package ru.securitytrip.maxapi.bot

import ru.securitytrip.maxapi.model.attachment.ContactAttachment
import ru.securitytrip.maxapi.model.attachment.ContactAttachmentPayload
import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.message.Message

/**
 * A contact the sender of a message shared about themselves by pressing a [RequestContactButton] of
 * the bot, with the signature of Max checked: get it with [MessageContext.sharedContact].
 *
 * ```kotlin
 * bot.onMessage(Filters.hasVerifiedContact()) {
 *     val contact = sharedContact()!!
 *     users.savePhone(contact.userId, contact.phone)
 * }
 * ```
 *
 * @property userId the user who shared the contact, the sender of the message.
 * @property phone the phone number as `+` and its digits, e.g. `+79991234567`.
 * @property name the name in the contact card, if it has one.
 * @property contact the contact as received.
 */
public data class VerifiedContact(
    val userId: Long,
    val phone: String,
    val name: String?,
    val contact: ContactAttachmentPayload,
) {
    public companion object {
        /**
         * The first contact of [message] that is the sender's own: signed by Max for the bot [botToken]
         * ([ContactAttachmentPayload.verify]), describing the sender ([ContactAttachmentPayload.maxInfo]
         * is the sender) and having a phone number. `null` if there is none.
         *
         * @throws IllegalArgumentException if [botToken] is blank.
         */
        @JvmStatic
        public fun of(message: Message, botToken: String): VerifiedContact? {
            val sender = message.sender?.userId
            return message.body?.attachments.orEmpty().filterIsInstance<ContactAttachment>().firstNotNullOfOrNull { attachment ->
                val payload = attachment.payload
                val phone = payload.phoneNumber()
                if (payload.verify(botToken) && sender != null && payload.maxInfo?.userId == sender && phone != null) {
                    VerifiedContact(sender, phone, payload.fullName(), payload)
                } else {
                    null
                }
            }
        }
    }
}
