package ru.securitytrip.maxapi.webapp

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 and the constant-time comparison shared by all signatures of Max. */
internal object Hmac {
    fun sha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hex(key: ByteArray, data: String): String = HexFormat.of().formatHex(sha256(key, data))

    /** Compares hex strings in constant time; the case of [received] does not matter. */
    fun matches(expectedHex: String, received: String?): Boolean =
        received != null && MessageDigest.isEqual(expectedHex.toByteArray(), received.lowercase().toByteArray())

    fun requireToken(botToken: String) {
        require(botToken.isNotBlank()) { "botToken must not be blank" }
    }
}

/** The signature of mini app `initData`: https://dev.max.ru/docs/webapps/validation. */
internal object InitDataSignature {
    const val HASH = "hash"
    private val WEB_APP_DATA = "WebAppData".toByteArray()

    /**
     * Returns the decoded fields of [initData], `hash` included, if its signature is valid.
     *
     * Values are percent-decoded once, as the documentation does with `decodeURIComponent`. If that
     * does not match and the data has a `+`, it is decoded once more treating `+` as a space, as
     * form decoders do; a valid signature of either decoding was made by Max.
     */
    fun check(initData: String, botToken: String): Map<String, String> {
        Hmac.requireToken(botToken)
        val secret = Hmac.sha256(WEB_APP_DATA, botToken)
        val fields = parse(initData, plusAsSpace = false)
        if (signed(fields, secret)) return fields
        if ('+' in initData) parse(initData, plusAsSpace = true).let { if (signed(it, secret)) return it }
        throw WebAppDataException(WebAppDataException.Reason.INVALID_SIGNATURE, "initData signature does not match")
    }

    private fun signed(fields: Map<String, String>, secret: ByteArray): Boolean {
        val launchParams = fields.filterKeys { it != HASH }.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }
        return Hmac.matches(Hmac.hex(secret, launchParams), fields[HASH])
    }

    private fun parse(initData: String, plusAsSpace: Boolean): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        var hashes = 0
        for (pair in initData.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='), plusAsSpace)
            val value = decode(pair.substringAfter('=', ""), plusAsSpace)
            if (key == HASH) hashes++
            if (fields.put(key, value) != null && key != HASH) {
                throw WebAppDataException(WebAppDataException.Reason.MALFORMED, "Field $key appears in initData more than once")
            }
        }
        when {
            hashes == 0 -> throw WebAppDataException(WebAppDataException.Reason.MISSING_HASH, "initData has no hash")
            hashes > 1 -> throw WebAppDataException(WebAppDataException.Reason.DUPLICATE_HASH, "hash appears in initData $hashes times")
        }
        return fields
    }

    private fun decode(value: String, plusAsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusAsSpace) value else value.replace("+", "%2B"), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        throw WebAppDataException(WebAppDataException.Reason.MALFORMED, "initData is not a valid query string: ${e.message}", e)
    }
}
