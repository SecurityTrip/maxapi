package ru.securitytrip.maxapi.model.attachment

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * An attachment of a received message. The concrete subclass is chosen by the `type` field:
 *
 * | `type`            | Class                        |
 * |-------------------|------------------------------|
 * | `image`           | [PhotoAttachment]            |
 * | `video`           | [VideoAttachment]            |
 * | `audio`           | [AudioAttachment]            |
 * | `file`            | [FileAttachment]             |
 * | `sticker`         | [StickerAttachment]          |
 * | `contact`         | [ContactAttachment]          |
 * | `inline_keyboard` | [InlineKeyboardAttachment]   |
 * | `share`           | [ShareAttachment]            |
 * | `location`        | [LocationAttachment]         |
 *
 * Types the library does not know yet become [UnknownAttachment], so new server features never
 * break deserialization.
 *
 * ```kotlin
 * when (val attachment = body.attachments?.firstOrNull()) {
 *     is PhotoAttachment -> println(attachment.payload.url)
 *     is LocationAttachment -> println("${attachment.latitude}, ${attachment.longitude}")
 *     else -> Unit
 * }
 * ```
 *
 * To send attachments, use [AttachmentRequest] instead.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
    defaultImpl = UnknownAttachment::class,
)
@JsonSubTypes(
    JsonSubTypes.Type(PhotoAttachment::class, name = "image"),
    JsonSubTypes.Type(VideoAttachment::class, name = "video"),
    JsonSubTypes.Type(AudioAttachment::class, name = "audio"),
    JsonSubTypes.Type(FileAttachment::class, name = "file"),
    JsonSubTypes.Type(StickerAttachment::class, name = "sticker"),
    JsonSubTypes.Type(ContactAttachment::class, name = "contact"),
    JsonSubTypes.Type(InlineKeyboardAttachment::class, name = "inline_keyboard"),
    JsonSubTypes.Type(ShareAttachment::class, name = "share"),
    JsonSubTypes.Type(LocationAttachment::class, name = "location"),
)
public sealed class Attachment {
    /** Wire type of the attachment, e.g. `"image"`. */
    public abstract val type: String
}
