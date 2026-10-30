package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate
import ru.securitytrip.maxapi.model.update.BotRemovedFromChatUpdate
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.BotStoppedUpdate
import ru.securitytrip.maxapi.model.update.ChatTitleChangedUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.user.User
import java.util.concurrent.ConcurrentHashMap

/**
 * A chat the bot is in, as a [ChatStore] remembers it: a group chat or a channel the bot was added to,
 * or the dialog of a user who started the bot.
 *
 * @property chatId identifier of the chat; pass it to `sendMessageToChat`.
 * @property type [ChatType.DIALOG], [ChatType.CHAT] or [ChatType.CHANNEL]; [ChatType.UNKNOWN] if only
 *   the title change of the chat was seen.
 * @property title title of the chat, or the name of the user for a dialog; `null` if not known.
 * @property userId the user of a dialog; `null` for other chats.
 * @property active `false` once the bot was removed from the chat or the user stopped the bot. The
 *   record is kept, so that a late repeated delivery of an older event does not bring the chat back.
 * @property updatedAt time of the event the record comes from, Unix time in milliseconds. Records from
 *   older events never replace records from newer ones.
 */
public data class KnownChat @JvmOverloads constructor(
    val chatId: Long,
    val type: ChatType,
    val title: String? = null,
    val userId: Long? = null,
    val active: Boolean = true,
    val updatedAt: Long = 0,
)

/**
 * Where the bot remembers the chats it is in, set with [BotConfig.chatStore]. Max has no method that
 * lists the chats of a bot, so the bot keeps the list itself from the updates it receives; read it
 * with [MaxBot.chats].
 *
 * The bot saves a chat when it is added to a group chat or a channel (`bot_added`), when a user starts
 * it (`bot_started`) and the first time it sees a message from a chat it does not know yet; it marks
 * the chat inactive when it is removed (`bot_removed`) or the user stops it (`bot_stopped`), and keeps
 * the title up to date (`chat_title_changed`). With a store set, these update types are requested by
 * long polling and [MaxBot.setWebhook] even if no handler takes them.
 *
 * Updates may arrive twice or out of order, so [save] keeps the record with the latest
 * [KnownChat.updatedAt]: a repeated delivery changes nothing, and an older event never undoes a newer
 * one.
 *
 * [InMemoryChatStore] forgets the chats on restart; `maxapi-jdbc` and `maxapi-redis` keep them in a
 * database. Implement this interface (Kotlin) or extend [BlockingChatStore] (Java) for other storages.
 */
public interface ChatStore {
    /** The chat with [chatId], active or not, or `null` if it is not known. */
    public suspend fun get(chatId: Long): KnownChat?

    /**
     * Saves [chat] unless the store has a record of the same chat with a later [KnownChat.updatedAt].
     * A record with the same time is replaced. Must be atomic when several instances share the store.
     *
     * @return `true` if [chat] was saved.
     */
    public suspend fun save(chat: KnownChat): Boolean

    /** The active chats, in no particular order. */
    public suspend fun list(): List<KnownChat>
}

/** Keeps the chats in memory: they are lost on restart and not shared between instances. */
public class InMemoryChatStore : ChatStore {
    private val chats = ConcurrentHashMap<Long, KnownChat>()

    override suspend fun get(chatId: Long): KnownChat? = chats[chatId]

    override suspend fun save(chat: KnownChat): Boolean {
        var saved = false
        chats.compute(chat.chatId) { _, old ->
            if (old != null && old.updatedAt > chat.updatedAt) old else chat.also { saved = true }
        }
        return saved
    }

    override suspend fun list(): List<KnownChat> = chats.values.filter { it.active }
}

/**
 * Base class for a [ChatStore] with blocking I/O, e.g. JDBC, for Java. The methods run on
 * `Dispatchers.IO`.
 */
public abstract class BlockingChatStore : ChatStore {
    /** The chat with [chatId], active or not, or `null` if it is not known. */
    protected abstract fun load(chatId: Long): KnownChat?

    /** Saves [chat] unless a record with a later `updatedAt` is stored; `true` if it was saved. */
    protected abstract fun saveIfNewer(chat: KnownChat): Boolean

    /** The active chats. */
    protected abstract fun loadActive(): List<KnownChat>

    final override suspend fun get(chatId: Long): KnownChat? = withContext(Dispatchers.IO) { load(chatId) }

    final override suspend fun save(chat: KnownChat): Boolean = withContext(Dispatchers.IO) { saveIfNewer(chat) }

    final override suspend fun list(): List<KnownChat> = withContext(Dispatchers.IO) { loadActive() }
}

/** Turns updates into records of a [ChatStore]. */
internal class ChatTracker(private val store: ChatStore, private val client: MaxClient) {
    /** Chats known to be active, so that messages from them do not read the store again. */
    private val seen = object : LinkedHashMap<Long, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>): Boolean = size > MAX_SEEN
    }

    suspend fun track(update: Update) {
        when (update) {
            is BotAddedToChatUpdate -> {
                val type = if (update.isChannel) ChatType.CHANNEL else ChatType.CHAT
                val title = titleOf(update.chatId) ?: store.get(update.chatId)?.title
                activate(KnownChat(update.chatId, type, title, null, true, update.timestamp))
            }
            is BotStartedUpdate -> activate(dialog(update.chatId, update.user, update.timestamp))
            is BotRemovedFromChatUpdate ->
                deactivate(update.chatId, update.timestamp) { KnownChat(it, if (update.isChannel) ChatType.CHANNEL else ChatType.CHAT) }
            is BotStoppedUpdate -> deactivate(update.chatId, update.timestamp) { dialog(it, update.user, 0) }
            is ChatTitleChangedUpdate -> {
                val old = store.get(update.chatId)
                val chat = old?.copy(title = update.title, updatedAt = update.timestamp)
                    ?: KnownChat(update.chatId, ChatType.UNKNOWN, update.title, null, true, update.timestamp)
                store.save(chat)
            }
            is MessageCreatedUpdate -> learn(update)
            else -> Unit
        }
    }

    /** Saves the chat of a message the first time this process sees it, so chats joined before the store are learned too. */
    private suspend fun learn(update: MessageCreatedUpdate) {
        val recipient = update.message.recipient
        val chatId = recipient.chatId ?: return
        if (synchronized(seen) { seen.containsKey(chatId) }) return
        val old = store.get(chatId)
        if (old == null || !old.active) {
            val sender = update.message.sender
            val chat = if (recipient.chatType == ChatType.DIALOG && sender != null) {
                dialog(chatId, sender, update.timestamp)
            } else {
                KnownChat(chatId, recipient.chatType, old?.title, null, true, update.timestamp)
            }
            if (!store.save(chat)) return
        }
        remember(chatId, true)
    }

    private suspend fun activate(chat: KnownChat) {
        if (store.save(chat)) remember(chat.chatId, true)
    }

    private suspend fun deactivate(chatId: Long, timestamp: Long, unknown: (Long) -> KnownChat) {
        val old = store.get(chatId) ?: unknown(chatId)
        store.save(old.copy(active = false, updatedAt = timestamp))
        remember(chatId, false)
    }

    private fun remember(chatId: Long, active: Boolean) {
        synchronized(seen) { if (active) seen[chatId] = Unit else seen.remove(chatId) }
    }

    private fun dialog(chatId: Long, user: User, timestamp: Long): KnownChat =
        KnownChat(chatId, ChatType.DIALOG, user.fullName(), user.userId, true, timestamp)

    private fun User.fullName(): String = listOfNotNull(firstName.ifEmpty { null }, lastName).joinToString(" ").ifEmpty { username ?: userId.toString() }

    /** The title from `getChat`; `null` if it cannot be read, the chat is saved without it then. */
    private suspend fun titleOf(chatId: Long): String? = try {
        client.getChat(chatId).title
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal companion object {
        const val MAX_SEEN = 10_000

        /** Update types the tracker needs, requested even without handlers for them. */
        val UPDATE_TYPES: Set<UpdateType> = setOf(
            UpdateType.BOT_ADDED,
            UpdateType.BOT_REMOVED,
            UpdateType.BOT_STARTED,
            UpdateType.BOT_STOPPED,
            UpdateType.CHAT_TITLE_CHANGED,
        )
    }
}
