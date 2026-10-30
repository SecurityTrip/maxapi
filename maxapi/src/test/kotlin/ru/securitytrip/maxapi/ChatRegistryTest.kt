package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.BlockingChatStore
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.ChatStore
import ru.securitytrip.maxapi.bot.InMemoryChatStore
import ru.securitytrip.maxapi.bot.KnownChat
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate
import ru.securitytrip.maxapi.model.update.BotRemovedFromChatUpdate
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.BotStoppedUpdate
import ru.securitytrip.maxapi.model.update.ChatTitleChangedUpdate
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.update.UserAddedToChatUpdate
import ru.securitytrip.maxapi.model.user.User
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRegistryTest {
    private val api = FakeApi()
    private val store = InMemoryChatStore()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val bot = MaxBot(api.client, BotConfig(chatStore = store)).onError { e, _ -> errors += e }
    private val ann = User(7, "Ann", "Lee")

    @AfterTest
    fun tearDown() {
        bot.close()
        api.close()
    }

    private fun added(chatId: Long, at: Long, channel: Boolean = false) = BotAddedToChatUpdate(at, chatId, ann, channel)
    private fun removed(chatId: Long, at: Long, channel: Boolean = false) = BotRemovedFromChatUpdate(at, chatId, ann, channel)

    @Test
    fun `chats are added, renamed and removed by events`(): Unit = runBlocking {
        api.respond = { if (it.path == "/chats/10") FakeApi.Reply(200, """{"chat_id":10,"type":"chat","title":"Team"}""") else null }
        bot.processUpdate(added(10, at = 1))
        bot.processUpdate(added(20, at = 2, channel = true))
        bot.processUpdate(BotStartedUpdate(3, 30, ann))

        assertEquals(
            setOf(
                KnownChat(10, ChatType.CHAT, "Team", null, true, 1),
                KnownChat(20, ChatType.CHANNEL, null, null, true, 2),
                KnownChat(30, ChatType.DIALOG, "Ann Lee", 7, true, 3),
            ),
            bot.chats().toSet(),
        )
        assertEquals(listOf(30L), bot.chats(ChatType.DIALOG).map { it.chatId })
        assertEquals(setOf(10L, 20L), bot.chatsBlocking(ChatType.CHAT, ChatType.CHANNEL).map { it.chatId }.toSet())

        bot.processUpdate(ChatTitleChangedUpdate(4, 20, ann, "News"))
        assertEquals("News", store.get(20)?.title)

        bot.processUpdate(removed(10, at = 5))
        bot.processUpdate(BotStoppedUpdate(6, 30, ann))
        assertEquals(listOf(20L), bot.chats().map { it.chatId })
        assertEquals(KnownChat(10, ChatType.CHAT, "Team", null, false, 5), store.get(10))
        assertEquals(KnownChat(30, ChatType.DIALOG, "Ann Lee", 7, false, 6), store.get(30))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `repeated and late deliveries change nothing`(): Unit = runBlocking {
        bot.processUpdate(added(10, at = 1))
        bot.processUpdate(added(10, at = 1))
        assertEquals(1, bot.chats().size)

        bot.processUpdate(removed(10, at = 5))
        // The add is delivered again after the removal: the removal is newer and wins.
        bot.processUpdate(added(10, at = 1))
        bot.processUpdate(ChatTitleChangedUpdate(3, 10, ann, "Old title"))
        bot.processUpdate(FakeApi.message("late", chatId = 10, chatType = ChatType.CHAT, timestamp = 4))
        assertEquals(emptyList(), bot.chats())
        assertNull(store.get(10)?.title)

        // Added again later: active again.
        bot.processUpdate(added(10, at = 9))
        assertEquals(listOf(10L), bot.chats().map { it.chatId })
    }

    @Test
    fun `chats are learned from messages once`(): Unit = runBlocking {
        val reads = CopyOnWriteArrayList<Long>()
        val counting = object : ChatStore by store {
            override suspend fun get(chatId: Long): KnownChat? = store.get(chatId).also { reads += chatId }
        }
        MaxBot(api.client, BotConfig(chatStore = counting)).use { bot ->
            bot.processUpdate(FakeApi.message("hi", chatId = 40, userId = 7, timestamp = 1))
            bot.processUpdate(FakeApi.message("again", chatId = 40, userId = 7, timestamp = 2))
            bot.processUpdate(FakeApi.message("hi all", chatId = 50, chatType = ChatType.CHAT, timestamp = 3))
            bot.processUpdate(BotStartedUpdate(4, 60, ann))
            bot.processUpdate(FakeApi.message("known", chatId = 60, userId = 7, timestamp = 5))

            assertEquals(listOf(40L, 50L), reads)
            assertEquals(KnownChat(40, ChatType.DIALOG, "User", 7, true, 1), store.get(40))
            assertEquals(KnownChat(50, ChatType.CHAT, null, null, true, 3), store.get(50))
            assertEquals(4, store.get(60)?.updatedAt)
        }
    }

    @Test
    fun `a message brings back a chat whose removal was missed`(): Unit = runBlocking {
        store.save(KnownChat(10, ChatType.CHAT, "Team", null, false, 1))
        bot.processUpdate(FakeApi.message("hi", chatId = 10, chatType = ChatType.CHAT, timestamp = 2))
        assertEquals(KnownChat(10, ChatType.CHAT, "Team", null, true, 2), store.get(10))
    }

    @Test
    fun `messages without a chat and other updates are ignored`(): Unit = runBlocking {
        bot.processUpdate(UserAddedToChatUpdate(1, 10, ann, 1))
        val noChat = FakeApi.message("hi").let { it.copy(message = it.message.copy(recipient = it.message.recipient.copy(chatId = null))) }
        bot.processUpdate(noChat)
        val noSender = FakeApi.message("hi", chatId = 70).let { it.copy(message = it.message.copy(sender = null)) }
        bot.processUpdate(noSender)
        assertEquals(listOf(KnownChat(70, ChatType.DIALOG, null, null, true, 1)), bot.chats())
    }

    @Test
    fun `removal of an unknown chat is remembered`(): Unit = runBlocking {
        bot.processUpdate(removed(10, at = 5, channel = true))
        bot.processUpdate(removed(11, at = 5))
        bot.processUpdate(BotStoppedUpdate(5, 12, User(8, "", null, "bob")))
        bot.processUpdate(BotStartedUpdate(5, 13, User(9, "")))
        bot.processUpdate(ChatTitleChangedUpdate(6, 14, ann, "New"))
        assertEquals(KnownChat(10, ChatType.CHANNEL, null, null, false, 5), store.get(10))
        assertEquals(KnownChat(11, ChatType.CHAT, null, null, false, 5), store.get(11))
        assertEquals(KnownChat(12, ChatType.DIALOG, "bob", 8, false, 5), store.get(12))
        assertEquals("9", store.get(13)?.title)
        assertEquals(KnownChat(14, ChatType.UNKNOWN, "New", null, true, 6), store.get(14))
    }

    @Test
    fun `a failing store is reported and handlers still run`(): Unit = runBlocking {
        val failing = object : ChatStore by store {
            override suspend fun save(chat: KnownChat): Boolean = error("db down")
        }
        val handled = CopyOnWriteArrayList<String>()
        val bot = MaxBot(api.client, BotConfig(chatStore = failing)).onError { e, _ -> errors += e }
        bot.use {
            it.onMessage { handled += text.orEmpty() }
            it.processUpdate(FakeApi.message("hi", chatId = 80))
        }
        assertEquals(listOf("hi"), handled)
        assertEquals("db down", errors.single().message)
    }

    @Test
    fun `the store adds the update types it needs`(): Unit = runBlocking {
        bot.onMessage { }
        bot.setWebhook("https://bot.example.com/hook")
        assertEquals(
            setOf("message_created", "bot_added", "bot_removed", "bot_started", "bot_stopped", "chat_title_changed"),
            api.calls.last().json!!.path("update_types").map { it.asText() }.toSet(),
        )
        assertEquals(setOf(UpdateType.MESSAGE_CREATED), bot.subscribedUpdateTypes())

        // A store alone is enough to subscribe; a handler of every update asks for all types.
        MaxBot(api.client, BotConfig(chatStore = store)).use {
            it.setWebhook("https://bot.example.com/hook")
            assertEquals(5, api.calls.last().json!!.path("update_types").size())
            it.on<ru.securitytrip.maxapi.model.update.Update> { }
            it.setWebhook("https://bot.example.com/hook")
            assertTrue(api.calls.last().json!!.path("update_types").isMissingNode)
        }
        MaxBot(api.client).use { plain -> assertFailsWith<IllegalStateException> { plain.chats() } }
    }

    @Test
    fun `a blocking store runs on the IO dispatcher`(): Unit = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        val blocking = object : BlockingChatStore() {
            override fun load(chatId: Long): KnownChat? = store.run { threads += Thread.currentThread().name; null }
            override fun saveIfNewer(chat: KnownChat): Boolean = runBlocking { store.save(chat) }
            override fun loadActive(): List<KnownChat> = runBlocking { store.list() }
        }
        assertNull(blocking.get(1))
        assertTrue(blocking.save(KnownChat(1, ChatType.CHAT)))
        assertFalse(store.save(KnownChat(1, ChatType.CHAT, updatedAt = -1)))
        assertEquals(1, blocking.list().size)
        assertTrue(threads.single().contains("DefaultDispatcher"))
    }
}
