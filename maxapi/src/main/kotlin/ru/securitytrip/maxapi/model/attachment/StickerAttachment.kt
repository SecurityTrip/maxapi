package ru.securitytrip.maxapi.model.attachment

/** A sticker. */
public data class StickerAttachment(
    val payload: StickerAttachmentPayload,
    val width: Int = 0,
    val height: Int = 0,
) : Attachment() {
    override val type: String get() = "sticker"
}

/** Payload of a sticker attachment. */
public data class StickerAttachmentPayload(
    /** Sticker image URL. */
    val url: String,
    /** Sticker code. Send the same sticker with `StickerAttachmentRequest.ofCode(code)`. */
    val code: String,
)
