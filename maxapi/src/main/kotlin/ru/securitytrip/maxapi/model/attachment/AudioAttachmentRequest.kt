package ru.securitytrip.maxapi.model.attachment

/** An audio recording to attach. It must be the only attachment of the message. */
public data class AudioAttachmentRequest(
    val payload: UploadedInfo,
) : AttachmentRequest() {
    override val type: String get() = "audio"

    public companion object {
        /** Audio that is already in Max, by token. */
        @JvmStatic
        public fun ofToken(token: String): AudioAttachmentRequest = AudioAttachmentRequest(UploadedInfo(token))
    }
}
