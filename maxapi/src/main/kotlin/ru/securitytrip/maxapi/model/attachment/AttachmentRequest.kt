package ru.securitytrip.maxapi.model.attachment

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import ru.securitytrip.maxapi.model.message.NewMessageBody

/**
 * An attachment of an outgoing message, put into [NewMessageBody.attachments].
 *
 * Images, videos, audio and files have to be uploaded first. `MaxClient.uploadImage`,
 * `uploadVideo`, `uploadAudio` and `uploadFile` do the whole upload and return a ready request:
 *
 * ```kotlin
 * val photo = client.uploadImage(UploadSource.of(File("cat.png")))
 * client.sendMessageToChat(chatId, message { text("A cat"); attach(photo) })
 * ```
 *
 * Media that is already in Max can be sent again by token, e.g. `PhotoAttachmentRequest.ofToken(token)`.
 *
 * Audio, file, sticker and contact attachments must be the only attachment of a message.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(PhotoAttachmentRequest::class, name = "image"),
    JsonSubTypes.Type(VideoAttachmentRequest::class, name = "video"),
    JsonSubTypes.Type(AudioAttachmentRequest::class, name = "audio"),
    JsonSubTypes.Type(FileAttachmentRequest::class, name = "file"),
    JsonSubTypes.Type(StickerAttachmentRequest::class, name = "sticker"),
    JsonSubTypes.Type(ContactAttachmentRequest::class, name = "contact"),
    JsonSubTypes.Type(InlineKeyboardAttachmentRequest::class, name = "inline_keyboard"),
    JsonSubTypes.Type(LocationAttachmentRequest::class, name = "location"),
    JsonSubTypes.Type(ShareAttachmentRequest::class, name = "share"),
)
public sealed class AttachmentRequest {
    /** Wire type of the attachment, e.g. `"image"`. */
    public abstract val type: String
}
