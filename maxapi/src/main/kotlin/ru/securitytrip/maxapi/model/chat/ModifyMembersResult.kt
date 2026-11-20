package ru.securitytrip.maxapi.model.chat

/** Result of adding members to a chat. */
public data class ModifyMembersResult(
    val success: Boolean,
    val message: String? = null,
    /** Users that could not be added. */
    val failedUserIds: Set<Long>? = null,
    /** Why the users could not be added. */
    val failedUserDetails: List<FailedUserDetails>? = null,
    /** Error code for the operation when provided by Max. */
    val code: String? = null,
) {
    public constructor(
        success: Boolean,
        message: String? = null,
        failedUserIds: Set<Long>? = null,
        failedUserDetails: List<FailedUserDetails>? = null,
    ) : this(success, message, failedUserIds, failedUserDetails, null)

    public fun copy(
        success: Boolean = this.success,
        message: String? = this.message,
        failedUserIds: Set<Long>? = this.failedUserIds,
        failedUserDetails: List<FailedUserDetails>? = this.failedUserDetails,
    ): ModifyMembersResult = copy(success, message, failedUserIds, failedUserDetails, code)
}

/** Reason why some users could not be added to a chat. */
public data class FailedUserDetails(
    /**
     * `add.participant.privacy` if privacy settings forbid adding the users,
     * `add.participant.not.found` if the users do not exist.
     */
    val errorCode: String,
    val userIds: List<Long> = emptyList(),
)
