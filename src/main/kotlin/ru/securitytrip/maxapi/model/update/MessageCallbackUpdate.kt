package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.message.Callback
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.user.User

/**
 * A user pressed a [CallbackButton]. The API expects the bot to answer every press with
 * `MaxClient.answerOnCallback`: update the message and/or show a notification.
 */
public data class MessageCallbackUpdate(
    override val timestamp: Long,
    val callback: Callback,
    /** The message with the keyboard; `null` if it was deleted before the bot got this update. */
    val message: Message? = null,
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "message_callback"
    @get:JsonIgnore
    override val chatId: Long? get() = message?.recipient?.chatId
    @get:JsonIgnore
    override val user: User get() = callback.user
}
