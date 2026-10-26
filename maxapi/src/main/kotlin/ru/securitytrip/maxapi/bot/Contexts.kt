package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.dsl.MessageBuilder
import ru.securitytrip.maxapi.java.MaxBlockingClient
import ru.securitytrip.maxapi.model.SimpleQueryResult
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.message.Callback
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.message.NewMessageLink
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.BotStoppedUpdate
import ru.securitytrip.maxapi.model.update.DialogClearedUpdate
import ru.securitytrip.maxapi.model.update.DialogMutedUpdate
import ru.securitytrip.maxapi.model.update.DialogRemovedUpdate
import ru.securitytrip.maxapi.model.update.DialogUnmutedUpdate
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.user.User

/**
 * What an update handler receives: the update itself, the bot, its client, and shortcuts to answer.
 *
 * In Kotlin the context is the receiver of the handler lambda, so its members are available directly:
 *
 * ```kotlin
 * bot.on<BotAddedToChatUpdate> { reply("Hi everyone! I was added by ${update.user.firstName}") }
 * ```
 *
 * Methods without a suffix are `suspend` and meant for Kotlin; the `*Blocking` variants are for Java
 * handlers, which run on a thread where blocking is fine.
 *
 * Specialized contexts add helpers for their update type: [MessageContext], [CallbackContext],
 * [BotStartedContext].
 *
 * ## Dialog state
 *
 * [state] and [stateData] hold the state of the dialog (this user in this chat), kept in
 * [BotConfig.stateStorage]. Use them for conversations of several steps, together with
 * [Filters.state]:
 *
 * ```kotlin
 * bot.onCommand("register") { setState("name"); reply("Your name?") }
 * bot.onMessage(Filters.state("name")) { setState("age", mapOf("name" to text.orEmpty())); reply("Your age?") }
 * bot.onMessage(Filters.state("age")) { reply("Hi, ${stateData["name"]}!"); clearState() }
 * ```
 *
 * @property update the update being handled.
 */
public open class UpdateContext<U : Update> internal constructor(
    public val update: U,
    internal val dispatch: Dispatch,
) {
    /** The bot that dispatched the update. */
    public val bot: MaxBot get() = dispatch.bot

    /** Client of the bot, for any API call. */
    public val client: MaxClient get() = bot.client

    /** Blocking client of the bot, for Java handlers. */
    public fun blocking(): MaxBlockingClient = client.blocking()

    /** Chat of the update, if it can be determined. [reply] sends messages there. */
    public open val chatId: Long? get() = update.chatId

    /** User who caused the update, if known. */
    public open val user: User? get() = update.user

    /** Locale of the user's client, like `ru`, if the update carries it. Translations are up to you. */
    public val locale: String? get() = localeOf(update)

    // ---------------------------------------------------------------- state

    /** Name of the dialog state, `null` if there is none. */
    public val state: String? get() = dispatch.dialog?.name

    /** Values stored with the dialog state; empty if there are none. */
    public val stateData: Map<String, String> get() = dispatch.dialog?.data.orEmpty()

    /**
     * Sets the dialog state to [name] and its values to [data] (by default the current values are kept).
     * @throws IllegalStateException if the update has neither a chat nor a user.
     */
    public suspend fun setState(name: String?, data: Map<String, String> = stateData) {
        save(DialogState(name, data))
    }

    /** Adds [data] to the values of the dialog state, replacing values with the same keys. */
    public suspend fun updateStateData(data: Map<String, String>) {
        save(DialogState(state, stateData + data))
    }

    /** Removes the dialog state and its values. */
    public suspend fun clearState() {
        save(null)
    }

    private suspend fun save(value: DialogState?) {
        val key = checkNotNull(dispatch.stateKey) {
            "Cannot keep state for ${update.updateType}: neither chat nor user is known"
        }
        val stored = value?.takeUnless { it.name == null && it.data.isEmpty() }
        bot.config.stateStorage.set(key, stored)
        dispatch.dialog = stored
    }

    /** Blocking version of [setState] for Java handlers. */
    public fun setStateBlocking(name: String?): Unit = runBlocking { setState(name) }

    /** Blocking version of [setState] for Java handlers. */
    public fun setStateBlocking(name: String?, data: Map<String, String>): Unit = runBlocking { setState(name, data) }

    /** Blocking version of [updateStateData] for Java handlers. */
    public fun updateStateDataBlocking(data: Map<String, String>): Unit = runBlocking { updateStateData(data) }

    /** Blocking version of [clearState] for Java handlers. */
    public fun clearStateBlocking(): Unit = runBlocking { clearState() }

    // -------------------------------------------------------------- replies

    /** Sends a text message to [chatId], or to [user] if the chat is unknown. */
    public suspend fun reply(text: String): Message = reply(NewMessageBody(text = text))

    /**
     * Sends a message to [chatId], or to [user] if the chat is unknown.
     * @throws IllegalStateException if neither is known for this update.
     */
    public suspend fun reply(body: NewMessageBody): Message {
        chatId?.let { return client.sendMessageToChat(it, body) }
        user?.let { return client.sendMessageToUser(it.userId, body) }
        throw IllegalStateException("Cannot reply to ${update.updateType}: neither chat nor user is known")
    }

    /**
     * Sends a message built with the DSL:
     *
     * ```kotlin
     * reply {
     *     text("Choose:")
     *     keyboard { row { callback("A", "a"); callback("B", "b") } }
     * }
     * ```
     */
    public suspend fun reply(block: MessageBuilder.() -> Unit): Message = reply(MessageBuilder().apply(block).build())

    /**
     * Shows a bot activity in [chatId], e.g. [SenderAction.SENDING_PHOTO].
     * @throws IllegalArgumentException if the chat is unknown.
     */
    public suspend fun sendAction(action: SenderAction): SimpleQueryResult =
        client.sendAction(requireNotNull(chatId) { "Chat of ${update.updateType} is unknown" }, action)

    /** Shows "typing…" in [chatId]. Handy before a slow reply. */
    public suspend fun typing(): SimpleQueryResult = sendAction(SenderAction.TYPING_ON)

    /** Blocking version of [reply] for Java handlers. */
    public fun replyBlocking(text: String): Message = runBlocking { reply(text) }

    /** Blocking version of [reply] for Java handlers. */
    public fun replyBlocking(body: NewMessageBody): Message = runBlocking { reply(body) }

    /** Blocking version of [sendAction] for Java handlers. */
    public fun sendActionBlocking(action: SenderAction): SimpleQueryResult = runBlocking { sendAction(action) }

    override fun toString(): String = "${javaClass.simpleName}($update)"
}

/**
 * Context of a new message, passed to `onMessage` and `onCommand` handlers.
 *
 * For a message like `/start ref-42` [command] is `"start"` and [commandArgs] is `"ref-42"`.
 */
public class MessageContext internal constructor(
    update: MessageCreatedUpdate,
    dispatch: Dispatch,
) : UpdateContext<MessageCreatedUpdate>(update, dispatch) {
    /** The new message. */
    public val message: Message get() = update.message

    /**
     * Message text, or `null` if the message has no text (e.g. only a photo or a sticker). Max sends
     * an empty string for such messages; it is `null` here too, so `text ?: "..."` works as expected.
     */
    public val text: String? get() = message.body?.text?.takeIf { it.isNotEmpty() }

    /** Identifier of the message. */
    public val messageId: String? get() = message.body?.mid

    private val parsedCommand: ParsedCommand? by lazy {
        parseCommand(text)?.takeIf { it.botName == null || it.botName.equals(bot.username, ignoreCase = true) }
    }

    /**
     * Command name if the text starts with `/`: lowercase, without `/` and without an `@botname`
     * suffix. For `/Start@my_bot abc` it is `"start"`. `null` if the message is not a command, or if it
     * is addressed to another bot (`/start@other_bot`, see [MaxBot.username]).
     */
    public val command: String? get() = parsedCommand?.name

    /** Text after the command, trimmed: `"abc def"` for `/start abc def`. Empty if there are no arguments. */
    public val commandArgs: String get() = parsedCommand?.args.orEmpty()

    /** Replies to this message with a quote of it. */
    public suspend fun replyToMessage(text: String): Message = replyToMessage(NewMessageBody(text = text))

    /** Replies to this message with a quote of it. Any [NewMessageBody.link] of [body] is replaced. */
    public suspend fun replyToMessage(body: NewMessageBody): Message {
        val mid = messageId ?: return reply(body)
        return reply(body.copy(link = NewMessageLink(MessageLinkType.REPLY, mid)))
    }

    /** Blocking version of [replyToMessage] for Java handlers. */
    public fun replyToMessageBlocking(text: String): Message = runBlocking { replyToMessage(text) }

    /** `/name@botName args`: [name] is lowercase, [botName] is `null` without a suffix. */
    internal data class ParsedCommand(val name: String, val botName: String?, val args: String)

    internal companion object {
        fun parseCommand(text: String?): ParsedCommand? {
            if (text == null) return null
            val trimmed = text.trimStart()
            if (trimmed.length < 2 || trimmed[0] != '/') return null
            val end = trimmed.indexOfFirst { it.isWhitespace() }.let { if (it < 0) trimmed.length else it }
            val token = trimmed.substring(1, end)
            val name = token.substringBefore('@')
            if (name.isEmpty()) return null
            val botName = token.substringAfter('@', missingDelimiterValue = "").ifEmpty { null }
            return ParsedCommand(name.lowercase(), botName, trimmed.substring(end).trim())
        }
    }
}

/**
 * Context of a callback button press, passed to `onCallback` handlers.
 *
 * Answer every press with [answer] or [editMessage]:
 *
 * ```kotlin
 * bot.onCallback("like") { answer("Thanks!") }
 * bot.onCallback({ it.payload?.startsWith("page:") == true }) {
 *     val page = payload!!.removePrefix("page:").toInt()
 *     editMessage(renderPage(page))
 * }
 * ```
 */
public class CallbackContext internal constructor(
    update: MessageCallbackUpdate,
    dispatch: Dispatch,
) : UpdateContext<MessageCallbackUpdate>(update, dispatch) {
    /** The button press. */
    public val callback: Callback get() = update.callback

    /** Payload of the pressed button. */
    public val payload: String? get() = callback.payload

    /** The message with the keyboard; `null` if it was deleted. */
    public val message: Message? get() = update.message

    /** User who pressed the button. */
    override val user: User get() = callback.user

    /**
     * The fields of [payload] packed by [callbackData].
     * @throws IllegalArgumentException if the payload was not packed by [callbackData].
     */
    public fun data(callbackData: CallbackData): Map<String, String> =
        requireNotNull(callbackData.unpack(payload)) { "Payload $payload does not match $callbackData" }

    /**
     * Answers the press with a one-time notification to the user. The mobile apps of Max show it;
     * the web client does not, so use [editMessage] when the user must see the result.
     */
    public suspend fun answer(notification: String): SimpleQueryResult =
        client.answerOnCallback(callback.callbackId, notification)

    /** Answers the press with a new message content and/or a notification. */
    public suspend fun answer(answer: CallbackAnswer): SimpleQueryResult =
        client.answerOnCallback(callback.callbackId, answer)

    /**
     * Answers the press by replacing the message that contains the button with [body],
     * optionally showing a [notification] too.
     */
    public suspend fun editMessage(body: NewMessageBody, notification: String? = null): SimpleQueryResult =
        answer(CallbackAnswer(message = body, notification = notification))

    /** Blocking version of [answer] for Java handlers. */
    public fun answerBlocking(notification: String): SimpleQueryResult = runBlocking { answer(notification) }

    /** Blocking version of [answer] for Java handlers. */
    public fun answerBlocking(callbackAnswer: CallbackAnswer): SimpleQueryResult =
        runBlocking { answer(callbackAnswer) }

    /** Blocking version of [editMessage] for Java handlers. */
    @JvmOverloads
    public fun editMessageBlocking(body: NewMessageBody, notification: String? = null): SimpleQueryResult =
        runBlocking { editMessage(body, notification) }
}

/**
 * Context of [BotStartedUpdate], passed to `onBotStarted` handlers: a user pressed "Start"
 * or opened a deep link to the bot.
 */
public class BotStartedContext internal constructor(
    update: BotStartedUpdate,
    dispatch: Dispatch,
) : UpdateContext<BotStartedUpdate>(update, dispatch) {
    /** The `start` parameter of `https://max.ru/<botName>?start=<payload>`, or `null` without a deep link. */
    public val payload: String? get() = update.payload

    /** The dialog with the user. */
    override val chatId: Long get() = update.chatId

    /** The user who started the bot. */
    override val user: User get() = update.user
}

private fun localeOf(update: Update): String? = when (update) {
    is MessageCreatedUpdate -> update.userLocale
    is MessageCallbackUpdate -> update.userLocale
    is BotStartedUpdate -> update.userLocale
    is BotStoppedUpdate -> update.userLocale
    is DialogClearedUpdate -> update.userLocale
    is DialogRemovedUpdate -> update.userLocale
    is DialogMutedUpdate -> update.userLocale
    is DialogUnmutedUpdate -> update.userLocale
    else -> null
}
