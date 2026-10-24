package ru.securitytrip.maxapi.model.attachment

/** A video. Use `MaxClient.getVideoAttachmentDetails(payload.token)` for direct playback URLs. */
public data class VideoAttachment(
    val payload: MediaAttachmentPayload,
    val thumbnail: VideoThumbnail? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Duration in seconds. */
    val duration: Int? = null,
) : Attachment() {
    override val type: String get() = "video"
}

/** Preview image of a video. */
public data class VideoThumbnail(
    val url: String,
)
