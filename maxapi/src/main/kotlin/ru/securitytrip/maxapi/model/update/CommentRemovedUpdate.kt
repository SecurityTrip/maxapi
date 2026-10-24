package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.user.User

/** A comment was deleted. Requires the `read_all_messages` permission in the channel. */
public data class CommentRemovedUpdate(
    override val timestamp: Long,
    val messageId: String,
    override val chatId: Long,
    val userId: Long,
    val postId: String,
) : Update() {
    override val updateType: String get() = "comment_removed"
    @get:JsonIgnore
    override val user: User? get() = null
}
