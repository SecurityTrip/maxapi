package ru.securitytrip.maxapi.model.attachment

/** A video to attach. Usually obtained from `MaxClient.uploadVideo`. */
public data class VideoAttachmentRequest(
    val payload: UploadedInfo,
) : AttachmentRequest() {
    override val type: String get() = "video"

    public companion object {
        /** A video that is already in Max, by token. */
        @JvmStatic
        public fun ofToken(token: String): VideoAttachmentRequest = VideoAttachmentRequest(UploadedInfo(token))
    }
}
