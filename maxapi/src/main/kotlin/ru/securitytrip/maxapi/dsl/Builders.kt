package ru.securitytrip.maxapi.dsl

import ru.securitytrip.maxapi.client.MaxLimits
import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.keyboard.ClipboardButton
import ru.securitytrip.maxapi.model.keyboard.LinkButton
import ru.securitytrip.maxapi.model.keyboard.MessageButton
import ru.securitytrip.maxapi.model.keyboard.OpenAppButton
import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.keyboard.RequestGeoLocationButton
import ru.securitytrip.maxapi.model.markup.TextFormat
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.message.NewMessageLink

/** Marks the message-building DSL so that inner blocks cannot accidentally call outer builders. */
@DslMarker
public annotation class MaxDsl

/**
 * Factory methods for keyboard buttons, convenient from Java:
 *
 * ```java
 * NewMessageBody.builder()
 *         .text("Do you like it?")
 *         .keyboardRow(Buttons.callback("Yes", "yes"), Buttons.callback("No", "no"))
 *         .keyboardRow(Buttons.link("Website", "https://example.com"))
 *         .build();
 * ```
 *
 * See [Button] for what each kind of button does.
 */
public object Buttons {
    /** A [CallbackButton]: the bot receives [payload] (up to 1024 characters) when it is pressed. */
    @JvmStatic
    public fun callback(text: String, payload: String): CallbackButton = CallbackButton(text, payload)

    /** A [LinkButton] that opens [url]. */
    @JvmStatic
    public fun link(text: String, url: String): LinkButton = LinkButton(text, url)

    /** A [RequestContactButton] that asks the user to share their contact. */
    @JvmStatic
    public fun requestContact(text: String): RequestContactButton = RequestContactButton(text)

    /**
     * A [RequestGeoLocationButton] that asks the user to share their location.
     * @param quick send the location without asking the user for confirmation.
     */
    @JvmStatic
    @JvmOverloads
    public fun requestGeoLocation(text: String, quick: Boolean = false): RequestGeoLocationButton =
        RequestGeoLocationButton(text, quick.takeIf { it })

    /** A [MessageButton] that sends its [text] to the chat on behalf of the user. */
    @JvmStatic
    public fun message(text: String): MessageButton = MessageButton(text)

    /**
     * An [OpenAppButton] that opens the mini app of the bot [webApp].
     * @param payload data passed to the mini app, up to 512 characters of `[A-Za-z0-9_-]`.
     * @throws IllegalArgumentException if [payload] breaks these limits, which Max would drop it for.
     */
    @JvmStatic
    @JvmOverloads
    public fun openApp(text: String, webApp: String, payload: String? = null, contactId: Long? = null): OpenAppButton {
        payload?.let(MaxLimits::checkOpenAppPayload)
        return OpenAppButton(text, webApp, payload, contactId)
    }

    /** A [ClipboardButton] that copies [payload] to the clipboard. */
    @JvmStatic
    public fun clipboard(text: String, payload: String): ClipboardButton = ClipboardButton(text, payload)
}

/** One row of buttons inside `keyboard { row { ... } }`. Methods mirror [Buttons]. */
@MaxDsl
public class RowBuilder internal constructor() {
    internal val buttons = mutableListOf<Button>()

    /** Adds any button, e.g. one created with [Buttons]. */
    public fun button(button: Button) {
        buttons += button
    }

    public fun callback(text: String, payload: String): Unit = button(Buttons.callback(text, payload))
    public fun link(text: String, url: String): Unit = button(Buttons.link(text, url))
    public fun requestContact(text: String): Unit = button(Buttons.requestContact(text))
    public fun requestGeoLocation(text: String, quick: Boolean = false): Unit =
        button(Buttons.requestGeoLocation(text, quick))

    public fun message(text: String): Unit = button(Buttons.message(text))
    public fun openApp(text: String, webApp: String, payload: String? = null, contactId: Long? = null): Unit =
        button(Buttons.openApp(text, webApp, payload, contactId))

    public fun clipboard(text: String, payload: String): Unit = button(Buttons.clipboard(text, payload))
}

/**
 * Builds an inline keyboard row by row.
 *
 * Kotlin:
 * ```kotlin
 * val kb = keyboard {
 *     row { callback("Yes", "yes"); callback("No", "no") }
 *     row { link("Help", "https://example.com/help") }
 * }
 * ```
 *
 * Java:
 * ```java
 * InlineKeyboardAttachmentRequest kb = new KeyboardBuilder()
 *         .row(Buttons.callback("Yes", "yes"), Buttons.callback("No", "no"))
 *         .build();
 * ```
 *
 * Empty rows are skipped.
 */
@MaxDsl
public class KeyboardBuilder {
    private val rows = mutableListOf<List<Button>>()

    /** Adds a row of the given buttons. */
    public fun row(vararg buttons: Button): KeyboardBuilder = row(buttons.asList())

    /** Adds a row of the given buttons. */
    public fun row(buttons: List<Button>): KeyboardBuilder = apply {
        if (buttons.isNotEmpty()) rows += buttons.toList()
    }

    /** Adds a row built with the DSL. */
    public fun row(block: RowBuilder.() -> Unit): KeyboardBuilder = row(RowBuilder().apply(block).buttons)

    internal fun isEmpty(): Boolean = rows.isEmpty()

    /**
     * Returns the keyboard attachment.
     * @throws IllegalStateException if no buttons were added.
     */
    public fun build(): InlineKeyboardAttachmentRequest {
        check(rows.isNotEmpty()) { "Keyboard must contain at least one button" }
        return InlineKeyboardAttachmentRequest(rows.toList())
    }
}

/**
 * Builds a [NewMessageBody].
 *
 * Kotlin, via [message] or `ctx.reply { ... }` in bot handlers:
 * ```kotlin
 * val body = message {
 *     markdown("**Order #42** is ready")
 *     replyTo(originalMid)
 *     keyboard { row { callback("Pick up", "pickup:42") } }
 * }
 * ```
 *
 * Java, via [NewMessageBody.builder]:
 * ```java
 * NewMessageBody body = NewMessageBody.builder()
 *         .html("<b>Order #42</b> is ready")
 *         .keyboardRow(Buttons.callback("Pick up", "pickup:42"))
 *         .build();
 * ```
 *
 * Keyboard rows from [keyboard] and [keyboardRow] are collected into a single keyboard,
 * which is appended after the other attachments.
 */
@MaxDsl
public class MessageBuilder {
    private var text: String? = null
    private var format: TextFormat? = null
    private var notify: Boolean? = null
    private var link: NewMessageLink? = null
    private var attachments: MutableList<AttachmentRequest>? = null
    private val keyboard = KeyboardBuilder()

    /** Message text, up to 4000 characters. */
    public fun text(value: String?): MessageBuilder = apply { text = value }

    /** How to interpret formatting in the text; `null` sends plain text. */
    public fun format(value: TextFormat?): MessageBuilder = apply { format = value }

    /** Sets the text and the [TextFormat.MARKDOWN] format. */
    public fun markdown(value: String): MessageBuilder = text(value).format(TextFormat.MARKDOWN)

    /** Sets the text and the [TextFormat.HTML] format. */
    public fun html(value: String): MessageBuilder = text(value).format(TextFormat.HTML)

    /** `false` delivers the message silently. Channels reject `false`. */
    public fun notify(value: Boolean): MessageBuilder = apply { notify = value }

    /** Makes the message a reply to the message with identifier [mid]. */
    public fun replyTo(mid: String): MessageBuilder = apply { link = NewMessageLink(MessageLinkType.REPLY, mid) }

    /** Makes the message forward the message with identifier [mid]. */
    public fun forward(mid: String): MessageBuilder = apply { link = NewMessageLink(MessageLinkType.FORWARD, mid) }

    /** Adds an attachment, e.g. the result of `MaxClient.uploadImage`. */
    public fun attach(attachment: AttachmentRequest): MessageBuilder = apply {
        (attachments ?: mutableListOf<AttachmentRequest>().also { attachments = it }) += attachment
    }

    /**
     * Sends an explicitly empty list of attachments. When the body is used with
     * `MaxClient.editMessage`, this removes all attachments of the message; without it,
     * attachments are left unchanged.
     */
    public fun clearAttachments(): MessageBuilder = apply { attachments = mutableListOf() }

    /** Adds a row of buttons to the message keyboard. */
    public fun keyboardRow(vararg buttons: Button): MessageBuilder = apply { keyboard.row(*buttons) }

    /** Adds rows of buttons to the message keyboard with the DSL. */
    public fun keyboard(block: KeyboardBuilder.() -> Unit): MessageBuilder = apply { keyboard.block() }

    /** Returns the message body. */
    public fun build(): NewMessageBody {
        val all = when {
            keyboard.isEmpty() -> attachments?.toList()
            else -> attachments.orEmpty() + keyboard.build()
        }
        return NewMessageBody(text = text, attachments = all, link = link, notify = notify, format = format)
    }
}

/**
 * Builds a [NewMessageBody] with the DSL:
 *
 * ```kotlin
 * client.sendMessageToChat(chatId, message {
 *     text("Choose:")
 *     keyboard { row { callback("A", "a"); callback("B", "b") } }
 * })
 * ```
 */
public fun message(block: MessageBuilder.() -> Unit): NewMessageBody = MessageBuilder().apply(block).build()

/**
 * Builds an inline keyboard attachment with the DSL:
 *
 * ```kotlin
 * val kb = keyboard { row { callback("Yes", "yes"); callback("No", "no") } }
 * ```
 *
 * @throws IllegalStateException if no buttons were added.
 */
public fun keyboard(block: KeyboardBuilder.() -> Unit): InlineKeyboardAttachmentRequest =
    KeyboardBuilder().apply(block).build()
