package ru.securitytrip.maxapi.model.keyboard

import ru.securitytrip.maxapi.model.attachment.LocationAttachment

/** Asks the user to share their location; the bot receives a message with a [LocationAttachment]. */
public data class RequestGeoLocationButton(
    override val text: String,
    /** `true` sends the location without asking the user for confirmation. */
    val quick: Boolean? = null,
) : Button() {
    override val type: String get() = "request_geo_location"
}
