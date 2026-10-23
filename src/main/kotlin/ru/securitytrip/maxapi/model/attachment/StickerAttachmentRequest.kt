package ru.securitytrip.maxapi.model.attachment

/** A sticker to attach. It must be the only attachment of the message. */
public data class StickerAttachmentRequest(
    val payload: StickerAttachmentRequestPayload,
) : AttachmentRequest() {
    override val type: String get() = "sticker"

    public companion object {
        /** A sticker by its [StickerAttachmentPayload.code]. */
        @JvmStatic
        public fun ofCode(code: String): StickerAttachmentRequest =
            StickerAttachmentRequest(StickerAttachmentRequestPayload(code))
    }
}

/** Sticker to send. */
public data class StickerAttachmentRequestPayload(
    val code: String,
)
