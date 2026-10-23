package ru.securitytrip.maxapi.model.attachment

/** Link preview. */
public data class ShareAttachment(
    val payload: ShareAttachmentPayload,
    /** Title of the linked page. */
    val title: String? = null,
    /** Description of the linked page. */
    val description: String? = null,
    /** Preview image of the linked page. */
    val imageUrl: String? = null,
) : Attachment() {
    override val type: String get() = "share"
}

/** Payload of a link preview, also used in [ShareAttachmentRequest]. */
public data class ShareAttachmentPayload @JvmOverloads constructor(
    /** URL to preview. */
    val url: String? = null,
    /** Token of an existing preview. */
    val token: String? = null,
)
