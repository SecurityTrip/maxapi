package ru.securitytrip.maxapi.client

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import ru.securitytrip.maxapi.java.MaxAsyncClient
import ru.securitytrip.maxapi.java.MaxBlockingClient
import ru.securitytrip.maxapi.model.SimpleQueryResult
import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.attachment.AudioAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.FileAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequestPayload
import ru.securitytrip.maxapi.model.attachment.PhotoToken
import ru.securitytrip.maxapi.model.attachment.UploadEndpoint
import ru.securitytrip.maxapi.model.attachment.UploadType
import ru.securitytrip.maxapi.model.attachment.UploadedInfo
import ru.securitytrip.maxapi.model.attachment.VideoAttachmentDetails
import ru.securitytrip.maxapi.model.attachment.VideoAttachmentRequest
import ru.securitytrip.maxapi.model.chat.ActionRequestBody
import ru.securitytrip.maxapi.model.chat.Chat
import ru.securitytrip.maxapi.model.chat.ChatAdmin
import ru.securitytrip.maxapi.model.chat.ChatAdminsList
import ru.securitytrip.maxapi.model.chat.ChatMember
import ru.securitytrip.maxapi.model.chat.ChatMembersList
import ru.securitytrip.maxapi.model.chat.ChatPatch
import ru.securitytrip.maxapi.model.chat.GetPinnedMessageResult
import ru.securitytrip.maxapi.model.chat.ModifyMembersResult
import ru.securitytrip.maxapi.model.chat.PinMessageBody
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.chat.UserIdsList
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.CommentMessage
import ru.securitytrip.maxapi.model.message.CommentMessageList
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.message.MessageList
import ru.securitytrip.maxapi.model.message.NewCommentBody
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.message.SendCommentResult
import ru.securitytrip.maxapi.model.message.SendMessageResult
import ru.securitytrip.maxapi.model.subscription.GetSubscriptionsResult
import ru.securitytrip.maxapi.model.subscription.Subscription
import ru.securitytrip.maxapi.model.subscription.SubscriptionRequestBody
import ru.securitytrip.maxapi.model.update.UpdateList
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.user.BotCommand
import ru.securitytrip.maxapi.model.user.BotCommandsInfo
import ru.securitytrip.maxapi.model.user.BotCommandsPatch
import ru.securitytrip.maxapi.model.user.BotInfo
import java.io.Closeable

/**
 * Client of [Max Bot API](https://dev.max.ru/docs-api).
 *
 * Method names match the `operationId`s of the API specification, so the official documentation
 * applies directly. All methods are `suspend` functions; Java code can use [blocking] or [async].
 *
 * ```kotlin
 * MaxClient(token).use { client ->
 *     val me = client.getMyInfo()
 *     client.sendMessageToChat(chatId, "Hello from ${me.firstName}")
 * }
 * ```
 *
 * ## Errors
 *
 * - [MaxApiException] (and subclasses such as [ForbiddenException]) when the server answers with an error;
 * - [MaxNetworkException] when the server cannot be reached;
 * - [MaxException] when the response cannot be parsed.
 *
 * Failed requests are retried according to [MaxClientConfig.retryPolicy] before an exception is thrown.
 *
 * ## Lifecycle
 *
 * The client is thread-safe. Create one instance per bot token, share it, and [close] it on shutdown.
 *
 * @property config settings this client was created with.
 */
public class MaxClient(public val config: MaxClientConfig) : Closeable {
    /** Creates a client with default settings. */
    public constructor(token: String) : this(MaxClientConfig(token))

    private val json = MaxJson.mapper
    internal val transport = HttpTransport(config, json)

    internal val asyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val blockingClient by lazy { MaxBlockingClient(this) }
    private val asyncClient by lazy { MaxAsyncClient(this) }

    /** Blocking facade of this client for Java. Always returns the same instance. */
    public fun blocking(): MaxBlockingClient = blockingClient

    /** [java.util.concurrent.CompletableFuture]-based facade of this client for Java. Always returns the same instance. */
    public fun async(): MaxAsyncClient = asyncClient

    // ---------------------------------------------------------------- bots

    /** Returns the current bot: identifier, name, username, avatar and commands. */
    public suspend fun getMyInfo(): BotInfo = get(BotInfo::class.java, "me")

    /**
     * Replaces the commands shown in the bot menu. The mobile apps of Max show the menu; the web
     * client does not.
     *
     * @param commands up to 32 commands; an empty list removes all commands.
     * @return the commands after the change.
     * @throws IllegalArgumentException if the commands break the [MaxLimits] of Max: more than 32,
     *   a name not of 1–64 characters, or a description not of 1–128.
     */
    public suspend fun editMyCommands(commands: List<BotCommand>): BotCommandsInfo {
        MaxLimits.checkCommands(commands)
        return transport.execute("PATCH", listOf("me", "commands"), BotCommandsInfo::class.java, body = BotCommandsPatch(commands))
    }

    // ---------------------------------------------------------------- chats

    /**
     * Returns a dialog, chat or channel by identifier.
     * @throws NotFoundException if the chat does not exist or the bot has no access to it.
     */
    public suspend fun getChat(chatId: Long): Chat = get(Chat::class.java, "chats", chatId.toString())

    /**
     * Changes title, icon, description or pinned message of a chat or channel.
     * The bot needs the corresponding administrator permissions.
     *
     * @return the chat after the change.
     * @throws ForbiddenException if the bot lacks permissions.
     */
    public suspend fun editChat(chatId: Long, patch: ChatPatch): Chat =
        transport.execute("PATCH", listOf("chats", chatId.toString()), Chat::class.java, body = patch)

    /** Shows a bot activity such as "typing…" to chat members, or marks messages as read. */
    public suspend fun sendAction(chatId: Long, action: SenderAction): SimpleQueryResult =
        post(SimpleQueryResult::class.java, ActionRequestBody(action), "chats", chatId.toString(), "actions")

    /** Returns the pinned message of a chat or channel, or `null` if nothing is pinned. */
    public suspend fun getPinnedMessage(chatId: Long): Message? =
        get(GetPinnedMessageResult::class.java, "chats", chatId.toString(), "pin").message

    /**
     * Pins a message in a chat or channel.
     *
     * @param messageId [ru.securitytrip.maxapi.model.message.MessageBody.mid] of the message.
     * @param notify whether members get a system message about it; the server default is `true`.
     */
    public suspend fun pinMessage(chatId: Long, messageId: String, notify: Boolean? = null): SimpleQueryResult =
        transport.execute(
            "PUT", listOf("chats", chatId.toString(), "pin"), SimpleQueryResult::class.java,
            body = PinMessageBody(messageId, notify),
        )

    /** Removes the pinned message of a chat or channel. */
    public suspend fun unpinMessage(chatId: Long): SimpleQueryResult =
        delete(SimpleQueryResult::class.java, null, "chats", chatId.toString(), "pin")

    /** Returns the current bot's membership in a chat: admin status, permissions, join time. */
    public suspend fun getMembership(chatId: Long): ChatMember =
        get(ChatMember::class.java, "chats", chatId.toString(), "members", "me")

    /** Removes the bot from a chat or channel. */
    public suspend fun leaveChat(chatId: Long): SimpleQueryResult =
        delete(SimpleQueryResult::class.java, null, "chats", chatId.toString(), "members", "me")

    /**
     * Returns all administrators of a chat or channel.
     * @throws ForbiddenException if the bot is not an administrator.
     */
    public suspend fun getAdmins(chatId: Long): ChatMembersList =
        get(ChatMembersList::class.java, "chats", chatId.toString(), "members", "admins")

    /**
     * Appoints administrators of a chat or channel. The bot needs the `add_admins` permission.
     * @return `success = true` if all administrators were added.
     */
    public suspend fun postAdmins(chatId: Long, admins: List<ChatAdmin>): SimpleQueryResult =
        post(SimpleQueryResult::class.java, ChatAdminsList(admins), "chats", chatId.toString(), "members", "admins")

    /** Revokes administrator rights of [userId] in a chat or channel. */
    public suspend fun deleteAdmins(chatId: Long, userId: Long): SimpleQueryResult =
        delete(SimpleQueryResult::class.java, null, "chats", chatId.toString(), "members", "admins", userId.toString())

    /**
     * Returns members of a chat or channel page by page.
     *
     * ```kotlin
     * var marker: Long? = null
     * do {
     *     val page = client.getMembers(chatId, marker = marker, count = 100)
     *     page.members.forEach { println(it.firstName) }
     *     marker = page.marker
     * } while (marker != null)
     * ```
     *
     * @param userIds return membership of these users only; [marker] and [count] are then ignored.
     * @param marker [ChatMembersList.marker] of the previous page; `null` for the first page.
     * @param count page size, 1–100; the server default is 20.
     */
    public suspend fun getMembers(
        chatId: Long,
        userIds: Collection<Long>? = null,
        marker: Long? = null,
        count: Int? = null,
    ): ChatMembersList = transport.execute(
        "GET", listOf("chats", chatId.toString(), "members"), ChatMembersList::class.java,
        query = Query().add("user_ids", userIds).add("marker", marker).add("count", count),
    )

    /**
     * Adds up to 100 users to a chat. The bot needs the `add_remove_members` permission.
     *
     * Max no longer serves this method: it was restricted on 2026-09-09 and removed on 2026-09-30.
     * It is kept for compatibility (the API specification still lists it), but the call fails.
     *
     * @return which users could not be added and why.
     */
    @Deprecated("Max removed POST /chats/{chatId}/members (restricted on 2026-09-09, removed on 2026-09-30), so the server rejects this call. The Bot API has no replacement.")
    public suspend fun addMembers(chatId: Long, userIds: List<Long>): ModifyMembersResult =
        post(ModifyMembersResult::class.java, UserIdsList(userIds), "chats", chatId.toString(), "members")

    /**
     * Removes a member from a chat or channel. The bot needs the `add_remove_members` permission.
     * @param block also block the user; applies only to chats with a public or private link.
     */
    public suspend fun removeMember(chatId: Long, userId: Long, block: Boolean? = null): SimpleQueryResult =
        delete(
            SimpleQueryResult::class.java, Query().add("user_id", userId).add("block", block),
            "chats", chatId.toString(), "members",
        )

    // -------------------------------------------------------- subscriptions

    /** Returns the webhook subscriptions of the bot. Empty if the bot uses long polling. */
    public suspend fun getSubscriptions(): List<Subscription> =
        get(GetSubscriptionsResult::class.java, "subscriptions").subscriptions

    /**
     * Subscribes the bot to receive updates at [url] via webhook.
     *
     * After that, [getUpdates] stops working until [unsubscribe] is called. Max retries failed
     * deliveries with growing intervals for up to 8 hours and treats every response other than
     * `200 OK` as a failure.
     *
     * @param url HTTPS URL of your endpoint; the server must listen on port 443.
     * @param secret sent in the `X-Max-Bot-Api-Secret` header of every request, so you can verify
     *   that requests come from Max; 5–256 characters of `[A-Za-z0-9_-]`.
     * @param updateTypes update types to deliver; `null` or empty delivers all of them.
     */
    public suspend fun subscribe(
        url: String,
        secret: String? = null,
        updateTypes: Collection<UpdateType>? = null,
    ): SimpleQueryResult = post(
        SimpleQueryResult::class.java,
        SubscriptionRequestBody(url, secret, updateTypes?.takeIf { it.isNotEmpty() }?.mapTo(LinkedHashSet()) { it.value }),
        "subscriptions",
    )

    /** Removes the webhook subscription for [url]. Long polling becomes available again. */
    public suspend fun unsubscribe(url: String): SimpleQueryResult =
        delete(SimpleQueryResult::class.java, Query().add("url", url), "subscriptions")

    /**
     * Fetches updates via long polling: waits up to [timeout] seconds for new updates.
     *
     * Every update has a sequence number; [UpdateList.marker] points to the next one. Passing a
     * marker commits all updates before it. Without a marker the server returns everything since
     * the last commit. Calls should be at least 300 ms apart.
     *
     * Long polling is limited in speed and retention time; the API recommends webhooks for production.
     * [ru.securitytrip.maxapi.bot.MaxBot] runs this loop for you.
     *
     * @param limit maximum number of updates, 1–1000; the server default is 100.
     * @param timeout seconds to wait for updates, 0–90; the server default is 30.
     * @param marker marker from the previous response.
     * @param types update types to receive; `null` returns all of them.
     * @throws MaxApiException with status 405 while a webhook subscription is active.
     */
    public suspend fun getUpdates(
        limit: Int? = null,
        timeout: Int? = null,
        marker: Long? = null,
        types: Collection<UpdateType>? = null,
    ): UpdateList = transport.execute(
        "GET", listOf("updates"), UpdateList::class.java,
        query = Query().add("limit", limit).add("timeout", timeout).add("marker", marker).add("types", types),
        longPolling = true,
    )

    // -------------------------------------------------------------- uploads

    /**
     * Returns a URL to upload a file to. This is the first step of the raw upload flow;
     * [upload] and the `upload*` methods perform all steps for you.
     */
    public suspend fun getUploadUrl(type: UploadType): UploadEndpoint = transport.execute(
        "POST", listOf("uploads"), UploadEndpoint::class.java, query = Query().add("type", type),
    )

    /**
     * Uploads a file and returns an attachment ready for [NewMessageBody.attachments].
     *
     * The upload has three steps: get an upload URL, upload the file there as `multipart/form-data`,
     * and build the attachment from the returned token. The bot token is not sent to the upload host.
     *
     * Large media may still be processing when the message is sent; [sendMessage] retries in that case.
     * See [UploadType] for size limits.
     */
    public suspend fun upload(type: UploadType, source: UploadSource): AttachmentRequest = when (type) {
        UploadType.IMAGE -> uploadImage(source)
        UploadType.VIDEO -> uploadVideo(source)
        UploadType.AUDIO -> uploadAudio(source)
        UploadType.FILE -> uploadFile(source)
    }

    /**
     * Uploads an image (JPG, PNG, GIF, TIFF, BMP, HEIC; up to 50 MB and 7680×7680 px).
     *
     * ```kotlin
     * val photo = client.uploadImage(UploadSource.of(File("cat.png")))
     * client.sendMessageToChat(chatId, message { text("A cat"); attach(photo) })
     * ```
     */
    public suspend fun uploadImage(source: UploadSource): PhotoAttachmentRequest {
        val endpoint = getUploadUrl(UploadType.IMAGE)
        val response = transport.upload(endpoint.url, source.fileName, source.body)
        val node = parseUploadResponse(response)
        val photos = node.get("photos")
        val payload = when {
            photos != null && photos.isObject && !photos.isEmpty ->
                PhotoAttachmentRequestPayload(photos = json.convertValue(photos, PHOTO_TOKENS))
            node.hasNonNull("token") -> PhotoAttachmentRequestPayload(token = node.get("token").asText())
            else -> throw MaxException("Image upload response contains no tokens: $response")
        }
        return PhotoAttachmentRequest(payload)
    }

    /** Uploads a video (MP4, MOV, MKV, WEBM; up to 250 MB). */
    public suspend fun uploadVideo(source: UploadSource): VideoAttachmentRequest =
        VideoAttachmentRequest(UploadedInfo(uploadForToken(UploadType.VIDEO, source)))

    /** Uploads audio (MP3, WAV, M4A and others; up to 256 MB and 60 minutes). It must be the only attachment of a message. */
    public suspend fun uploadAudio(source: UploadSource): AudioAttachmentRequest =
        AudioAttachmentRequest(UploadedInfo(uploadForToken(UploadType.AUDIO, source)))

    /** Uploads a file of any common format, up to 4 GB. It must be the only attachment of a message. */
    public suspend fun uploadFile(source: UploadSource): FileAttachmentRequest =
        FileAttachmentRequest(UploadedInfo(uploadForToken(UploadType.FILE, source)))

    // ------------------------------------------------------------- messages

    /**
     * Returns messages of a chat or channel, newest first. Set either [chatId] or [messageIds].
     *
     * Because messages go in reverse order, [before] must be greater than [after] when both are set.
     *
     * @param before only messages created before this time (Unix milliseconds).
     * @param after only messages created after this time (Unix milliseconds).
     * @param count maximum number of messages, 1–100; the server default is 50.
     * @throws ForbiddenException if the bot has no access to the chat or the user stopped the bot.
     */
    public suspend fun getMessages(
        chatId: Long? = null,
        messageIds: Collection<String>? = null,
        before: Long? = null,
        after: Long? = null,
        count: Int? = null,
    ): List<Message> = transport.execute(
        "GET", listOf("messages"), MessageList::class.java,
        query = Query().add("chat_id", chatId).add("message_ids", messageIds)
            .add("before", before).add("after", after).add("count", count),
    ).messages

    /**
     * Sends a message to a user ([userId]) or to a chat, channel or dialog ([chatId]).
     * Exactly one of them must be set; [sendMessageToChat] and [sendMessageToUser] are shorter.
     *
     * If an attached file is still being processed, the server answers `attachment.not.ready`
     * and the message is re-sent (see [MaxClientConfig.attachmentReadyRetries]).
     *
     * @param disableLinkPreview `true` disables previews for links in the text.
     * @return the created message, including its [ru.securitytrip.maxapi.model.message.MessageBody.mid].
     * @throws IllegalArgumentException if neither or both of [userId] and [chatId] are set, if
     *   [body] has no text, no attachments and does not forward a message (Max rejects such messages),
     *   or if it breaks the [MaxLimits] of Max, e.g. has more than 4000 characters of text (see
     *   [sendLongMessage]) or a keyboard that is too large.
     * @throws ForbiddenException if the user stopped the bot, the bot cannot write to the chat,
     *   or forwarding from the source chat is prohibited.
     */
    public suspend fun sendMessage(
        body: NewMessageBody,
        userId: Long? = null,
        chatId: Long? = null,
        disableLinkPreview: Boolean? = null,
    ): Message {
        require((userId == null) != (chatId == null)) { "Exactly one of userId or chatId must be set" }
        require(!body.text.isNullOrEmpty() || !body.attachments.isNullOrEmpty() || body.link?.type == MessageLinkType.FORWARD) {
            "A message needs text, attachments or a forwarded message"
        }
        MaxLimits.check(body)
        val query = Query().add("user_id", userId).add("chat_id", chatId).add("disable_link_preview", disableLinkPreview)
        return retryWhileAttachmentNotReady {
            transport.execute("POST", listOf("messages"), SendMessageResult::class.java, query, body)
        }.message
    }

    /** Sends a message to a chat, channel or dialog. See [sendMessage]. */
    public suspend fun sendMessageToChat(chatId: Long, body: NewMessageBody): Message =
        sendMessage(body, chatId = chatId)

    /** Sends a plain text message to a chat, channel or dialog. See [sendMessage]. */
    public suspend fun sendMessageToChat(chatId: Long, text: String): Message =
        sendMessage(NewMessageBody(text = text), chatId = chatId)

    /** Sends a message to a user in the dialog with the bot. See [sendMessage]. */
    public suspend fun sendMessageToUser(userId: Long, body: NewMessageBody): Message =
        sendMessage(body, userId = userId)

    /** Sends a plain text message to a user in the dialog with the bot. See [sendMessage]. */
    public suspend fun sendMessageToUser(userId: Long, text: String): Message =
        sendMessage(NewMessageBody(text = text), userId = userId)

    /**
     * Sends a message whose text may be longer than the 4000 characters of one message: such text
     * goes as several messages, split by [TextSplitter] in the [NewMessageBody.format] of [body]
     * (between paragraphs, lines or words, with markup kept valid in every part). Text that fits
     * goes as one message, exactly like [sendMessage].
     *
     * The attachments of [body], its keyboard included, go with the last message, the reply or
     * forward [NewMessageBody.link] with the first; every message has the same `notify` and format.
     * The messages are sent one after another, so if one fails, the earlier ones stay sent.
     *
     * ```kotlin
     * client.sendLongMessageToChat(chatId, message { markdown(report); keyboard { row { callback("OK", "ok") } } })
     * ```
     *
     * @return the sent messages in order.
     * @throws IllegalArgumentException as [sendMessage] does, except for text longer than 4000 characters.
     */
    public suspend fun sendLongMessage(
        body: NewMessageBody,
        userId: Long? = null,
        chatId: Long? = null,
        disableLinkPreview: Boolean? = null,
    ): List<Message> {
        val text = body.text
        if (text == null || text.length <= MaxLimits.TEXT_LENGTH) return listOf(sendMessage(body, userId, chatId, disableLinkPreview))
        require((userId == null) != (chatId == null)) { "Exactly one of userId or chatId must be set" }
        // Fail before the first part is sent rather than at the last one.
        MaxLimits.check(body.copy(text = null))
        val parts = TextSplitter.split(text, body.format)
        return parts.mapIndexed { index, part ->
            val message = body.copy(
                text = part,
                attachments = body.attachments.takeIf { index == parts.lastIndex },
                link = body.link.takeIf { index == 0 },
            )
            sendMessage(message, userId, chatId, disableLinkPreview)
        }
    }

    /** Sends a message of any length to a chat, channel or dialog. See [sendLongMessage]. */
    public suspend fun sendLongMessageToChat(chatId: Long, body: NewMessageBody): List<Message> =
        sendLongMessage(body, chatId = chatId)

    /** Sends a plain text of any length to a chat, channel or dialog. See [sendLongMessage]. */
    public suspend fun sendLongMessageToChat(chatId: Long, text: String): List<Message> =
        sendLongMessage(NewMessageBody(text = text), chatId = chatId)

    /** Sends a message of any length to a user in the dialog with the bot. See [sendLongMessage]. */
    public suspend fun sendLongMessageToUser(userId: Long, body: NewMessageBody): List<Message> =
        sendLongMessage(body, userId = userId)

    /** Sends a plain text of any length to a user in the dialog with the bot. See [sendLongMessage]. */
    public suspend fun sendLongMessageToUser(userId: Long, text: String): List<Message> =
        sendLongMessage(NewMessageBody(text = text), userId = userId)

    /**
     * Replaces the content of a message sent by the bot.
     *
     * [NewMessageBody.attachments] set to `null` keeps the current attachments; an empty list
     * removes all of them (see [ru.securitytrip.maxapi.dsl.MessageBuilder.clearAttachments]).
     *
     * @throws IllegalArgumentException if [body] breaks the [MaxLimits] of Max.
     */
    public suspend fun editMessage(messageId: String, body: NewMessageBody): SimpleQueryResult {
        MaxLimits.check(body)
        return retryWhileAttachmentNotReady {
            transport.execute(
                "PUT", listOf("messages"), SimpleQueryResult::class.java,
                query = Query().add("message_id", messageId), body = body,
            )
        }
    }

    /**
     * Deletes a message in a dialog, chat or channel. The bot needs permission to delete messages.
     * @throws ForbiddenException if the bot is not allowed to delete the message.
     */
    public suspend fun deleteMessage(messageId: String): SimpleQueryResult =
        delete(SimpleQueryResult::class.java, Query().add("message_id", messageId), "messages")

    /**
     * Returns a single message.
     * @throws NotFoundException if the message does not exist or the bot has no access to it.
     */
    public suspend fun getMessageById(messageId: String): Message = get(Message::class.java, "messages", messageId)

    /**
     * Returns direct playback URLs and metadata of a video.
     * @param videoToken [ru.securitytrip.maxapi.model.attachment.MediaAttachmentPayload.token] of a video attachment.
     */
    public suspend fun getVideoAttachmentDetails(videoToken: String): VideoAttachmentDetails =
        get(VideoAttachmentDetails::class.java, "videos", videoToken)

    /**
     * Answers a press of a callback button: updates the message that contains the button
     * and/or shows a one-time notification to the user. The API expects an answer to every press.
     *
     * @param callbackId [ru.securitytrip.maxapi.model.message.Callback.callbackId] from the update.
     * @param disableLinkPreview `true` disables previews for links in the updated text.
     * @throws IllegalArgumentException if [callbackId] is blank, or if [CallbackAnswer.message]
     *   breaks the [MaxLimits] of Max.
     */
    public suspend fun answerOnCallback(
        callbackId: String,
        answer: CallbackAnswer,
        disableLinkPreview: Boolean? = null,
    ): SimpleQueryResult {
        require(callbackId.isNotBlank()) { "callbackId must not be blank" }
        MaxLimits.check(answer)
        return retryWhileAttachmentNotReady {
            transport.execute(
                "POST", listOf("answers"), SimpleQueryResult::class.java,
                query = Query().add("callback_id", callbackId).add("disable_link_preview", disableLinkPreview),
                body = answer,
            )
        }
    }

    /** Answers a press of a callback button with a one-time notification. See [answerOnCallback]. */
    public suspend fun answerOnCallback(callbackId: String, notification: String): SimpleQueryResult =
        answerOnCallback(callbackId, CallbackAnswer(notification = notification))

    // ------------------------------------------------------------- comments

    /**
     * Returns comments to a channel post, newest first.
     *
     * @param messageId identifier of the commented post.
     * @param commentIds return only these comments.
     * @param before only comments created before this time (Unix milliseconds).
     * @param after only comments created after this time (Unix milliseconds).
     * @param count maximum number of comments, 1–100; the server default is 50.
     */
    public suspend fun getComments(
        messageId: String,
        commentIds: Collection<String>? = null,
        before: Long? = null,
        after: Long? = null,
        count: Int? = null,
    ): List<CommentMessage> = transport.execute(
        "GET", listOf("messages", messageId, "comments"), CommentMessageList::class.java,
        query = Query().add("comment_ids", commentIds).add("before", before).add("after", after).add("count", count),
    ).messages

    /**
     * Posts a comment to a channel post. Attachments are not allowed in comments.
     *
     * @param messageId identifier of the commented post.
     * @param disableLinkPreview `true` disables previews for links in the text.
     * @throws IllegalArgumentException if the text is longer than 4000 characters (see [MaxLimits]).
     */
    public suspend fun sendComment(
        messageId: String,
        body: NewCommentBody,
        disableLinkPreview: Boolean? = null,
    ): CommentMessage {
        MaxLimits.check(body)
        return transport.execute(
            "POST", listOf("messages", messageId, "comments"), SendCommentResult::class.java,
            query = Query().add("disable_link_preview", disableLinkPreview), body = body,
        ).message
    }

    /**
     * Replaces the content of a comment.
     * @throws IllegalArgumentException if the text is longer than 4000 characters (see [MaxLimits]).
     */
    public suspend fun editComment(messageId: String, commentId: String, body: NewCommentBody): SimpleQueryResult {
        MaxLimits.check(body)
        return transport.execute(
            "PUT", listOf("messages", messageId, "comments"), SimpleQueryResult::class.java,
            query = Query().add("comment_id", commentId), body = body,
        )
    }

    /** Deletes a comment. The bot needs permission to delete messages. */
    public suspend fun deleteComment(messageId: String, commentId: String): SimpleQueryResult =
        delete(SimpleQueryResult::class.java, Query().add("comment_id", commentId), "messages", messageId, "comments")

    /**
     * Returns a single comment.
     * @throws NotFoundException if the comment or the post does not exist or is not accessible.
     */
    public suspend fun getCommentById(messageId: String, commentId: String): CommentMessage =
        get(CommentMessage::class.java, "messages", messageId, "comments", commentId)

    // ---------------------------------------------------------------------

    /**
     * Cancels pending [async] calls and shuts down the HTTP client if the library created it.
     * An `OkHttpClient` passed in [MaxClientConfig.httpClient] is left open.
     */
    override fun close() {
        asyncScope.cancel()
        transport.close()
    }

    override fun toString(): String = "MaxClient(${config.baseUrl})"

    private suspend fun <T> get(type: Class<T>, vararg path: String): T =
        transport.execute("GET", path.asList(), type)

    private suspend fun <T> post(type: Class<T>, body: Any, vararg path: String): T =
        transport.execute("POST", path.asList(), type, body = body)

    private suspend fun <T> delete(type: Class<T>, query: Query?, vararg path: String): T =
        transport.execute("DELETE", path.asList(), type, query = query)

    private suspend fun uploadForToken(type: UploadType, source: UploadSource): String {
        val endpoint = getUploadUrl(type)
        val response = transport.upload(endpoint.url, source.fileName, source.body)
        // For video and audio the token comes together with the upload URL.
        endpoint.token?.takeIf { it.isNotBlank() }?.let { return it }
        val node = parseUploadResponse(response)
        val token = node.get("token")
        if (token == null || !token.isTextual) {
            throw MaxException("${type.value} upload response contains no token: $response")
        }
        return token.asText()
    }

    private fun parseUploadResponse(response: String) = try {
        json.readTree(response)
    } catch (e: JacksonException) {
        throw MaxException("Cannot parse upload response: ${e.originalMessage}", e)
    }

    private suspend fun <T> retryWhileAttachmentNotReady(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: MaxApiException) {
                if (!e.isAttachmentNotReady || attempt >= config.attachmentReadyRetries) throw e
                attempt++
                delay(config.attachmentReadyDelay.toMillis())
            }
        }
    }

    public companion object {
        private val PHOTO_TOKENS = object : TypeReference<Map<String, PhotoToken>>() {}

        /** Creates a client with default settings. Same as `MaxClient(token)`. */
        @JvmStatic
        public fun create(token: String): MaxClient = MaxClient(token)

        /** Creates a client with custom settings. Same as `MaxClient(config)`. */
        @JvmStatic
        public fun create(config: MaxClientConfig): MaxClient = MaxClient(config)
    }
}
