package ru.securitytrip.maxapi.model.chat

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/** Permissions of a chat administrator. */
public enum class ChatAdminPermission(@get:JsonValue override val value: String) : ApiEnum {
    /** Read all messages. Bots need it to receive `message_created` in group chats. */
    READ_ALL_MESSAGES("read_all_messages"),
    ADD_REMOVE_MEMBERS("add_remove_members"),
    ADD_ADMINS("add_admins"),
    CHANGE_CHAT_INFO("change_chat_info"),
    PIN_MESSAGE("pin_message"),
    EDIT_LINK("edit_link"),
    WRITE("write"),
    EDIT("edit"),
    DELETE("delete"),
    CAN_CALL("can_call"),
    VIEW_STATS("view_stats"),

    /** A permission this version of the library does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}
