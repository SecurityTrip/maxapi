package ru.securitytrip.maxapi.model.attachment

/** Payload of a video or audio attachment. */
public data class MediaAttachmentPayload(
    /** Media URL. For videos, use `MaxClient.getVideoAttachmentDetails` to get direct links. */
    val url: String,
    /** Send the same media again with `VideoAttachmentRequest.ofToken` / `AudioAttachmentRequest.ofToken`. */
    val token: String,
)
