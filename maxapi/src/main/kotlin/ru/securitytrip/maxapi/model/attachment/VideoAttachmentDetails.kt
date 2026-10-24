package ru.securitytrip.maxapi.model.attachment

import com.fasterxml.jackson.annotation.JsonProperty

/** Playback URLs and metadata of a video, returned by `MaxClient.getVideoAttachmentDetails`. */
public data class VideoAttachmentDetails(
    /** Video attachment token. */
    val token: String,
    /** URLs to download or play the video; `null` if the video is unavailable. */
    val urls: VideoUrls? = null,
    val thumbnail: PhotoAttachmentPayload? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Duration in seconds. */
    val duration: Int = 0,
)

/** Direct video URLs by resolution. Each one is `null` if that resolution is not available. */
public data class VideoUrls(
    @JsonProperty("mp4_1080") val mp4p1080: String? = null,
    @JsonProperty("mp4_720") val mp4p720: String? = null,
    @JsonProperty("mp4_480") val mp4p480: String? = null,
    @JsonProperty("mp4_360") val mp4p360: String? = null,
    @JsonProperty("mp4_240") val mp4p240: String? = null,
    @JsonProperty("mp4_144") val mp4p144: String? = null,
    /** HLS streaming URL. */
    val hls: String? = null,
)
