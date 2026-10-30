package ru.securitytrip.maxapi.client

import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.keyboard.ClipboardButton
import ru.securitytrip.maxapi.model.keyboard.LinkButton
import ru.securitytrip.maxapi.model.keyboard.OpenAppButton
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.NewCommentBody
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.user.BotCommand

/**
 * Limits of Max on what a bot sends. [MaxClient] checks them before sending a message, a comment,
 * an answer to a button press or the bot commands, and throws [IllegalArgumentException] instead
 * of making a request the server would reject.
 *
 * Lengths are counted like [String.length], in UTF-16 code units, because the server counts them
 * that way: an emoji such as 😀 takes 2 of the 4000 characters of a message, a flag 4. Markup of
 * Markdown and HTML counts too.
 */
public object MaxLimits {
    /** Characters in the text of a message or a comment. Split longer text with [TextSplitter]. */
    public const val TEXT_LENGTH: Int = 4000

    /** Buttons in a keyboard. */
    public const val KEYBOARD_BUTTONS: Int = 210

    /** Rows in a keyboard. */
    public const val KEYBOARD_ROWS: Int = 30

    /** Buttons in a row of a keyboard. */
    public const val BUTTONS_PER_ROW: Int = 7

    /** Buttons in a row of a keyboard that has a button of one of the [WIDE_BUTTON_TYPES]. */
    public const val WIDE_BUTTONS_PER_ROW: Int = 3

    /** Types of buttons that limit their row to [WIDE_BUTTONS_PER_ROW] buttons. */
    @JvmField
    public val WIDE_BUTTON_TYPES: Set<String> = setOf("link", "open_app", "request_geo_location", "request_contact")

    /** Characters in the text of a button; it must not be empty. */
    public const val BUTTON_TEXT_LENGTH: Int = 128

    /** Characters in the payload of a callback or clipboard button. */
    public const val BUTTON_PAYLOAD_LENGTH: Int = 1024

    /** Characters in the URL of a link button. */
    public const val LINK_URL_LENGTH: Int = 2048

    /** Characters in the payload of an `open_app` button, which may hold only `A-Z`, `a-z`, `0-9`, `_` and `-`. */
    public const val OPEN_APP_PAYLOAD_LENGTH: Int = 512

    /** Commands in the bot menu. */
    public const val COMMANDS: Int = 32

    /** Characters in the name of a command; it must not be empty. */
    public const val COMMAND_NAME_LENGTH: Int = 64

    /** Characters in the description of a command; if set, it must not be empty. */
    public const val COMMAND_DESCRIPTION_LENGTH: Int = 128

    private val OPEN_APP_PAYLOAD = Regex("[A-Za-z0-9_-]*")

    // ------------------------------------------------------------ checks

    internal fun check(body: NewMessageBody) {
        checkText(body.text, "Message")
        body.attachments?.forEach(::check)
    }

    internal fun check(body: NewCommentBody) {
        checkText(body.text, "Comment")
    }

    internal fun check(answer: CallbackAnswer) {
        answer.message?.let(::check)
    }

    internal fun checkCommands(commands: List<BotCommand>) {
        require(commands.size <= COMMANDS) { "${commands.size} commands; Max allows at most $COMMANDS" }
        for (command in commands) {
            require(command.name.length in 1..COMMAND_NAME_LENGTH) {
                "Command name \"${command.name.short()}\" is ${command.name.length} characters long; Max allows 1 to $COMMAND_NAME_LENGTH"
            }
            val description = command.description ?: continue
            require(description.length in 1..COMMAND_DESCRIPTION_LENGTH) {
                "Description of command ${command.name} is ${description.length} characters long; Max allows 1 to $COMMAND_DESCRIPTION_LENGTH"
            }
        }
    }

    private fun checkText(text: String?, what: String) {
        val length = text?.length ?: return
        require(length <= TEXT_LENGTH) {
            "$what text is $length characters long (UTF-16 code units, as String.length counts them); " +
                "Max allows at most $TEXT_LENGTH. Split it with TextSplitter or send it with sendLongMessage"
        }
    }

    private fun check(attachment: AttachmentRequest) {
        if (attachment is InlineKeyboardAttachmentRequest) checkKeyboard(attachment.payload.buttons)
    }

    private fun checkKeyboard(rows: List<List<Button>>) {
        require(rows.size <= KEYBOARD_ROWS) { "Keyboard has ${rows.size} rows; Max allows at most $KEYBOARD_ROWS" }
        val buttons = rows.sumOf { it.size }
        require(buttons >= 1) { "A keyboard needs at least one button" }
        require(buttons <= KEYBOARD_BUTTONS) { "Keyboard has $buttons buttons; Max allows at most $KEYBOARD_BUTTONS" }
        rows.forEachIndexed { index, row ->
            require(row.size <= BUTTONS_PER_ROW) {
                "Row ${index + 1} of the keyboard has ${row.size} buttons; Max allows at most $BUTTONS_PER_ROW"
            }
            val wide = row.firstOrNull { it.type in WIDE_BUTTON_TYPES }
            require(wide == null || row.size <= WIDE_BUTTONS_PER_ROW) {
                "Row ${index + 1} of the keyboard has ${row.size} buttons; Max allows at most $WIDE_BUTTONS_PER_ROW " +
                    "in a row with a ${wide?.type} button"
            }
            row.forEach(::checkButton)
        }
    }

    private fun checkButton(button: Button) {
        require(button.text.length in 1..BUTTON_TEXT_LENGTH) {
            "Text of button \"${button.text.short()}\" is ${button.text.length} characters long; Max allows 1 to $BUTTON_TEXT_LENGTH"
        }
        when (button) {
            is CallbackButton -> checkPayload(button.payload, button)
            is ClipboardButton -> checkPayload(button.payload, button)
            is LinkButton -> require(button.url.length <= LINK_URL_LENGTH) {
                "URL of button \"${button.text}\" is ${button.url.length} characters long; Max allows at most $LINK_URL_LENGTH"
            }
            is OpenAppButton -> button.payload?.let { payload ->
                require(payload.length <= OPEN_APP_PAYLOAD_LENGTH) {
                    "Payload of button \"${button.text}\" is ${payload.length} characters long; Max allows at most $OPEN_APP_PAYLOAD_LENGTH"
                }
                require(OPEN_APP_PAYLOAD.matches(payload)) {
                    "Payload of button \"${button.text}\" may hold only A-Z, a-z, 0-9, _ and -: \"${payload.short()}\""
                }
            }
            else -> Unit
        }
    }

    private fun checkPayload(payload: String, button: Button) {
        require(payload.length <= BUTTON_PAYLOAD_LENGTH) {
            "Payload of button \"${button.text}\" is ${payload.length} characters long; Max allows at most $BUTTON_PAYLOAD_LENGTH"
        }
    }

    /** The start of a long value, for error messages. */
    private fun String.short(): String = if (length <= 40) this else take(40) + "…"
}
