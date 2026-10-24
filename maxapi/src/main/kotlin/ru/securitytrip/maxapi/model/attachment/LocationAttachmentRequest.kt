package ru.securitytrip.maxapi.model.attachment

/** A geographic location to attach. */
public data class LocationAttachmentRequest(
    val latitude: Double,
    val longitude: Double,
) : AttachmentRequest() {
    override val type: String get() = "location"
}
