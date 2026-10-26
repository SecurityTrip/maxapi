package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.user.User

/** A press of a callback button, delivered in [MessageCallbackUpdate]. */
public data class Callback(
    /** When the button was pressed, Unix time in milliseconds. */
    val timestamp: Long,
    /** Identifies this press. Pass it to `MaxClient.answerOnCallback`. */
    val callbackId: String,
    /** [CallbackButton.payload] of the pressed button. */
    val payload: String? = null,
    /** User who pressed the button. */
    val user: User,
)

/**
 * Reaction to a callback button press, sent with `MaxClient.answerOnCallback`.
 * At least one of the fields should be set.
 */
public data class CallbackAnswer @JvmOverloads constructor(
    /** New content of the message that contains the button. */
    val message: NewMessageBody? = null,
    /** One-time notification shown to the user who pressed the button (not shown by the web client of Max). */
    val notification: String? = null,
)
