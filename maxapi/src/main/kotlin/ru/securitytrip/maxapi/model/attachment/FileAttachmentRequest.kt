package ru.securitytrip.maxapi.model.attachment

/** A file to attach. It must be the only attachment of the message. */
public data class FileAttachmentRequest(
    val payload: UploadedInfo,
) : AttachmentRequest() {
    override val type: String get() = "file"

    public companion object {
        /** A file that is already in Max, by token. */
        @JvmStatic
        public fun ofToken(token: String): FileAttachmentRequest = FileAttachmentRequest(UploadedInfo(token))
    }
}
