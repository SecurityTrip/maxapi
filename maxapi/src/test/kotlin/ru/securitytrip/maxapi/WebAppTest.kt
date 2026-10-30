package ru.securitytrip.maxapi

import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.MaxLimits
import ru.securitytrip.maxapi.dsl.Buttons
import ru.securitytrip.maxapi.model.attachment.ContactAttachment
import ru.securitytrip.maxapi.model.attachment.ContactAttachmentPayload
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.MessageBody
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.webapp.MaxLinks
import ru.securitytrip.maxapi.webapp.WebAppChat
import ru.securitytrip.maxapi.webapp.WebAppData
import ru.securitytrip.maxapi.webapp.WebAppDataException
import ru.securitytrip.maxapi.webapp.WebAppDataException.Reason
import ru.securitytrip.maxapi.webapp.WebAppPhone
import ru.securitytrip.maxapi.webapp.WebAppUser
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signatures of mini apps and contacts. Every vector is signed here with the formula of the Max
 * documentation, computed with `javax.crypto` directly rather than with the library code.
 */
class WebAppTest {
    private val token = "test-bot-token-1234567890"
    private val authDate = 1_771_409_719L
    private val now: Instant = Instant.ofEpochSecond(authDate + 60)

    // ------------------------------------------------------------ the formulas, written out

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** hex(HMAC_SHA256(HMAC_SHA256("WebAppData", botToken), launchParams)). */
    private fun initDataHash(launchParams: String, botToken: String = token): String =
        hex(hmac(hmac("WebAppData".toByteArray(), botToken), launchParams))

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    /** `initData` as the mini app gets it: URL-encoded fields in any order, signed over the sorted decoded ones. */
    private fun initData(fields: Map<String, String>, botToken: String = token): String {
        val launchParams = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        val hash = initDataHash(launchParams, botToken)
        return (fields.entries.map { "${enc(it.key)}=${enc(it.value)}" } + "hash=$hash").joinToString("&")
    }

    private val userJson = """{"id":67890,"first_name":"Max","last_name":"User","username":null,"language_code":"ru","photo_url":null}"""

    private val fields = linkedMapOf(
        "user" to userJson,
        "chat" to """{"id":12345,"type":"DIALOG"}""",
        "query_id" to "4c0ab423-342b-4e45-aea4-2747dbc500cd",
        "auth_date" to authDate.toString(),
        "ip" to "192.168.0.1",
        "start_param" to "promo_summer",
    )

    private fun rejected(reason: Reason, initData: String, maxAge: Duration = WebAppData.DEFAULT_MAX_AGE): WebAppDataException {
        val e = assertFailsWith<WebAppDataException> { WebAppData.validate(initData, token, maxAge, now) }
        assertEquals(reason, e.reason, e.message)
        assertFalse(token in e.message.orEmpty())
        return e
    }

    // ------------------------------------------------------------ initData

    @Test
    fun `the example of the documentation`() {
        // https://dev.max.ru/docs/webapps/validation: the URL fragment holds WebAppData encoded once more;
        // window.WebApp.initData is that value decoded once. The documentation prints the prepared lines.
        val fragmentValue = "chat%3D%257B%2522id%2522%253A12345%252C%2522type%2522%253A%2522DIALOG%2522%257D%26ip%3D192.168.0.1" +
            "%26user%3D%257B%2522id%2522%253A67890%252C%2522first_name%2522%253A%2522Max%2522%252C%2522last_name%2522%253A%2522User" +
            "%2522%252C%2522username%2522%253Anull%252C%2522language_code%2522%253A%2522ru%2522%252C%2522photo_url%2522%253Anull%257D" +
            "%26query_id%3D4c0ab423-342b-4e45-aea4-2747dbc500cd%26auth_date%3D1771409719"
        val prepared = "auth_date=1771409719\nchat={\"id\":12345,\"type\":\"DIALOG\"}\nip=192.168.0.1\n" +
            "query_id=4c0ab423-342b-4e45-aea4-2747dbc500cd\n" +
            "user={\"id\":67890,\"first_name\":\"Max\",\"last_name\":\"User\",\"username\":null,\"language_code\":\"ru\",\"photo_url\":null}"
        val initData = URLDecoder.decode(fragmentValue, Charsets.UTF_8) + "&hash=" + initDataHash(prepared)

        val data = WebAppData.validate(initData, token, WebAppData.DEFAULT_MAX_AGE, now)
        assertEquals(WebAppUser(67890, "Max", "User", null, "ru", null), data.user)
        assertEquals(WebAppChat(12345, ChatType.DIALOG), data.chat)
        assertEquals("4c0ab423-342b-4e45-aea4-2747dbc500cd", data.queryId)
        assertEquals(Instant.ofEpochSecond(authDate), data.authDate)
        assertEquals("192.168.0.1", data.ip)
        assertNull(data.startParam)
        assertEquals(initDataHash(prepared), data.hash)
        assertEquals(setOf("auth_date", "chat", "ip", "query_id", "user"), data.fields.keys)
        assertTrue(WebAppData.verifySignature(initData, token))
    }

    @Test
    fun `valid data is parsed into fields`() {
        val data = WebAppData.validate(initData(fields + ("extra" to "kept")), token, Duration.ofHours(1), now)
        assertEquals(67890, data.user?.userId)
        assertEquals("Max", data.user?.firstName)
        assertEquals("promo_summer", data.startParam)
        assertEquals("kept", data.fields["extra"])
        assertEquals(userJson, data.fields["user"])
        assertFalse("hash" in data.fields)
    }

    @Test
    fun `values with reserved characters, Cyrillic and emoji are decoded once`() {
        val user = """{"id":1,"first_name":"Том & Джерри = друзья","last_name":"50% 😀","username":"a+b","language_code":"ru","photo_url":"https://i.example/a.png?x=1&y=2"}"""
        val initData = initData(fields + ("user" to user) + ("start_param" to "a%2Fb"))
        assertContains(initData, "%26") // the & inside the name is encoded, not a separator
        val data = WebAppData.validate(initData, token, Duration.ofHours(1), now)
        assertEquals("Том & Джерри = друзья", data.user?.firstName)
        assertEquals("50% 😀", data.user?.lastName)
        assertEquals("a+b", data.user?.username)
        assertEquals("https://i.example/a.png?x=1&y=2", data.user?.photoUrl)
        // Decoded once: %2F of the value stays as the user typed it.
        assertEquals("a%2Fb", data.startParam)
    }

    @Test
    fun `plus is read as plus, and as a space when that is what was signed`() {
        val plus = initData(mapOf("auth_date" to "$authDate", "q" to "a+b")).replace("q=a%2Bb", "q=a+b")
        assertEquals("a+b", WebAppData.validate(plus, token, Duration.ofHours(1), now).fields["q"])
        val space = initData(mapOf("auth_date" to "$authDate", "q" to "a b")).replace("q=a%20b", "q=a+b")
        assertEquals("a b", WebAppData.validate(space, token, Duration.ofHours(1), now).fields["q"])
        rejected(Reason.INVALID_SIGNATURE, space.replace("q=a+b", "q=a+c"))
    }

    @Test
    fun `a changed or added field breaks the signature`() {
        val valid = initData(fields)
        rejected(Reason.INVALID_SIGNATURE, valid.replace("4c0ab423", "4c0ab424"))
        rejected(Reason.INVALID_SIGNATURE, valid.replace("%22id%22%3A67890", "%22id%22%3A67891"))
        rejected(Reason.INVALID_SIGNATURE, "$valid&admin=true")
        rejected(Reason.INVALID_SIGNATURE, valid.substringBefore("&hash=") + "&hash=" + "0".repeat(64))
        // Signed with another bot's token.
        rejected(Reason.INVALID_SIGNATURE, initData(fields, botToken = "another-token"))
        assertFalse(WebAppData.verifySignature(valid, "another-token"))
    }

    @Test
    fun `hash must appear exactly once`() {
        val valid = initData(fields)
        val hash = valid.substringAfter("&hash=")
        rejected(Reason.MISSING_HASH, valid.substringBefore("&hash="))
        rejected(Reason.MISSING_HASH, "")
        rejected(Reason.DUPLICATE_HASH, "$valid&hash=$hash")
        rejected(Reason.DUPLICATE_HASH, "hash=$hash&$valid")
        assertFalse(WebAppData.verifySignature("$valid&hash=$hash", token))
        // The hash is case-insensitive hex.
        assertEquals(67890, WebAppData.validate(valid.replace(hash, hash.uppercase()), token, Duration.ofHours(1), now).user?.userId)
    }

    @Test
    fun `old data is rejected, signature first`() {
        val valid = initData(fields)
        val e = rejected(Reason.EXPIRED, valid, maxAge = Duration.ofSeconds(59))
        assertContains(e.message.orEmpty(), "PT59S")
        assertEquals(67890, WebAppData.validate(valid, token, Duration.ofSeconds(60), now).user?.userId)
        // Without a fixed clock the example from 2026-02 is long expired.
        assertEquals(Reason.EXPIRED, assertFailsWith<WebAppDataException> { WebAppData.validate(valid, token) }.reason)
        // A forged old hash is reported as forged, not as old.
        rejected(Reason.INVALID_SIGNATURE, valid.replace("4c0ab423", "4c0ab424"), maxAge = Duration.ZERO)
        // verifySignature ignores the age.
        assertTrue(WebAppData.verifySignature(valid, token))
    }

    @Test
    fun `fresh data passes with the defaults`() {
        val fresh = initData(fields + ("auth_date" to Instant.now().epochSecond.toString()))
        assertEquals(12345, WebAppData.validate(fresh, token).chat?.id)
        assertEquals(12345, WebAppData.validate(fresh, token, Duration.ofMinutes(5)).chat?.id)
    }

    @Test
    fun `malformed data`() {
        rejected(Reason.MALFORMED, initData(fields) + "&user=x")
        rejected(Reason.MALFORMED, "a=%zz&hash=00")
        rejected(Reason.MALFORMED, initData(fields - "auth_date"))
        rejected(Reason.MALFORMED, initData(fields + ("auth_date" to "yesterday")))
        rejected(Reason.MALFORMED, initData(fields + ("user" to "{not json")))
        rejected(Reason.MALFORMED, initData(fields + ("user" to """{"first_name":"No id"}""")))
        rejected(Reason.MALFORMED, initData(fields + ("chat" to """{"id":"12345"}""")))
        rejected(Reason.MALFORMED, initData(fields + ("chat" to """{"id":1.5}""")))
        assertFalse(WebAppData.verifySignature("a=%zz&hash=00", token))
    }

    @Test
    fun `optional fields may be missing`() {
        val minimal = initData(mapOf("auth_date" to "$authDate", "flag" to "", "chat" to """{"id":7,"type":"secret"}""", "user" to """{"id":5}""")) + "&&"
        val data = WebAppData.validate(minimal.replace("flag=", "flag"), token, Duration.ofHours(1), now)
        assertEquals(WebAppUser(5, "", null, null, null, null), data.user)
        assertEquals(WebAppChat(7, ChatType.UNKNOWN), data.chat)
        assertEquals("", data.fields["flag"])
        assertNull(data.queryId)
        assertNull(data.ip)
        val bare = WebAppData.validate(initData(mapOf("auth_date" to "$authDate")), token, Duration.ofHours(1), now)
        assertNull(bare.user)
        assertNull(bare.chat)
    }

    @Test
    fun `chat types are matched ignoring case`() {
        for ((sent, type) in listOf("DIALOG" to ChatType.DIALOG, "CHAT" to ChatType.CHAT, "channel" to ChatType.CHANNEL)) {
            val data = WebAppData.validate(initData(fields + ("chat" to """{"id":1,"type":"$sent"}""")), token, Duration.ofHours(1), now)
            assertEquals(type, data.chat?.type)
        }
    }

    @Test
    fun `wrong arguments`() {
        assertFailsWith<IllegalArgumentException> { WebAppData.validate(initData(fields), " ") }
        assertFailsWith<IllegalArgumentException> { WebAppData.verifySignature(initData(fields), "") }
        assertFailsWith<IllegalArgumentException> { WebAppData.validate(initData(fields), token, Duration.ofSeconds(-1)) }
    }

    // ------------------------------------------------------------ requestContact()

    /** hex(HMAC_SHA256(botToken, "authDate=...\nphone=...\nuserId=...")). */
    private fun phoneHash(authDate: String, phone: String, userId: Long): String =
        hex(hmac(token.toByteArray(), "authDate=$authDate\nphone=$phone\nuserId=$userId"))

    @Test
    fun `phone from requestContact`() {
        val hash = phoneHash("1771409719", "79991234567", 67890)
        val phone = WebAppPhone("+79991234567", "1771409719", hash)
        assertTrue(phone.verify(67890, token))
        assertTrue(phone.copy(phone = "79991234567").verify(67890, token))
        assertTrue(WebAppPhone.verify("+79991234567", "1771409719", 67890, hash.uppercase(), token))
        assertFalse(phone.verify(67891, token))
        assertFalse(phone.copy(phone = "+79991234568").verify(67890, token))
        assertFalse(phone.copy(authDate = "1771409720").verify(67890, token))
        assertFalse(phone.verify(67890, "another-token"))
        // Not the initData formula: no "WebAppData" key derivation.
        val derived = hex(hmac(hmac("WebAppData".toByteArray(), token), "authDate=1771409719\nphone=79991234567\nuserId=67890"))
        assertFalse(phone.copy(hash = derived).verify(67890, token))
        assertFailsWith<IllegalArgumentException> { phone.verify(67890, "") }
    }

    // ------------------------------------------------------------ request_contact button

    private val vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nTEL;TYPE=cell:79991234567\r\nFN:Max User\r\nEND:VCARD\r\n"

    @Test
    fun `contact from a request_contact button`() {
        val hash = hex(hmac(token.toByteArray(), vcf))
        val payload = ContactAttachmentPayload(vcf, hash)
        assertTrue(payload.verify(token))
        assertTrue(ContactAttachment(payload).verify(token))
        assertFalse(payload.verify("another-token"))
        assertFalse(payload.copy(vcfInfo = vcf.replace("\r\n", "\n")).verify(token))
        assertFalse(payload.copy(vcfInfo = vcf.replace("79991234567", "79990000000")).verify(token))
        assertFalse(payload.copy(hash = null).verify(token))
        assertFalse(payload.copy(vcfInfo = null).verify(token))
        assertFailsWith<IllegalArgumentException> { payload.verify(" ") }
    }

    @Test
    fun `contact parsed from an update keeps CRLF`() {
        val hash = hex(hmac(token.toByteArray(), vcf))
        val vcfJson = vcf.replace("\r\n", "\\r\\n")
        val update = MaxJson.parseUpdate(
            """{"update_type":"message_created","timestamp":1,"message":{"recipient":{"chat_id":1,"chat_type":"dialog"},
               "timestamp":1,"body":{"mid":"m","seq":1,"attachments":[{"type":"contact","payload":{"vcf_info":"$vcfJson","hash":"$hash"}}]}}}""",
        ) as MessageCreatedUpdate
        val body: MessageBody = update.message.body!!
        val contact = body.attachments!!.single() as ContactAttachment
        assertTrue(contact.verify(token))
    }

    // ------------------------------------------------------------ links and payloads

    @Test
    fun `bot links`() {
        assertEquals("https://max.ru/shop_bot", MaxLinks.bot("shop_bot"))
        assertEquals("https://max.ru/shop_bot", MaxLinks.bot("@shop_bot"))
        assertEquals("https://max.ru/shop_bot?start=ref-42", MaxLinks.botStart("shop_bot", "ref-42"))
        assertEquals("https://max.ru/b?start=a%20b%2Bc%26d%3De~f.g%2A", MaxLinks.botStart("b", "a b+c&d=e~f.g*"))
        assertEquals("https://max.ru/b?start=%D0%BF", MaxLinks.botStart("b", "п"))
        assertEquals(128, MaxLinks.botStart("b", "x".repeat(128)).substringAfter("start=").length)
        assertContains(assertFailsWith<IllegalArgumentException> { MaxLinks.botStart("b", "x".repeat(129)) }.message!!, "1 to 128")
        assertFailsWith<IllegalArgumentException> { MaxLinks.botStart("b", "") }
        for (name in listOf("", "a b", "a/b", "a?b", "бот")) {
            assertFailsWith<IllegalArgumentException>(name) { MaxLinks.bot(name) }
        }
    }

    @Test
    fun `mini app links`() {
        assertEquals("https://max.ru/MyShopBot?startapp", MaxLinks.miniApp("MyShopBot"))
        assertEquals("https://max.ru/MyShopBot?startapp", MaxLinks.miniApp("MyShopBot", ""))
        assertEquals("https://max.ru/MyShopBot?startapp=ref_user123_campaign-sale", MaxLinks.miniApp("MyShopBot", "ref_user123_campaign-sale"))
        val longest = "Az09_-".repeat(85) + "ab"
        assertEquals(longest, MaxLinks.miniApp("b", longest).substringAfter("startapp="))
        assertContains(assertFailsWith<IllegalArgumentException> { MaxLinks.miniApp("b", longest + "c") }.message!!, "at most 512")
        assertContains(assertFailsWith<IllegalArgumentException> { MaxLinks.miniApp("b", "a.b") }.message!!, "may hold only")
        assertFailsWith<IllegalArgumentException> { MaxLinks.miniApp("b", "a b") }
        assertFailsWith<IllegalArgumentException> { MaxLinks.miniApp("b", "я") }
    }

    @Test
    fun `share links`() {
        assertEquals("https://max.ru/:share?text=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82%20%D0%BC%D0%B8%D1%80", MaxLinks.share("Привет мир"))
        assertEquals("https://max.ru/:share?text=https%3A%2F%2Fexample.com", MaxLinks.share("https://example.com"))
        assertEquals("https://max.ru/:share?text=%F0%9F%9A%80%20MAX", MaxLinks.share("🚀 MAX"))
        assertFailsWith<IllegalArgumentException> { MaxLinks.share("") }
    }

    @Test
    fun `open_app payloads`() {
        assertTrue(MaxLimits.isValidOpenAppPayload(""))
        assertTrue(MaxLimits.isValidOpenAppPayload("Az09_-"))
        assertTrue(MaxLimits.isValidOpenAppPayload("a".repeat(512)))
        assertFalse(MaxLimits.isValidOpenAppPayload("a".repeat(513)))
        assertFalse(MaxLimits.isValidOpenAppPayload("a=b"))
        MaxLimits.checkOpenAppPayload("ok")
        assertContains(assertFailsWith<IllegalArgumentException> { MaxLimits.checkOpenAppPayload("a b") }.message!!, "Mini app payload may hold only")
        assertContains(assertFailsWith<IllegalArgumentException> { Buttons.openApp("Open", "bot", "a".repeat(513)) }.message!!, "at most 512")
        assertFailsWith<IllegalArgumentException> { Buttons.openApp("Open", "bot", "a&b") }
        assertEquals("ok", Buttons.openApp("Open", "bot", "ok").payload)
    }
}
