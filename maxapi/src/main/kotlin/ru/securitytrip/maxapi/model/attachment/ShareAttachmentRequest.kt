package ru.securitytrip.maxapi.model.attachment

/** A media preview of an external URL. */
public data class ShareAttachmentRequest(
    val payload: ShareAttachmentPayload,
) : AttachmentRequest() {
    override val type: String get() = "share"

    public companion object {
        /** A preview of [url]. */
        @JvmStatic
        public fun ofUrl(url: String): ShareAttachmentRequest = ShareAttachmentRequest(ShareAttachmentPayload(url = url))
    }
}
