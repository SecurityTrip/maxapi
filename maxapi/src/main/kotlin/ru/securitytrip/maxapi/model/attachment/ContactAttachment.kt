package ru.securitytrip.maxapi.model.attachment

import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.user.User
import ru.securitytrip.maxapi.webapp.Hmac

/** A contact card, e.g. sent after pressing a [RequestContactButton]. */
public data class ContactAttachment(
    val payload: ContactAttachmentPayload,
) : Attachment() {
    override val type: String get() = "contact"

    /** Same as [ContactAttachmentPayload.verify]. */
    public fun verify(botToken: String): Boolean = payload.verify(botToken)
}

/** Contact details. */
public data class ContactAttachmentPayload(
    /** Contact in VCF (vCard) format; contains the phone number. */
    val vcfInfo: String? = null,
    /** Hash of `vcfInfo`. */
    val hash: String? = null,
    /** The Max user, if the contact is registered in Max. */
    val maxInfo: User? = null,
) {
    /**
     * `true` if Max signed [vcfInfo] for the bot [botToken], that is the user shared their own
     * contact by pressing a [RequestContactButton] of this bot. Contacts sent any other way have no
     * [hash] and are never verified, so a user cannot pass off someone else's phone number.
     *
     * The signature is `hex(HMAC_SHA256(botToken, vcfInfo))`, over `vcfInfo` exactly as received.
     * @throws IllegalArgumentException if [botToken] is blank.
     */
    public fun verify(botToken: String): Boolean {
        Hmac.requireToken(botToken)
        val vcf = vcfInfo ?: return false
        return Hmac.matches(Hmac.hex(botToken.toByteArray(), vcf), hash)
    }

    /**
     * The first phone number (`TEL`) of [vcfInfo] as `+` and its digits, e.g. `+79991234567`; `null`
     * if there is none. The number is not verified: see [verify].
     */
    public fun phoneNumber(): String? =
        vcfValue("TEL")?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }?.let { "+$it" }

    /** The formatted name (`FN`) of [vcfInfo]; `null` if there is none. */
    public fun fullName(): String? = vcfValue("FN")?.trim()?.takeIf { it.isNotEmpty() }

    /** The value of the first line of [vcfInfo] named [name], like `TEL;TYPE=cell:79991234567` or `item1.TEL:...`. */
    private fun vcfValue(name: String): String? = vcfInfo?.lineSequence()
        ?.firstOrNull { line -> line.substringBefore(':').substringBefore(';').substringAfterLast('.').equals(name, ignoreCase = true) }
        ?.substringAfter(':', "")
}
