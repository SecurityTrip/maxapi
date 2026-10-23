package ru.securitytrip.maxapi.model.attachment


/** An image. */
public data class PhotoAttachment(
    val payload: PhotoAttachmentPayload,
) : Attachment() {
    override val type: String get() = "image"
}

/** Image metadata. */
public data class PhotoAttachmentPayload(
    /** Unique image identifier. */
    val photoId: Long,
    /** Send the same image again with `PhotoAttachmentRequest.ofToken(token)`. */
    val token: String,
    /** Image URL. */
    val url: String,
)
