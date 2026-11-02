package ru.securitytrip.maxapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.ChatQueues
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.Filters
import ru.securitytrip.maxapi.bot.InMemoryStateStorage
import ru.securitytrip.maxapi.bot.InMemoryUpdateDeduplicator
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.QueueOverflowException
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.VerifiedContact
import ru.securitytrip.maxapi.bot.WebhookHandler
import ru.securitytrip.maxapi.client.AnsweredCallbacks
import ru.securitytrip.maxapi.client.CallbackChats
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.MaxTls
import ru.securitytrip.maxapi.dsl.Html
import ru.securitytrip.maxapi.dsl.Markdown
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.attachment.ContactAttachment
import ru.securitytrip.maxapi.model.attachment.ContactAttachmentPayload
import ru.securitytrip.maxapi.model.attachment.PhotoAttachment
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentPayload
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.MessageBody
import ru.securitytrip.maxapi.model.message.Recipient
import ru.securitytrip.maxapi.model.markup.TextFormat
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.user.User
import ru.securitytrip.maxapi.webapp.WebAppPhone
import java.net.Socket
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The fixes of the review of 1.4.0: isolation of chats, safer defaults, contacts, formatting and TLS. */
class ReviewFixesTest {
    private fun text(update: Update): String? = (update as MessageCreatedUpdate).message.body?.text

    // ------------------------------------------------------------ chat queues

    @Test
    fun `chat queues keep the order of a chat and limit the waiting updates`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handled = CopyOnWriteArrayList<String?>()
        val queues = ChatQueues(this, parallelism = 1, capacity = 2) { update ->
            if (text(update) == "slow") gate.await()
            handled += text(update)
        }
        // Workers start only when this coroutine suspends: nothing is taken from the queues yet.
        assertTrue(queues.trySend(FakeApi.message("slow", chatId = 1)))
        assertTrue(queues.trySend(FakeApi.message("next", chatId = 1)))
        // All chats together have parallelism * capacity = 2 updates waiting.
        assertFalse(queues.trySend(FakeApi.message("refused", chatId = 2)))
        val done = CompletableDeferred<Unit>()
        val sent = launch { assertTrue(queues.send(FakeApi.message("other", chatId = 2), done)) }
        delay(50)
        assertFalse(sent.isCompleted, "send waits for room")
        assertEquals(emptyList(), handled)
        gate.complete(Unit)
        withTimeout(5_000) { done.await() }
        assertEquals(listOf<String?>("slow", "next", "other"), handled)

        queues.close()
        val dropped = CompletableDeferred<Unit>()
        assertFalse(queues.send(FakeApi.message("late", chatId = 3), dropped))
        assertTrue(dropped.isCompleted)
    }

    @Test
    fun `a full queue of a chat refuses its updates only`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val queues = ChatQueues(this, parallelism = 4, capacity = 1) { gate.await() }
        assertTrue(queues.trySend(FakeApi.message("a", chatId = 1)))
        assertFalse(queues.trySend(FakeApi.message("b", chatId = 1)))
        assertTrue(queues.trySend(FakeApi.message("c", chatId = 2)))
        val noChat = MessageCreatedUpdate(1, Message(User(7, "U"), Recipient(), 1, body = MessageBody("mid.d", text = "d")))
        assertTrue(queues.trySend(noChat))
        assertEquals(7, ChatQueues.keyOf(noChat))
        assertEquals(0, ChatQueues.keyOf(MaxJson.parseUpdate("""{"update_type":"brand_new","timestamp":1}""")))
        gate.complete(Unit)
    }

    @Test
    fun `a stuck handler holds up only its own chat in long polling`(): Unit = runBlocking {
        FakeApi().use { api ->
            val page = (listOf("stuck", "a1", "a2", "a3", "a4").map { 1L to it } + (2L to "b"))
                .joinToString(",") { (chat, text) -> """{"update_type":"message_created","timestamp":1,"message":${messageJson(chat, text, "mid.$text")}}""" }
            var polls = 0
            api.respond = { call ->
                when (call.path) {
                    "/subscriptions" -> FakeApi.Reply(200, """{"subscriptions":[]}""")
                    "/updates" -> FakeApi.Reply(200, if (polls++ == 0) """{"marker":1,"updates":[$page]}""" else """{"marker":1,"updates":[]}""")
                    else -> null
                }
            }
            val bot = MaxBot(api.client, BotConfig(queueCapacity = 2, pollTimeoutSeconds = 0, minPollInterval = Duration.ofMillis(5)))
            val gate = CompletableDeferred<Unit>()
            val handled = CopyOnWriteArrayList<String?>()
            val errors = CopyOnWriteArrayList<Throwable>()
            bot.onError { e, _ -> errors += e }
            bot.onMessage {
                if (text == "stuck") gate.await()
                handled += text
            }
            bot.start()
            withTimeout(5_000) { while ("b" !in handled) delay(10) }
            assertEquals(listOf<String?>("b"), handled, "chat 2 is handled while chat 1 is stuck")
            val overflow = assertIs<QueueOverflowException>(errors.first())
            assertEquals(2, overflow.capacity)
            assertEquals(1, overflow.update.chatId)
            assertTrue(overflow.message!!.contains("message_created"))
            gate.complete(Unit)
            withTimeout(5_000) { while (handled.size + errors.size < 6) delay(10) }
            val chat1 = handled.filter { it != "b" }
            assertEquals(chat1.sortedBy { listOf("stuck", "a1", "a2", "a3", "a4").indexOf(it) }, chat1, "in order")
            bot.stop()
            bot.close()
        }
    }

    @Test
    fun `webhook updates handled in the background keep the order of a chat`(): Unit = runBlocking {
        FakeApi().use { api ->
            val bot = MaxBot(api.client, BotConfig(queueCapacity = 1))
            val handled = CopyOnWriteArrayList<String?>()
            val gate = CompletableDeferred<Unit>()
            bot.onMessage {
                if (text == "wait") gate.await()
                handled += text
            }
            val handler = bot.webhookHandler(WebhookHandler.secretFor(TOKEN))
            val secret = WebhookHandler.secretFor(TOKEN)
            fun body(text: String, chat: Long) =
                """{"update_type":"message_created","timestamp":1,"message":${messageJson(chat, text, "mid.$text")}}"""
            assertTrue(handler.handleAsync(body("wait", 1), secret))
            // The chat has one update waiting at most: one of the next two is refused, so that Max redelivers it.
            val refused = assertFailsWith<IllegalStateException> {
                repeat(2) { handler.handleAsync(body("more$it", 1), secret) }
            }
            assertTrue(refused.message!!.contains("chat 1"))
            assertTrue(handler.handleAsync(body("x", 2), secret))
            withTimeout(5_000) { while ("x" !in handled) delay(10) }
            gate.complete(Unit)
            withTimeout(5_000) { while (handled.size < 3) delay(10) }
            assertEquals(listOf<String?>("x", "wait"), handled.take(2))
            bot.close()
        }
    }

    // ------------------------------------------------------------ defaults

    @Test
    fun `defaults limit handlers and answer button presses`() {
        val config = BotConfig()
        assertEquals(64, config.parallelism)
        assertEquals(Duration.ofMinutes(1), config.handlerTimeout)
        assertTrue(config.autoAnswerCallbacks)
        assertFalse(BotConfig.builder().autoAnswerCallbacks(false).build().autoAnswerCallbacks)
        assertEquals(64, BotConfig.builder().build().parallelism)
        val constructors = BotConfig::class.java.constructors.map { it.parameterCount }
        assertTrue(constructors.containsAll(listOf(11, 12, 13, 14)), "the constructors of earlier versions are kept: $constructors")
        val old = BotConfig::class.java.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Duration::class.java, Duration::class.java, ru.securitytrip.maxapi.bot.StateStorage::class.java, Duration::class.java,
            ru.securitytrip.maxapi.bot.UpdateDeduplicator::class.java, ru.securitytrip.maxapi.bot.MarkerStore::class.java,
            ru.securitytrip.maxapi.events.MaxEventListener::class.java, ru.securitytrip.maxapi.bot.ChatStore::class.java,
            ru.securitytrip.maxapi.bot.SuspendUpdateDeduplicator::class.java,
        ).newInstance(2, 3, 4, 5, Duration.ofMillis(300), Duration.ofSeconds(1), InMemoryStateStorage(), null, null, null,
            ru.securitytrip.maxapi.events.MaxEventListener.NONE, null, null)
        assertEquals(2, old.parallelism)
        assertTrue(old.autoAnswerCallbacks)
    }

    // ------------------------------------------------------------ callbacks

    @Test
    fun `button presses nobody answered get an empty answer`(): Unit = runBlocking {
        FakeApi().use { api ->
            val bot = MaxBot(api.client, BotConfig(deduplicator = InMemoryUpdateDeduplicator()))
            val errors = CopyOnWriteArrayList<Throwable>()
            bot.onError { e, _ -> errors += e }
            bot.onCallback("answered") { answer("Thanks") }
            bot.onCallback("direct") { client.answerOnCallback(callback.callbackId, "Direct") }
            bot.onCallback("silent") { }
            bot.onCallback("boom") { error("failed") }

            fun answers() = api.calls.filter { it.path == "/answers" }
            bot.processUpdate(FakeApi.press("answered"))
            bot.processUpdate(FakeApi.press("direct").let { it.copy(callback = it.callback.copy(callbackId = "cb2")) })
            assertEquals(listOf<String?>("Thanks", "Direct"), api.notifications)

            bot.processUpdate(FakeApi.press("silent").let { it.copy(callback = it.callback.copy(callbackId = "cb3")) })
            bot.processUpdate(FakeApi.press("boom").let { it.copy(callback = it.callback.copy(callbackId = "cb4")) })
            bot.processUpdate(FakeApi.press("nobody").let { it.copy(callback = it.callback.copy(callbackId = "cb5")) })
            assertEquals(listOf("cb", "cb2", "cb3", "cb4", "cb5"), answers().map { it.query["callback_id"] })
            assertEquals("{}", answers()[2].body)
            assertEquals("failed", errors.single().message)

            // A repeated delivery is dropped, and not answered again.
            bot.processUpdate(FakeApi.press("nobody").let { it.copy(callback = it.callback.copy(callbackId = "cb5")) })
            assertEquals(5, answers().size)

            // A failed answer is only logged.
            api.respond = { if (it.path == "/answers") FakeApi.Reply(400, """{"code":"bad","message":"expired"}""") else null }
            bot.processUpdate(FakeApi.press("silent").let { it.copy(callback = it.callback.copy(callbackId = "cb6")) })
            assertEquals(6, answers().size)
            assertEquals(1, errors.size)
            assertEquals(0, api.client.transport.answeredCallbacks.size)
        }
    }

    @Test
    fun `automatic answers can be turned off`(): Unit = runBlocking {
        FakeApi().use { api ->
            val bot = MaxBot(api.client, BotConfig(autoAnswerCallbacks = false))
            bot.onCallback("silent") { }
            bot.processUpdate(FakeApi.press("silent"))
            assertTrue(api.calls.none { it.path == "/answers" })
            assertFalse(api.client.transport.answeredCallbacks.enabled)
            api.client.answerOnCallback("cb", "not tracked")
            assertEquals(0, api.client.transport.answeredCallbacks.size)
        }
    }

    @Test
    fun `answered button presses are remembered within a bound`() {
        val answered = AnsweredCallbacks()
        answered.add("ignored")
        assertEquals(0, answered.size)
        answered.enabled = true
        answered.add(null)
        repeat(CallbackChats.MAX_CALLBACKS + 1) { answered.add("cb$it") }
        assertEquals(CallbackChats.MAX_CALLBACKS, answered.size)
        assertFalse(answered.remove("cb0"))
        assertTrue(answered.remove("cb1"))
    }

    // ------------------------------------------------------------ username

    @Test
    fun `an update survives a failed getMyInfo`(): Unit = runBlocking {
        FakeApi().use { api ->
            api.respond = { if (it.path == "/me") FakeApi.Reply(503, """{"code":"down","message":"down"}""") else null }
            val bot = MaxBot(api.client, BotConfig(autoAnswerCallbacks = false))
            val errors = CopyOnWriteArrayList<Throwable>()
            val handled = CopyOnWriteArrayList<String>()
            bot.onError { e, _ -> errors += e }
            bot.onCommand("start") { handled += "command" }
            bot.onMessage { handled += "message: $text" }
            bot.processUpdate(FakeApi.message("/start@my_bot"))
            assertEquals(listOf("message: /start@my_bot"), handled)
            assertIs<MaxApiException>(errors.single())
            assertNull(bot.username)
        }
    }

    // ------------------------------------------------------------ dialog states

    @Test
    fun `states in memory expire and are bounded`(): Unit = runBlocking {
        var now = 0L
        val storage = InMemoryStateStorage(maxSize = 2, ttl = Duration.ofSeconds(10)).apply { clock = { now } }
        val a = StateKey(1, 1)
        val b = StateKey(2, 2)
        val c = StateKey(3, 3)
        storage.set(a, DialogState("a"))
        storage.set(b, DialogState("b"))
        assertEquals("a", storage.get(a)?.name) // a is now the most recently used
        storage.set(c, DialogState("c"))
        assertEquals(2, storage.size)
        assertNull(storage.get(b), "the least recently used state is dropped")
        now = Duration.ofSeconds(10).toNanos()
        assertNull(storage.get(a), "expired")
        assertEquals(1, storage.size)
        storage.set(c, null)
        assertEquals(0, storage.size)

        val forever = InMemoryStateStorage(ttl = null).apply { clock = { now } }
        forever.set(a, DialogState("x"))
        now += Duration.ofDays(10_000).toNanos()
        assertEquals("x", forever.get(a)?.name)
        assertEquals(InMemoryStateStorage.DEFAULT_MAX_SIZE, InMemoryStateStorage().maxSize)
        assertEquals(Duration.ofDays(1), InMemoryStateStorage().ttl)
        assertFailsWith<IllegalArgumentException> { InMemoryStateStorage(maxSize = 0) }
        assertFailsWith<IllegalArgumentException> { InMemoryStateStorage(ttl = Duration.ZERO) }
    }

    // ------------------------------------------------------------ mini app phone

    private fun hmacHex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return HexFormat.of().formatHex(mac.doFinal(data.toByteArray()))
    }

    @Test
    fun `phone numbers of mini apps expire`() {
        val issued = Instant.parse("2026-11-01T10:00:00Z")
        val authDate = issued.epochSecond.toString()
        val hash = hmacHex(TOKEN, "authDate=$authDate\nphone=79990001122\nuserId=7")
        val phone = WebAppPhone("+79990001122", authDate, hash)
        assertTrue(phone.verify(7, TOKEN, now = issued.plusSeconds(3600)))
        assertFalse(phone.verify(7, TOKEN, now = issued.plusSeconds(3601)))
        assertTrue(phone.verify(7, TOKEN, maxAge = null, now = issued.plusSeconds(1_000_000)))
        assertFalse(phone.verify(8, TOKEN, now = issued))
        val unparsable = hmacHex(TOKEN, "authDate=soon\nphone=79990001122\nuserId=7")
        assertFalse(WebAppPhone.verify("79990001122", "soon", 7, unparsable, TOKEN))
        assertTrue(WebAppPhone.verify("79990001122", "soon", 7, unparsable, TOKEN, null))
        assertFailsWith<IllegalArgumentException> { phone.verify(7, TOKEN, Duration.ofSeconds(-1)) }
    }

    // ------------------------------------------------------------ contacts

    private val vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nTEL;TYPE=cell:79991234567\r\nFN:Max User\r\nEND:VCARD\r\n"

    private fun contact(vcfInfo: String? = vcf, hash: String? = vcfInfo?.let { hmacHex(TOKEN, it) }, owner: Long? = 1) =
        ContactAttachment(ContactAttachmentPayload(vcfInfo, hash, owner?.let { User(it, "Max") }))

    private fun messageWith(vararg attachments: ru.securitytrip.maxapi.model.attachment.Attachment, sender: Long? = 1) = Message(
        sender?.let { User(it, "Max") }, Recipient(chatId = 100, chatType = ChatType.DIALOG), 1,
        body = MessageBody("mid.c", attachments = attachments.toList()),
    )

    @Test
    fun `the sender's own signed contact is verified`() {
        val photo = PhotoAttachment(PhotoAttachmentPayload(1, "t", "u"))
        val verified = VerifiedContact.of(messageWith(photo, contact()), TOKEN)!!
        assertEquals(1, verified.userId)
        assertEquals("+79991234567", verified.phone)
        assertEquals("Max User", verified.name)
        assertEquals(vcf, verified.contact.vcfInfo)

        assertNull(VerifiedContact.of(messageWith(contact(owner = 2)), TOKEN), "someone else's contact")
        assertNull(VerifiedContact.of(messageWith(contact(owner = null)), TOKEN), "a contact without a Max user")
        assertNull(VerifiedContact.of(messageWith(contact(), sender = null), TOKEN), "a message of a channel")
        assertNull(VerifiedContact.of(messageWith(contact(hash = "00")), TOKEN), "a wrong signature")
        assertNull(VerifiedContact.of(messageWith(contact(vcfInfo = "BEGIN:VCARD\r\nFN:X\r\nEND:VCARD")), TOKEN), "no phone")
        assertNull(VerifiedContact.of(messageWith(photo), TOKEN))
        assertNull(VerifiedContact.of(Message(User(1, "Max"), Recipient(chatId = 1), 1), TOKEN))
    }

    @Test
    fun `phone numbers and names are read from vCards`() {
        fun payload(vcf: String?) = ContactAttachmentPayload(vcf)
        assertEquals("+79991234567", payload("item1.TEL;type=CELL:+7 (999) 123-45-67\nFN:  ").phoneNumber())
        assertNull(payload("item1.TEL;type=CELL:+7 (999) 123-45-67\nFN:  ").fullName())
        assertNull(payload("TEL:none").phoneNumber())
        assertNull(payload("TEL").phoneNumber())
        assertNull(payload(null).phoneNumber())
        assertNull(payload(null).fullName())
        assertEquals("Ann", payload("fn:Ann").fullName())
    }

    @Test
    fun `handlers read the shared contact`(): Unit = runBlocking {
        FakeApi().use { api ->
            val bot = MaxBot(api.client)
            val phones = CopyOnWriteArrayList<String>()
            bot.onMessage(Filters.hasVerifiedContact()) { phones += sharedContact()!!.phone }
            bot.onMessage { phones += "other" }
            bot.processUpdate(MessageCreatedUpdate(1, messageWith(contact())))
            bot.processUpdate(MessageCreatedUpdate(1, messageWith(contact(owner = 5))))
            assertEquals(listOf("+79991234567", "other"), phones)
        }
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `user text is escaped in HTML and markdown`() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;", Html.escape("<a href=\"x\">Tom & Jerry's</a>"))
        assertEquals("plain", Html.escape("plain"))
        assertEquals("\\*\\*bold\\*\\* \\[link\\]\\(url\\) a\\_b", Markdown.escape("**bold** [link](url) a_b"))
        val html = Html.builder()
            .text("<1> ").bold("b").italic("i").underline("u").strikethrough("s").code("c").highlighted("h")
            .newline().quote("q").link("<x>", "https://e.com/?a=1&b=\"2\"").mention("Ann", 42).raw("<br>")
            .build()
        assertEquals(
            "&lt;1&gt; <b>b</b><i>i</i><u>u</u><s>s</s><code>c</code><mark>h</mark>\n<blockquote>q</blockquote>" +
                "<a href=\"https://e.com/?a=1&amp;b=&quot;2&quot;\">&lt;x&gt;</a><a href=\"max://user/42\">Ann</a><br>",
            html,
        )
        val body = message { html { text("Hi, "); bold("<script>") } }
        assertEquals("Hi, <b>&lt;script&gt;</b>", body.text)
        assertEquals(TextFormat.HTML, body.format)
        assertEquals("<b>x</b>", Html.builder().bold("x").toString())
    }

    // ------------------------------------------------------------ client settings

    @Test
    fun `the base URL must use https away from the local host`() {
        for (url in listOf("http://platform-api2.max.ru", "http://example.com/", "http://no-such-host.invalid/")) {
            val error = assertFailsWith<IllegalArgumentException>(url) { MaxClientConfig(TOKEN, baseUrl = url) }
            assertTrue(error.message!!.contains("https"))
        }
        assertFailsWith<IllegalArgumentException> { MaxClientConfig(TOKEN, baseUrl = "ftp://x") }
        for (url in listOf("http://localhost:1/", "http://bot.localhost/", "http://fake.test/", "http://127.0.0.1:2/", "http://[::1]:3/", "https://example.com")) {
            assertEquals(url, MaxClientConfig(TOKEN, baseUrl = url).baseUrl)
        }
    }

    @Test
    fun `a builder of an OkHttpClient trusts the root CA and is owned by the client`() {
        val config = MaxClientConfig.builder(TOKEN).httpClient(OkHttpClient.Builder().callTimeout(Duration.ofSeconds(7))).build()
        assertTrue(config.ownsHttpClient)
        val client = MaxClient(config)
        assertSame(MaxTls.trustManager, client.transport.http.x509TrustManager)
        assertEquals(7_000, client.transport.http.callTimeoutMillis)
        client.close()
        assertTrue(client.transport.http.dispatcher.executorService.isShutdown)

        val untrusting = MaxClientConfig.builder(TOKEN).httpClient(OkHttpClient.Builder()).trustRussianRootCa(false).build()
        assertNotSame(MaxTls.trustManager, untrusting.httpClient!!.x509TrustManager)

        val own = OkHttpClient()
        val replaced = MaxClientConfig.builder(TOKEN).httpClient(OkHttpClient.Builder()).httpClient(own).build()
        assertSame(own, replaced.httpClient)
        assertFalse(replaced.ownsHttpClient)
        val rebuilt = MaxClientConfig.builder(TOKEN).httpClient(own).httpClient(OkHttpClient.Builder()).build()
        assertNotSame(own, rebuilt.httpClient)
        assertTrue(rebuilt.ownsHttpClient)
        rebuilt.httpClient!!.dispatcher.executorService.shutdown()
    }

    @Test
    fun `the Russian root CA is trusted for the hosts of Max only`() {
        val calls = CopyOnWriteArrayList<String>()
        fun recorder(name: String) = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) { calls += "$name client socket" }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) { calls += "$name server socket" }
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) { calls += "$name client engine" }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) { calls += "$name server engine" }
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { calls += "$name client" }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { calls += "$name server" }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(MaxTls.russianTrustedRootCa)
        }
        val manager = MaxTls.HostTrustManager(recorder("jvm"), recorder("max"))
        val chain = arrayOf(MaxTls.russianTrustedRootCa)
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        manager.checkServerTrusted(chain, "RSA", context.createSSLEngine("platform-api2.max.ru", 443))
        manager.checkServerTrusted(chain, "RSA", context.createSSLEngine("iu.oneme.ru", 443))
        manager.checkServerTrusted(chain, "RSA", context.socketFactory.createSocket())
        manager.checkServerTrusted(chain, "RSA", null as Socket?)
        manager.checkServerTrusted(chain, "RSA")
        manager.checkClientTrusted(chain, "RSA", null as Socket?)
        manager.checkClientTrusted(chain, "RSA", null as SSLEngine?)
        manager.checkClientTrusted(chain, "RSA")
        assertEquals(
            listOf(
                "max server engine", "jvm server engine", "jvm server socket", "jvm server socket", "max server",
                "jvm client socket", "jvm client engine", "jvm client",
            ),
            calls,
        )
        assertEquals(1, manager.acceptedIssuers.size)
        assertTrue(MaxTls.isMaxHost("max.ru"))
        assertTrue(MaxTls.isMaxHost("PLATFORM-API2.MAX.RU"))
        assertFalse(MaxTls.isMaxHost("evilmax.ru"))
        assertFalse(MaxTls.isMaxHost(null))
    }

    // ------------------------------------------------------------ webhook secret

    @Test
    fun `a webhook secret is derived from the token`() {
        val secret = WebhookHandler.secretFor(TOKEN)
        assertEquals(hmacHex(TOKEN, "max-webhook-secret"), secret)
        assertTrue(secret.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(secret, WebhookHandler.secretFor("other"))
        assertFailsWith<IllegalArgumentException> { WebhookHandler.secretFor(" ") }
    }

    // ------------------------------------------------------------ deduplication

    @Test
    fun `other updates of the same type and time keep different keys`() {
        fun started(payload: String) = MaxJson.parseUpdate(
            """{"update_type":"bot_started","timestamp":9,"chat_id":4,"user":{"user_id":5},"payload":"$payload"}""",
        )
        val key = ru.securitytrip.maxapi.bot.UpdateDeduplicator.keyOf(started("a"))
        assertTrue(key.matches(Regex("bot_started:9:[0-9a-f]{32}")), key)
        assertEquals(key, ru.securitytrip.maxapi.bot.UpdateDeduplicator.keyOf(started("a")))
        assertNotEquals(key, ru.securitytrip.maxapi.bot.UpdateDeduplicator.keyOf(started("b")))
    }
}
