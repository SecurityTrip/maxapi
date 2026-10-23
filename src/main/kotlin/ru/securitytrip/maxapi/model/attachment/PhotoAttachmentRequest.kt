package ru.securitytrip.maxapi.model.attachment

/** An image to attach. */
public data class PhotoAttachmentRequest(
    val payload: PhotoAttachmentRequestPayload,
) : AttachmentRequest() {
    override val type: String get() = "image"

    public companion object {
        /** An image the server downloads from an external [url]. */
        @JvmStatic
        public fun ofUrl(url: String): PhotoAttachmentRequest =
            PhotoAttachmentRequest(PhotoAttachmentRequestPayload(url = url))

        /** An image that is already in Max, by [PhotoAttachmentPayload.token]. */
        @JvmStatic
        public fun ofToken(token: String): PhotoAttachmentRequest =
            PhotoAttachmentRequest(PhotoAttachmentRequestPayload(token = token))
    }
}

/** Source of an image. The fields are mutually exclusive: set exactly one. */
public data class PhotoAttachmentRequestPayload @JvmOverloads constructor(
    /** Any external image URL. */
    val url: String? = null,
    /** Token of an existing image attachment. */
    val token: String? = null,
    /** Tokens returned by the upload server after uploading an image. */
    val photos: Map<String, PhotoToken>? = null,
)

/** Token of an uploaded image. */
public data class PhotoToken(
    val token: String,
)
