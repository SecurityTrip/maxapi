package ru.securitytrip.maxapi.model.markup

/** A user mention: by `@username`, or by user id if the user has no username. */
public data class UserMentionMarkup(
    override val from: Int,
    override val length: Int,
    /** `@username` of the mentioned user. */
    val userLink: String? = null,
    /** Identifier of a mentioned user without a username. */
    val userId: Long? = null,
) : MarkupElement() {
    override val type: String get() = "user_mention"
}
