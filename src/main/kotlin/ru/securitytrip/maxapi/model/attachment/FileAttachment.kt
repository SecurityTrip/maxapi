package ru.securitytrip.maxapi.model.attachment

/** A file. */
public data class FileAttachment(
    val payload: FileAttachmentPayload,
    /** Original file name. */
    val filename: String,
    /** Size in bytes. */
    val size: Long,
) : Attachment() {
    override val type: String get() = "file"
}

/** Payload of a file attachment. */
public data class FileAttachmentPayload(
    /** Download URL. */
    val url: String,
    /** Send the same file again with `FileAttachmentRequest.ofToken(token)`. */
    val token: String,
)
