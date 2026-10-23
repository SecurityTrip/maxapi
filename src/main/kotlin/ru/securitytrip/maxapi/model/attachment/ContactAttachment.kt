package ru.securitytrip.maxapi.model.attachment

import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.user.User

/** A contact card, e.g. sent after pressing a [RequestContactButton]. */
public data class ContactAttachment(
    val payload: ContactAttachmentPayload,
) : Attachment() {
    override val type: String get() = "contact"
}

/** Contact details. */
public data class ContactAttachmentPayload(
    /** Contact in VCF (vCard) format; contains the phone number. */
    val vcfInfo: String? = null,
    /** Hash of `vcfInfo`. */
    val hash: String? = null,
    /** The Max user, if the contact is registered in Max. */
    val maxInfo: User? = null,
)
