package ru.securitytrip.maxapi.model.attachment

/** A contact card to attach. It must be the only attachment of the message. */
public data class ContactAttachmentRequest(
    val payload: ContactAttachmentRequestPayload,
) : AttachmentRequest() {
    override val type: String get() = "contact"
}

/** Contact to send: either a Max user (`contactId`) or VCF data. */
public data class ContactAttachmentRequestPayload @JvmOverloads constructor(
    /** Display name. */
    val name: String? = null,
    /** Identifier of the contact if they are a Max user. */
    val contactId: Long? = null,
    /** Full contact information in VCF format. */
    val vcfInfo: String? = null,
    /** Phone number in VCF format. */
    val vcfPhone: String? = null,
)
