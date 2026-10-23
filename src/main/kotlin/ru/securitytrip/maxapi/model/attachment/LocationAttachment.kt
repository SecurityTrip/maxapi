package ru.securitytrip.maxapi.model.attachment

import ru.securitytrip.maxapi.model.keyboard.RequestGeoLocationButton

/** A geographic location, e.g. sent after pressing a [RequestGeoLocationButton]. */
public data class LocationAttachment(
    val latitude: Double,
    val longitude: Double,
) : Attachment() {
    override val type: String get() = "location"
}
