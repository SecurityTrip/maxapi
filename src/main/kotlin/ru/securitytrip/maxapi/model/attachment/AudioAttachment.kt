package ru.securitytrip.maxapi.model.attachment

/** An audio recording, including voice messages. */
public data class AudioAttachment(
    val payload: MediaAttachmentPayload,
    /** Speech-to-text transcription, if available. */
    val transcription: String? = null,
) : Attachment() {
    override val type: String get() = "audio"
}
