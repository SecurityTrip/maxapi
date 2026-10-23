package ru.securitytrip.maxapi.model.keyboard

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate

/**
 * A button of an inline keyboard. The concrete subclass is chosen by the `type` field:
 *
 * | `type`                 | Class                       | What happens on press                         |
 * |------------------------|-----------------------------|-----------------------------------------------|
 * | `callback`             | [CallbackButton]            | the bot receives a [MessageCallbackUpdate]    |
 * | `link`                 | [LinkButton]                | the user opens a URL                          |
 * | `message`              | [MessageButton]             | the user sends the button text to the chat    |
 * | `request_contact`      | [RequestContactButton]      | the user shares their contact                 |
 * | `request_geo_location` | [RequestGeoLocationButton]  | the user shares their location                |
 * | `open_app`             | [OpenAppButton]             | a mini app opens                              |
 * | `clipboard`            | [ClipboardButton]           | the payload is copied to the clipboard        |
 *
 * Create buttons with [Buttons][ru.securitytrip.maxapi.dsl.Buttons] or the DSL
 * `keyboard { row { callback("Yes", "yes") } }`. Unknown types become [UnknownButton].
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
    defaultImpl = UnknownButton::class,
)
@JsonSubTypes(
    JsonSubTypes.Type(CallbackButton::class, name = "callback"),
    JsonSubTypes.Type(LinkButton::class, name = "link"),
    JsonSubTypes.Type(RequestGeoLocationButton::class, name = "request_geo_location"),
    JsonSubTypes.Type(RequestContactButton::class, name = "request_contact"),
    JsonSubTypes.Type(MessageButton::class, name = "message"),
    JsonSubTypes.Type(OpenAppButton::class, name = "open_app"),
    JsonSubTypes.Type(ClipboardButton::class, name = "clipboard"),
)
public sealed class Button {
    /** Wire type of the button, e.g. `"callback"`. */
    public abstract val type: String

    /** Visible text, 1–128 characters. */
    public abstract val text: String
}
