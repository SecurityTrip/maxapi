package ru.securitytrip.maxapi.test

import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.Callback
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.MessageBody
import ru.securitytrip.maxapi.model.message.Recipient
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate
import ru.securitytrip.maxapi.model.update.BotRemovedFromChatUpdate
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.BotStoppedUpdate
import ru.securitytrip.maxapi.model.update.ChatTitleChangedUpdate
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.user.User

/**
 * Factories of the common updates for tests, with sensible defaults. For other update types,
 * construct the model classes directly or parse JSON with `MaxJson.parseUpdate`.
 */
public object TestUpdates {
    /** Default chat of the test updates. */
    public const val CHAT_ID: Long = 100

    /** Default user of the test updates. */
    public const val USER_ID: Long = 1

    /** A user with [userId] named "User [userId]". */
    @JvmStatic
    public fun user(userId: Long = USER_ID): User = User(userId, firstName = "User $userId")

    /** A new text message (`text` may be `null` for a message without text). */
    @JvmStatic
    @JvmOverloads
    public fun message(
        text: String?,
        chatId: Long = CHAT_ID,
        userId: Long = USER_ID,
        chatType: ChatType = ChatType.DIALOG,
        mid: String = "mid.1",
    ): MessageCreatedUpdate = MessageCreatedUpdate(
        timestamp = System.currentTimeMillis(),
        message = Message(
            sender = user(userId),
            recipient = Recipient(chatId = chatId, chatType = chatType),
            timestamp = System.currentTimeMillis(),
            body = MessageBody(mid = mid, text = text),
        ),
    )

    /** A press of a callback button with [payload] on a message of the bot in [chatId]. */
    @JvmStatic
    @JvmOverloads
    public fun callback(
        payload: String,
        chatId: Long = CHAT_ID,
        userId: Long = USER_ID,
        callbackId: String = "cb.1",
    ): MessageCallbackUpdate = MessageCallbackUpdate(
        timestamp = System.currentTimeMillis(),
        callback = Callback(System.currentTimeMillis(), callbackId, payload, user(userId)),
        message = Message(
            sender = User(BotTester.BOT_USER_ID, firstName = "Test bot", isBot = true),
            recipient = Recipient(chatId = chatId, chatType = ChatType.DIALOG),
            timestamp = System.currentTimeMillis(),
            body = MessageBody(mid = "mid.keyboard"),
        ),
    )

    /** A user pressing "Start", optionally through a deep link with [payload]. */
    @JvmStatic
    @JvmOverloads
    public fun botStarted(payload: String? = null, userId: Long = USER_ID, chatId: Long = CHAT_ID): BotStartedUpdate =
        BotStartedUpdate(System.currentTimeMillis(), chatId, user(userId), payload)

    /** A user stopping the bot in the dialog [chatId]. */
    @JvmStatic
    @JvmOverloads
    public fun botStopped(userId: Long = USER_ID, chatId: Long = CHAT_ID): BotStoppedUpdate =
        BotStoppedUpdate(System.currentTimeMillis(), chatId, user(userId))

    /** The bot added by [userId] to the group chat [chatId], or to a channel if [isChannel]. */
    @JvmStatic
    @JvmOverloads
    public fun botAdded(chatId: Long = CHAT_ID, userId: Long = USER_ID, isChannel: Boolean = false): BotAddedToChatUpdate =
        BotAddedToChatUpdate(System.currentTimeMillis(), chatId, user(userId), isChannel)

    /** The bot removed by [userId] from the group chat [chatId], or from a channel if [isChannel]. */
    @JvmStatic
    @JvmOverloads
    public fun botRemoved(chatId: Long = CHAT_ID, userId: Long = USER_ID, isChannel: Boolean = false): BotRemovedFromChatUpdate =
        BotRemovedFromChatUpdate(System.currentTimeMillis(), chatId, user(userId), isChannel)

    /** [userId] changing the title of the chat [chatId] to [title]. */
    @JvmStatic
    @JvmOverloads
    public fun chatTitleChanged(title: String, chatId: Long = CHAT_ID, userId: Long = USER_ID): ChatTitleChangedUpdate =
        ChatTitleChangedUpdate(System.currentTimeMillis(), chatId, user(userId), title)
}
