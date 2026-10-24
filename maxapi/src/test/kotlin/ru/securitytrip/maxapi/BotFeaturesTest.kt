package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import ru.securitytrip.maxapi.bot.BlockingStateStorage
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.CallbackContext
import ru.securitytrip.maxapi.bot.CallbackData
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.Filter
import ru.securitytrip.maxapi.bot.Filters
import ru.securitytrip.maxapi.bot.Handler
import ru.securitytrip.maxapi.bot.InMemoryStateStorage
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.MessageContext
import ru.securitytrip.maxapi.bot.Middleware
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.UpdateContext
import ru.securitytrip.maxapi.bot.and
import ru.securitytrip.maxapi.bot.not
import ru.securitytrip.maxapi.bot.or
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.attachment.FileAttachment
import ru.securitytrip.maxapi.model.attachment.PhotoAttachment
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.user.BotCommand
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotFeaturesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private lateinit var bot: MaxBot
    private val log = CopyOnWriteArrayList<String>()
    private val errors = CopyOnWriteArrayList<Throwable>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
        bot = MaxBot(client)
        bot.onError { error, _ -> errors += error }
    }

    @AfterTest
    fun tearDown() {
        bot.close()
        client.close()
        server.close()
    }

    private fun message(
        text: String? = "hi",
        chatId: Long = 100,
        userId: Long = 1,
        chatType: String = "dialog",
        attachments: String? = null,
        locale: String? = null,
    ): Update = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1${locale?.let { ""","user_locale":"$it"""" }.orEmpty()},
            "message":{"sender":{"user_id":$userId},"recipient":{"chat_id":$chatId,"chat_type":"$chatType"},"timestamp":1,
            "body":{"mid":"mid.1","seq":1${text?.let { ""","text":"$it"""" }.orEmpty()}
            ${attachments?.let { ""","attachments":$it""" }.orEmpty()}}}}""",
    )

    private fun callback(payload: String?, userId: Long = 1, chatId: Long = 100): Update = MaxJson.parseUpdate(
        """{"update_type":"message_callback","timestamp":1,
            "callback":{"timestamp":1,"callback_id":"cb1"${payload?.let { ""","payload":"$it"""" }.orEmpty()},"user":{"user_id":$userId}},
            "message":{"recipient":{"chat_id":$chatId,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"m","seq":1}}}""",
    )

    private fun dialogEvent(type: String, locale: String?): Update = MaxJson.parseUpdate(
        """{"update_type":"$type","timestamp":1,"chat_id":5,"user":{"user_id":1}${locale?.let { ""","user_locale":"$it"""" }.orEmpty()}}""",
    )

    /** An update with neither chat nor user. */
    private val anonymous: Update = MaxJson.parseUpdate("""{"update_type":"something_new","timestamp":1}""")

    // ---------------------------------------------------------- middleware

    @Test
    fun `middleware runs around the handler in registration order`(): Unit = runBlocking {
        bot.use { ctx, next -> log += "outer>"; assertIs<MessageContext>(ctx); next(); log += "<outer" }
        bot.use { _, next -> log += "inner>"; next(); log += "<inner" }
        bot.onMessage { log += "handler" }
        bot.processUpdate(message())
        assertEquals(listOf("outer>", "inner>", "handler", "<inner", "<outer"), log)
    }

    @Test
    fun `middleware can skip the handler`(): Unit = runBlocking {
        bot.use { ctx, next -> if (ctx.user?.userId == 1L) next() else log += "denied" }
        bot.onMessage { log += "handled ${user?.userId}" }
        bot.processUpdate(message(userId = 1))
        bot.processUpdate(message(userId = 2))
        assertEquals(listOf("handled 1", "denied"), log)
    }

    @Test
    fun `middleware does not run when no handler matches`(): Unit = runBlocking {
        bot.use { _, next -> log += "middleware"; next() }
        bot.onCallback { log += "callback" }
        bot.processUpdate(message())
        assertEquals(emptyList(), log)
    }

    @Test
    fun `exceptions pass through middleware to the error handler`(): Unit = runBlocking {
        bot.use { _, next ->
            try {
                next()
            } finally {
                log += "finally"
            }
        }
        bot.onMessage { error("boom") }
        bot.processUpdate(message())
        assertEquals(listOf("finally"), log)
        assertEquals("boom", errors.single().message)
    }

    @Test
    fun `Java middleware runs blocking and can proceed or skip`(): Unit = runBlocking {
        bot.use(Middleware { ctx, next ->
            log += "java ${Thread.currentThread().name.contains("DefaultDispatcher")}"
            if (ctx.chatId == 100L) next.proceed()
        })
        bot.onMessage { log += "handler" }
        bot.processUpdate(message(chatId = 100))
        bot.processUpdate(message(chatId = 200))
        assertEquals(listOf("java true", "handler", "java true"), log)
    }

    // ------------------------------------------------------------- routers

    @Test
    fun `included routers keep the registration order`(): Unit = runBlocking {
        bot.onCommand("first") { log += "bot first" }
        bot.include(Router().onCommand("first") { log += "router first" }.onCommand("second") { log += "router second" })
        bot.onCommand("second") { log += "bot second" }
        bot.onMessage { log += "bot any" }
        bot.processUpdate(message("/first"))
        bot.processUpdate(message("/second"))
        bot.processUpdate(message("other"))
        assertEquals(listOf("bot first", "router second", "bot any"), log)
    }

    @Test
    fun `router middleware wraps only its own handlers, bot middleware wraps all`(): Unit = runBlocking {
        bot.use { _, next -> log += "bot"; next() }
        val admin = Router().use { _, next -> log += "admin"; next() }.onCommand("ban") { log += "ban" }
        val nested = Router().use { _, next -> log += "nested"; next() }.onCommand("deep") { log += "deep" }
        admin.include(nested)
        bot.include(admin)
        bot.onMessage { log += "any" }
        bot.processUpdate(message("/ban"))
        bot.processUpdate(message("/deep"))
        bot.processUpdate(message("text"))
        assertEquals(listOf("bot", "admin", "ban", "bot", "admin", "nested", "deep", "bot", "any"), log)
    }

    @Test
    fun `routers cannot include themselves`() {
        val a = Router()
        val b = Router().include(a)
        assertFailsWith<IllegalArgumentException> { a.include(a) }
        assertFailsWith<IllegalArgumentException> { a.include(b) }
        val c = Router().include(b)
        assertFailsWith<IllegalArgumentException> { a.include(c) }
        a.include(Router())
    }

    @Test
    fun `subscribed types include those of routers`() {
        bot.onCommand("x") { }
        bot.include(Router().onCallback { }.include(Router().onBotStarted { }))
        assertEquals(
            setOf(UpdateType.MESSAGE_CREATED, UpdateType.MESSAGE_CALLBACK, UpdateType.BOT_STARTED),
            bot.subscribedUpdateTypes(),
        )
        bot.include(Router().on<Update> { })
        assertNull(bot.subscribedUpdateTypes())
    }

    @Test
    fun `a router accepts every kind of handler`(): Unit = runBlocking {
        val router = Router()
            .onMessage({ it.text == "kotlin" }) { log += "filtered" }
            .onMessage(Handler { log += "java message" })
            .onCallback("exact") { log += "exact" }
            .onCallback(Handler { log += "java callback" })
            .onBotStarted(Handler { log += "java started" })
            .onUpdate(BotStartedUpdate::class.java, Handler { log += "never" })
        bot.include(router)
        bot.processUpdate(message("kotlin"))
        bot.processUpdate(message("other"))
        bot.processUpdate(callback("exact"))
        bot.processUpdate(callback("other"))
        bot.processUpdate(MaxJson.parseUpdate("""{"update_type":"bot_started","timestamp":1,"chat_id":1,"user":{"user_id":1}}"""))
        assertEquals(listOf("filtered", "java message", "exact", "java callback", "java started"), log)
    }

    // ------------------------------------------------------------ commands

    @Test
    fun `commands with descriptions are collected for the menu`() {
        bot.onCommand("/Start", "Start the bot") { }
        bot.onCommand("hidden") { }
        bot.include(
            Router()
                .onCommand("help", "Show help", Handler { })
                .onCommand("start", "Duplicate") { },
        )
        assertEquals(listOf(BotCommand("start", "Start the bot"), BotCommand("help", "Show help")), bot.commands())
        assertFailsWith<IllegalArgumentException> { bot.onCommand(" ", "blank") { } }
    }

    @Test
    fun `commands with descriptions are handled like other commands`(): Unit = runBlocking {
        bot.onCommand("start", "Start") { log += "start $commandArgs" }
        bot.onCommand("java", "Java", Handler { log += "java" })
        bot.processUpdate(message("/start ref"))
        bot.processUpdate(message("/JAVA"))
        assertEquals(listOf("start ref", "java"), log)
    }

    @Test
    fun `syncCommands sends the described commands`() {
        bot.onCommand("start", "Start") { }
        server.enqueue(json("""{"commands":[{"name":"start","description":"Start"}]}"""))
        val result = bot.syncCommandsBlocking()
        assertEquals(listOf(BotCommand("start", "Start")), result.commands)
        val request = server.take()
        assertEquals("PATCH", request.method)
        assertEquals("/me/commands", request.url.encodedPath)
        assertEquals("""{"commands":[{"name":"start","description":"Start"}]}""", request.bodyText)
    }

    // ------------------------------------------------------------- filters

    private fun <C> Filter<C>.on(update: Update, contextOf: (Update) -> C): Boolean = this(contextOf(update))

    private fun capture(update: Update): UpdateContext<*> {
        var captured: UpdateContext<*>? = null
        val probe = MaxBot(client)
        probe.onMessage { captured = this }
        probe.onCallback { captured = this }
        probe.on<Update> { captured = this }
        probe.processUpdateBlocking(update)
        return captured!!
    }

    private fun msg(update: Update) = capture(update) as MessageContext
    private fun cb(update: Update) = capture(update) as CallbackContext

    @Test
    fun `message filters`() {
        assertTrue(Filters.hasText().on(message("x"), ::msg))
        assertFalse(Filters.hasText().on(message(null), ::msg))

        assertTrue(Filters.text(Regex("\\d+")).on(message("order 42"), ::msg))
        assertFalse(Filters.text(Regex("^\\d+$")).on(message("order 42"), ::msg))
        assertFalse(Filters.text(Regex(".*")).on(message(null), ::msg))
        assertTrue(Filters.text(Pattern.compile("^ok$")).on(message("ok"), ::msg))

        assertTrue(Filters.textEquals("yes", "no").on(message("no"), ::msg))
        assertFalse(Filters.textEquals("yes").on(message("YES"), ::msg))
        assertTrue(Filters.textEqualsIgnoreCase("yes").on(message("YES"), ::msg))
        assertFalse(Filters.textEqualsIgnoreCase("yes").on(message(null), ::msg))

        assertTrue(Filters.textStartsWith("/admin").on(message("/admin ban"), ::msg))
        assertFalse(Filters.textStartsWith("/admin").on(message(null), ::msg))

        assertTrue(Filters.chatType(ChatType.CHAT, ChatType.CHANNEL).on(message(chatType = "chat"), ::msg))
        assertFalse(Filters.chatType(ChatType.CHAT).on(message(chatType = "dialog"), ::msg))

        val photo = """[{"type":"image","payload":{"photo_id":1,"token":"t","url":"u"}}]"""
        assertTrue(Filters.hasAttachment<PhotoAttachment>().on(message(attachments = photo), ::msg))
        assertFalse(Filters.hasAttachment(FileAttachment::class.java).on(message(attachments = photo), ::msg))
        assertFalse(Filters.hasAttachment<PhotoAttachment>().on(message(), ::msg))
    }

    @Test
    fun `callback and generic filters`() {
        assertTrue(Filters.payloadStartsWith("buy:").on(callback("buy:1"), ::cb))
        assertFalse(Filters.payloadStartsWith("buy:").on(callback(null), ::cb))

        assertTrue(Filters.fromUsers(1, 2).on(callback("p", userId = 2), ::cb))
        assertFalse(Filters.fromUsers(1).on(message(userId = 3), ::msg))
        assertTrue(Filters.inChats(100).on(message(chatId = 100), ::msg))
        assertFalse(Filters.inChats(100).on(anonymous, ::capture))
        assertFalse(Filters.fromUsers(1).on(anonymous, ::capture))
    }

    @Test
    fun `filters combine`() {
        val dialog = Filters.chatType(ChatType.DIALOG)
        val text = Filters.hasText()
        assertTrue((dialog and text).on(message("x"), ::msg))
        assertFalse((dialog and text).on(message(null), ::msg))
        assertFalse((dialog and text).on(message("x", chatType = "chat"), ::msg))
        assertTrue((dialog or text).on(message(null), ::msg))
        assertTrue((dialog or text).on(message("x", chatType = "chat"), ::msg))
        assertFalse((dialog or text).on(message(null, chatType = "chat"), ::msg))
        assertTrue((!text).on(message(null), ::msg))
        assertTrue(Filters.of<MessageContext> { it.text == "a" }.test(msg(message("a"))))
    }

    @Test
    fun `filters plug into handlers`(): Unit = runBlocking {
        bot.onMessage(Filters.chatType(ChatType.CHAT) and Filters.hasText()) { log += "group text" }
        bot.onCallback(Filters.payloadStartsWith("go:")) { log += "go" }
        bot.onMessage { log += "other" }
        bot.processUpdate(message("x", chatType = "chat"))
        bot.processUpdate(message("x"))
        bot.processUpdate(callback("go:1"))
        assertEquals(listOf("group text", "other", "go"), log)
    }

    // --------------------------------------------------------------- state

    @Test
    fun `a dialog of several steps`(): Unit = runBlocking {
        bot.onCommand("register") { setState("name"); log += "ask name" }
        bot.onMessage(Filters.state("name")) { setState("age", mapOf("name" to text!!)); log += "ask age" }
        bot.onMessage(Filters.state("age")) {
            updateStateData(mapOf("age" to text!!))
            log += "done ${stateData["name"]} ${stateData["age"]} ${state}"
            clearState()
            log += "cleared ${state} ${stateData}"
        }
        bot.onMessage(Filters.noState()) { log += "no state" }
        bot.processUpdate(message("/register"))
        bot.processUpdate(message("Ann"))
        bot.processUpdate(message("30", chatId = 200))
        bot.processUpdate(message("30"))
        bot.processUpdate(message("again"))
        assertEquals(listOf("ask name", "ask age", "no state", "done Ann 30 age", "cleared null {}", "no state"), log)
        assertEquals(0, (bot.config.stateStorage as InMemoryStateStorage).size)
    }

    @Test
    fun `setState keeps the data unless new data is given`(): Unit = runBlocking {
        val storage = InMemoryStateStorage()
        val bot = MaxBot(client, BotConfig(stateStorage = storage))
        bot.onMessage {
            when (text) {
                "1" -> setState("a", mapOf("k" to "v"))
                "2" -> setState("b")
                "3" -> setState(null)
                "4" -> setState(null, emptyMap())
            }
        }
        val key = StateKey(100, 1)
        bot.processUpdate(message("1"))
        assertEquals(DialogState("a", mapOf("k" to "v")), storage.get(key))
        bot.processUpdate(message("2"))
        assertEquals(DialogState("b", mapOf("k" to "v")), storage.get(key))
        bot.processUpdate(message("3"))
        assertEquals(DialogState(null, mapOf("k" to "v")), storage.get(key))
        bot.processUpdate(message("4"))
        assertNull(storage.get(key))
        assertEquals(0, storage.size)
    }

    @Test
    fun `state is shared by middleware and handler contexts of one update`(): Unit = runBlocking {
        bot.use { ctx, next -> ctx.setState("from middleware"); next() }
        bot.onMessage { log += state.toString() }
        bot.processUpdate(message())
        assertEquals(listOf("from middleware"), log)
    }

    @Test
    fun `state filters`(): Unit = runBlocking {
        bot.onMessage(Filters.state("a", "b")) { log += "a or b" }
        bot.onMessage(Filters.anyState()) { log += "any" }
        bot.onMessage { log += "none"; setState(text) }
        bot.processUpdate(message("b"))
        bot.processUpdate(message("x"))
        bot.processUpdate(message("x", userId = 2))
        bot.processUpdate(message("c", userId = 2))
        assertEquals(listOf("none", "a or b", "none", "any"), log)
    }

    @Test
    fun `state needs a chat or a user`(): Unit = runBlocking {
        bot.on<Update> { assertNull(state); setState("x") }
        bot.processUpdate(anonymous)
        assertIs<IllegalStateException>(errors.single())
    }

    @Test
    fun `blocking state methods for Java handlers`(): Unit = runBlocking {
        val storage = InMemoryStateStorage()
        val bot = MaxBot(client, BotConfig.builder().stateStorage(storage).build())
        bot.onMessage(Handler { ctx ->
            when (ctx.text) {
                "1" -> ctx.setStateBlocking("a")
                "2" -> ctx.setStateBlocking("b", mapOf("x" to "1"))
                "3" -> ctx.updateStateDataBlocking(mapOf("y" to "2"))
                "4" -> ctx.clearStateBlocking()
            }
        })
        val key = StateKey(100, 1)
        bot.processUpdate(message("1"))
        assertEquals(DialogState("a"), storage.get(key))
        bot.processUpdate(message("2"))
        bot.processUpdate(message("3"))
        assertEquals(DialogState("b", mapOf("x" to "1", "y" to "2")), storage.get(key))
        bot.processUpdate(message("4"))
        assertNull(storage.get(key))
    }

    @Test
    fun `a blocking storage runs on the IO dispatcher`(): Unit = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val storage = object : BlockingStateStorage() {
            var value: DialogState? = null
            override fun load(key: StateKey): DialogState? {
                calls += "load $key ${Thread.currentThread().name.contains("DefaultDispatcher")}"
                return value
            }

            override fun save(key: StateKey, state: DialogState?) {
                calls += "save ${state?.name}"
                value = state
            }
        }
        val bot = MaxBot(client, BotConfig(stateStorage = storage))
        bot.onMessage { setState("s") }
        bot.processUpdate(message())
        assertEquals(listOf("load StateKey(chatId=100, userId=1) true", "save s"), calls)
    }

    @Test
    fun `state keys`() {
        assertEquals(StateKey(100, 1), StateKey.of(message()))
        assertEquals(StateKey(5, 1), StateKey.of(dialogEvent("dialog_cleared", null)))
        assertEquals(
            StateKey(null, 3),
            StateKey.of(MaxJson.parseUpdate("""{"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"c","user":{"user_id":3}}}""")),
        )
        assertNull(StateKey.of(anonymous))
        assertEquals(StateKey(100, 1), StateKey.of(callback("p")))
    }

    // ------------------------------------------------------- callback data

    private val order = CallbackData("order", "id", "action")

    @Test
    fun `callback data packs and unpacks`() {
        assertEquals("order:42:buy", order.pack(42, "buy"))
        assertEquals("order::buy", order.pack(null, "buy"))
        assertEquals("order:7:", order.pack(mapOf("id" to 7)))
        assertEquals("order:1:a:b", order.pack(1, "a:b"))
        assertEquals(mapOf("id" to "1", "action" to "a:b"), order.unpack("order:1:a:b"))
        assertEquals(mapOf("id" to "42", "action" to "buy"), order.unpack("order:42:buy"))
        assertNull(order.unpack(null))
        assertNull(order.unpack("order:42"))
        assertNull(order.unpack("other:42:buy"))
        assertTrue(order.matches("order::"))
        assertFalse(order.matches("orders:1:2"))

        val ping = CallbackData("ping")
        assertEquals("ping", ping.pack())
        assertEquals(emptyMap(), ping.unpack("ping"))
        assertNull(ping.unpack("ping:1"))
        assertEquals("CallbackData(order, [id, action])", order.toString())
        assertEquals(listOf("id", "action"), order.fields)
    }

    @Test
    fun `callback data validates its input`() {
        assertFailsWith<IllegalArgumentException> { CallbackData("") }
        assertFailsWith<IllegalArgumentException> { CallbackData("a:b") }
        assertFailsWith<IllegalArgumentException> { CallbackData("a", "x", "x") }
        assertFailsWith<IllegalArgumentException> { order.pack(1) }
        assertFailsWith<IllegalArgumentException> { order.pack("1:2", "buy") }
        assertFailsWith<IllegalArgumentException> { order.pack(1, "x".repeat(CallbackData.MAX_LENGTH)) }
        assertFailsWith<IllegalArgumentException> { order.pack(mapOf("price" to 1)) }
    }

    @Test
    fun `callback handlers for callback data`(): Unit = runBlocking {
        bot.onCallback(order) { log += "order ${data(order)["id"]}" }
        bot.onCallback(CallbackData("java"), Handler { log += "java" })
        bot.onCallback(order.filter()) { log += "never" }
        bot.onCallback { log += "other"; data(order) }
        bot.processUpdate(callback("order:5:buy"))
        bot.processUpdate(callback("java"))
        bot.processUpdate(callback("misc"))
        assertEquals(listOf("order 5", "java", "other"), log)
        assertIs<IllegalArgumentException>(errors.single())
        assertTrue(order.filter().on(callback("order:1:2"), ::cb))
    }

    // -------------------------------------------------------------- locale

    @Test
    fun `locale comes from the update`() {
        assertEquals("ru", capture(message(locale = "ru")).locale)
        assertNull(capture(message()).locale)
        for (type in listOf("bot_started", "bot_stopped", "dialog_cleared", "dialog_removed", "dialog_muted", "dialog_unmuted")) {
            assertEquals("en", capture(dialogEvent(type, "en")).locale, type)
        }
        assertEquals(
            "de",
            capture(
                MaxJson.parseUpdate(
                    """{"update_type":"message_callback","timestamp":1,"user_locale":"de",
                        "callback":{"timestamp":1,"callback_id":"c","user":{"user_id":3}}}""",
                ),
            ).locale,
        )
        assertNull(capture(anonymous).locale)
        assertIs<MessageCreatedUpdate>(capture(message()).update)
    }
}
