package ru.securitytrip.maxapi.java

import kotlinx.coroutines.future.future
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
import java.util.concurrent.CompletableFuture

/**
 * Asynchronous facade of [MaxClient] for Java: every method returns a [CompletableFuture] immediately.
 *
 * Every method has the same name, parameters and semantics as the corresponding [MaxClient] method
 * (see its documentation). The future completes with the result, or exceptionally with a
 * [ru.securitytrip.maxapi.client.MaxException]. Cancelling the future cancels the HTTP request.
 * Optional parameters are exposed as Java overloads.
 *
 * ```java
 * MaxAsyncClient api = MaxClient.create(token).async();
 * api.getMyInfo()
 *    .thenCompose(me -> api.sendMessageToChat(chatId, "I am " + me.getFirstName()))
 *    .exceptionally(error -> { log.error("Failed", error); return null; });
 * ```
 *
 * Futures run on the client's own coroutine scope and are cancelled by [MaxClient.close].
 * Get an instance with [MaxClient.async].
 *
 * @property client the underlying coroutine client.
 */
public class MaxAsyncClient internal constructor(public val client: MaxClient) {

    public fun getMyInfo(): CompletableFuture<BotInfo> = async { client.getMyInfo() }

    public fun editMyCommands(commands: List<BotCommand>): CompletableFuture<BotCommandsInfo> =
        async { client.editMyCommands(commands) }

    public fun getChat(chatId: Long): CompletableFuture<Chat> = async { client.getChat(chatId) }

    public fun editChat(chatId: Long, patch: ChatPatch): CompletableFuture<Chat> = async { client.editChat(chatId, patch) }

    public fun sendAction(chatId: Long, action: SenderAction): CompletableFuture<SimpleQueryResult> =
        async { client.sendAction(chatId, action) }

    public fun getPinnedMessage(chatId: Long): CompletableFuture<Message?> = async { client.getPinnedMessage(chatId) }

    @JvmOverloads
    public fun pinMessage(chatId: Long, messageId: String, notify: Boolean? = null): CompletableFuture<SimpleQueryResult> =
        async { client.pinMessage(chatId, messageId, notify) }

    public fun unpinMessage(chatId: Long): CompletableFuture<SimpleQueryResult> = async { client.unpinMessage(chatId) }

    public fun getMembership(chatId: Long): CompletableFuture<ChatMember> = async { client.getMembership(chatId) }

    public fun leaveChat(chatId: Long): CompletableFuture<SimpleQueryResult> = async { client.leaveChat(chatId) }

    public fun getAdmins(chatId: Long): CompletableFuture<ChatMembersList> = async { client.getAdmins(chatId) }

    public fun postAdmins(chatId: Long, admins: List<ChatAdmin>): CompletableFuture<SimpleQueryResult> =
        async { client.postAdmins(chatId, admins) }

    public fun deleteAdmins(chatId: Long, userId: Long): CompletableFuture<SimpleQueryResult> =
        async { client.deleteAdmins(chatId, userId) }

    @JvmOverloads
    public fun getMembers(
        chatId: Long,
        userIds: Collection<Long>? = null,
        marker: Long? = null,
        count: Int? = null,
    ): CompletableFuture<ChatMembersList> = async { client.getMembers(chatId, userIds, marker, count) }

    public fun addMembers(chatId: Long, userIds: List<Long>): CompletableFuture<ModifyMembersResult> =
        async { client.addMembers(chatId, userIds) }

    @JvmOverloads
    public fun removeMember(chatId: Long, userId: Long, block: Boolean? = null): CompletableFuture<SimpleQueryResult> =
        async { client.removeMember(chatId, userId, block) }

    public fun getSubscriptions(): CompletableFuture<List<Subscription>> = async { client.getSubscriptions() }

    @JvmOverloads
    public fun subscribe(
        url: String,
        secret: String? = null,
        updateTypes: Collection<UpdateType>? = null,
    ): CompletableFuture<SimpleQueryResult> = async { client.subscribe(url, secret, updateTypes) }

    public fun unsubscribe(url: String): CompletableFuture<SimpleQueryResult> = async { client.unsubscribe(url) }

    @JvmOverloads
    public fun getUpdates(
        limit: Int? = null,
        timeout: Int? = null,
        marker: Long? = null,
        types: Collection<UpdateType>? = null,
    ): CompletableFuture<UpdateList> = async { client.getUpdates(limit, timeout, marker, types) }

    public fun getUploadUrl(type: UploadType): CompletableFuture<UploadEndpoint> = async { client.getUploadUrl(type) }

    public fun upload(type: UploadType, source: UploadSource): CompletableFuture<AttachmentRequest> =
        async { client.upload(type, source) }

    public fun uploadImage(source: UploadSource): CompletableFuture<PhotoAttachmentRequest> = async { client.uploadImage(source) }

    public fun uploadVideo(source: UploadSource): CompletableFuture<VideoAttachmentRequest> = async { client.uploadVideo(source) }

    public fun uploadAudio(source: UploadSource): CompletableFuture<AudioAttachmentRequest> = async { client.uploadAudio(source) }

    public fun uploadFile(source: UploadSource): CompletableFuture<FileAttachmentRequest> = async { client.uploadFile(source) }

    @JvmOverloads
    public fun getMessages(
        chatId: Long? = null,
        messageIds: Collection<String>? = null,
        before: Long? = null,
        after: Long? = null,
        count: Int? = null,
    ): CompletableFuture<List<Message>> = async { client.getMessages(chatId, messageIds, before, after, count) }

    @JvmOverloads
    public fun sendMessage(
        body: NewMessageBody,
        userId: Long? = null,
        chatId: Long? = null,
        disableLinkPreview: Boolean? = null,
    ): CompletableFuture<Message> = async { client.sendMessage(body, userId, chatId, disableLinkPreview) }

    public fun sendMessageToChat(chatId: Long, body: NewMessageBody): CompletableFuture<Message> =
        async { client.sendMessageToChat(chatId, body) }

    public fun sendMessageToChat(chatId: Long, text: String): CompletableFuture<Message> =
        async { client.sendMessageToChat(chatId, text) }

    public fun sendMessageToUser(userId: Long, body: NewMessageBody): CompletableFuture<Message> =
        async { client.sendMessageToUser(userId, body) }

    public fun sendMessageToUser(userId: Long, text: String): CompletableFuture<Message> =
        async { client.sendMessageToUser(userId, text) }

    public fun editMessage(messageId: String, body: NewMessageBody): CompletableFuture<SimpleQueryResult> =
        async { client.editMessage(messageId, body) }

    public fun deleteMessage(messageId: String): CompletableFuture<SimpleQueryResult> = async { client.deleteMessage(messageId) }

    public fun getMessageById(messageId: String): CompletableFuture<Message> = async { client.getMessageById(messageId) }

    public fun getVideoAttachmentDetails(videoToken: String): CompletableFuture<VideoAttachmentDetails> =
        async { client.getVideoAttachmentDetails(videoToken) }

    @JvmOverloads
    public fun answerOnCallback(
        callbackId: String,
        answer: CallbackAnswer,
        disableLinkPreview: Boolean? = null,
    ): CompletableFuture<SimpleQueryResult> = async { client.answerOnCallback(callbackId, answer, disableLinkPreview) }

    public fun answerOnCallback(callbackId: String, notification: String): CompletableFuture<SimpleQueryResult> =
        async { client.answerOnCallback(callbackId, notification) }

    @JvmOverloads
    public fun getComments(
        messageId: String,
        commentIds: Collection<String>? = null,
        before: Long? = null,
        after: Long? = null,
        count: Int? = null,
    ): CompletableFuture<List<CommentMessage>> = async { client.getComments(messageId, commentIds, before, after, count) }

    @JvmOverloads
    public fun sendComment(
        messageId: String,
        body: NewCommentBody,
        disableLinkPreview: Boolean? = null,
    ): CompletableFuture<CommentMessage> = async { client.sendComment(messageId, body, disableLinkPreview) }

    public fun editComment(messageId: String, commentId: String, body: NewCommentBody): CompletableFuture<SimpleQueryResult> =
        async { client.editComment(messageId, commentId, body) }

    public fun deleteComment(messageId: String, commentId: String): CompletableFuture<SimpleQueryResult> =
        async { client.deleteComment(messageId, commentId) }

    public fun getCommentById(messageId: String, commentId: String): CompletableFuture<CommentMessage> =
        async { client.getCommentById(messageId, commentId) }

    private fun <T> async(block: suspend () -> T): CompletableFuture<T> = client.asyncScope.future { block() }
}
