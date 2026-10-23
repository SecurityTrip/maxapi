package ru.securitytrip.maxapi.model.attachment

/** Response of `POST /uploads`: where to upload the file. */
public data class UploadEndpoint(
    /** URL to upload the file to with a `multipart/form-data` request. */
    val url: String,
    /** Token to use in the message for video and audio uploads. */
    val token: String? = null,
)
