package ru.securitytrip.maxapi.java

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.UploadSource
import ru.securitytrip.maxapi.model.SimpleQueryResult
import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.attachment.AudioAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.FileAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.UploadEndpoint
import ru.securitytrip.maxapi.model.attachment.UploadType
import ru.securitytrip.maxapi.model.attachment.VideoAttachmentDetails
import ru.securitytrip.maxapi.model.attachment.VideoAttachmentRequest
import ru.securitytrip.maxapi.model.chat.Chat
import ru.securitytrip.maxapi.model.chat.ChatAdmin
import ru.securitytrip.maxapi.model.chat.ChatMember
import ru.securitytrip.maxapi.model.chat.ChatMembersList
import ru.securitytrip.maxapi.model.chat.ChatPatch
import ru.securitytrip.maxapi.model.chat.ModifyMembersResult
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.CommentMessage
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.NewCommentBody
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.subscription.Subscription
import ru.securitytrip.maxapi.model.update.UpdateList
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.user.BotCommand
import ru.securitytrip.maxapi.model.user.BotCommandsInfo
import ru.securitytrip.maxapi.model.user.BotInfo

/**
 * Blocking facade of [MaxClient] for Java and other non-coroutine code.
 *
 * Every method has the same name, parameters and semantics as the corresponding [MaxClient] method
 * (see its documentation), and blocks the calling thread until the response arrives.
 * Optional parameters are exposed as Java overloads.
 *
 * ```java
 * MaxBlockingClient api = MaxClient.create(token).blocking();
 * BotInfo me = api.getMyInfo();
 * api.sendMessageToChat(chatId, "Hello");
 * ```
 *
 * Do not call these methods from a coroutine or an event loop thread; use [MaxClient] or
 * [MaxAsyncClient] there. Get an instance with [MaxClient.blocking].
 *
 * @property client the underlying coroutine client.
 */
public class MaxBlockingClient internal constructor(public val client: MaxClient) {

    public fun getMyInfo(): BotInfo = runBlocking { client.getMyInfo() }

    public fun editMyCommands(commands: List<BotCommand>): BotCommandsInfo =
        runBlocking { client.editMyCommands(commands) }

    public fun getChat(chatId: Long): Chat = runBlocking { client.getChat(chatId) }

    public fun editChat(chatId: Long, patch: ChatPatch): Chat = runBlocking { client.editChat(chatId, patch) }

    public fun sendAction(chatId: Long, action: SenderAction): SimpleQueryResult =
        runBlocking { client.sendAction(chatId, action) }

    public fun getPinnedMessage(chatId: Long): Message? = runBlocking { client.getPinnedMessage(chatId) }

    @JvmOverloads
    public fun pinMessage(chatId: Long, messageId: String, notify: Boolean? = null): SimpleQueryResult =
        runBlocking { client.pinMessage(chatId, messageId, notify) }

    public fun unpinMessage(chatId: Long): SimpleQueryResult = runBlocking { client.unpinMessage(chatId) }

    public fun getMembership(chatId: Long): ChatMember = runBlocking { client.getMembership(chatId) }

    public fun leaveChat(chatId: Long): SimpleQueryResult = runBlocking { client.leaveChat(chatId) }

    public fun getAdmins(chatId: Long): ChatMembersList = runBlocking { client.getAdmins(chatId) }

    public fun postAdmins(chatId: Long, admins: List<ChatAdmin>): SimpleQueryResult =
        runBlocking { client.postAdmins(chatId, admins) }

    public fun deleteAdmins(chatId: Long, userId: Long): SimpleQueryResult =
        runBlocking { client.deleteAdmins(chatId, userId) }

    @JvmOverloads
    public fun getMembers(
        chatId: Long,
        userIds: Collection<Long>? = null,
        marker: Long? = null,
        count: Int? = null,
    ): ChatMembersList = runBlocking { client.getMembers(chatId, userIds, marker, count) }

    /** Not served by Max any more; see [MaxClient.addMembers]. */
    @Deprecated("Max removed POST /chats/{chatId}/members (restricted on 2026-09-09, removed on 2026-09-30), so the server rejects this call. The Bot API has no replacement.")
    public fun addMembers(chatId: Long, userIds: List<Long>): ModifyMembersResult =
        runBlocking { @Suppress("DEPRECATION") client.addMembers(chatId, userIds) }

    @JvmOverloads
    public fun removeMember(chatId: Long, userId: Long, block: Boolean? = null): SimpleQueryResult =
        runBlocking { client.removeMember(chatId, userId, block) }

    public fun getSubscriptions(): List<Subscription> = runBlocking { client.getSubscriptions() }

    @JvmOverloads
    public fun subscribe(
        url: String,
        secret: String? = null,
        updateTypes: Collection<UpdateType>? = null,
    ): SimpleQueryResult = runBlocking { client.subscribe(url, secret, updateTypes) }

    public fun unsubscribe(url: String): SimpleQueryResult = runBlocking { client.unsubscribe(url) }

    @JvmOverloads
    public fun getUpdates(
        limit: Int? = null,
        timeout: Int? = null,
        marker: Long? = null,
        types: Collection<UpdateType>? = null,
    ): UpdateList = runBlocking { client.getUpdates(limit, timeout, marker, types) }

    public fun getUploadUrl(type: UploadType): UploadEndpoint = runBlocking { client.getUploadUrl(type) }

    public fun upload(type: UploadType, source: UploadSource): AttachmentRequest =
        runBlocking { client.upload(type, source) }

    public fun uploadImage(source: UploadSource): PhotoAttachmentRequest = runBlocking { client.uploadImage(source) }

    public fun uploadVideo(source: UploadSource): VideoAttachmentRequest = runBlocking { client.uploadVideo(source) }

    public fun uploadAudio(source: UploadSource): AudioAttachmentRequest = runBlocking { client.uploadAudio(source) }

    public fun uploadFile(source: UploadSource): FileAttachmentRequest = runBlocking { client.uploadFile(source) }

    @JvmOverloads
    public fun getMessages(
        chatId: Long? = null,
        messageIds: Collection<String>? = null,
        before: Long? = null,
        after: Long? = null,
        count: Int? = null,
    ): List<Message> = runBlocking { client.getMessages(chatId, messageIds, before, after, count) }

    @JvmOverloads
    public fun sendMessage(
        body: NewMessageBody,
        userId: Long? = null,
        chatId: Long? = null,
        disableLinkPreview: Boolean? = null,
    ): Message = runBlocking { client.sendMessage(body, userId, chatId, disableLinkPreview) }

    public fun sendMessageToChat(chatId: Long, body: NewMessageBody): Message =
        runBlocking { client.sendMessageToChat(chatId, body) }

    public fun sendMessageToChat(chatId: Long, text: String): Message =
        runBlocking { client.sendMessageToChat(chatId, text) }

    public fun sendMessageToUser(userId: Long, body: NewMessageBody): Message =
        runBlocking { client.sendMessageToUser(userId, body) }

    public fun sendMessageToUser(userId: Long, text: String): Message =
        runBlocking { client.sendMessageToUser(userId, text) }

    @JvmOverloads
    public fun sendLongMessage(
        body: NewMessageBody,
        userId: Long? = null,
        chatId: Long? = null,
        disableLinkPreview: Boolean? = null,
    ): List<Message> = runBlocking { client.sendLongMessage(body, userId, chatId, disableLinkPreview) }

    public fun sendLongMessageToChat(chatId: Long, body: NewMessageBody): List<Message> =
        runBlocking { client.sendLongMessageToChat(chatId, body) }

    public fun sendLongMessageToChat(chatId: Long, text: String): List<Message> =
        runBlocking { client.sendLongMessageToChat(chatId, text) }

    public fun sendLongMessageToUser(userId: Long, body: NewMessageBody): List<Message> =
        runBlocking { client.sendLongMessageToUser(userId, body) }

    public fun sendLongMessageToUser(userId: Long, text: String): List<Message> =
        runBlocking { client.sendLongMessageToUser(userId, text) }

    public fun editMessage(messageId: String, body: NewMessageBody): SimpleQueryResult =
        runBlocking { client.editMessage(messageId, body) }

    public fun deleteMessage(messageId: String): SimpleQueryResult = runBlocking { client.deleteMessage(messageId) }

    public fun getMessageById(messageId: String): Message = runBlocking { client.getMessageById(messageId) }

    public fun getVideoAttachmentDetails(videoToken: String): VideoAttachmentDetails =
        runBlocking { client.getVideoAttachmentDetails(videoToken) }

    @JvmOverloads
    public fun answerOnCallback(
        callbackId: String,
        answer: CallbackAnswer,
        disableLinkPreview: Boolean? = null,
    ): SimpleQueryResult = runBlocking { client.answerOnCallback(callbackId, answer, disableLinkPreview) }

    public fun answerOnCallback(callbackId: String, notification: String): SimpleQueryResult =
        runBlocking { client.answerOnCallback(callbackId, notification) }

    @JvmOverloads
    public fun getComments(
        messageId: String,
        commentIds: Collection<String>? = null,
        before: Long? = null,
        after: Long? = null,
        count: Int? = null,
    ): List<CommentMessage> = runBlocking { client.getComments(messageId, commentIds, before, after, count) }

    @JvmOverloads
    public fun sendComment(
        messageId: String,
        body: NewCommentBody,
        disableLinkPreview: Boolean? = null,
    ): CommentMessage = runBlocking { client.sendComment(messageId, body, disableLinkPreview) }

    public fun editComment(messageId: String, commentId: String, body: NewCommentBody): SimpleQueryResult =
        runBlocking { client.editComment(messageId, commentId, body) }

    public fun deleteComment(messageId: String, commentId: String): SimpleQueryResult =
        runBlocking { client.deleteComment(messageId, commentId) }

    public fun getCommentById(messageId: String, commentId: String): CommentMessage =
        runBlocking { client.getCommentById(messageId, commentId) }
}
