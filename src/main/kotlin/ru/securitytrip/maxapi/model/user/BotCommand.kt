package ru.securitytrip.maxapi.model.user

/**
 * A command of the bot, shown in the bot menu.
 *
 * @property name command name without `/`, 1–64 characters.
 * @property description optional description, 1–128 characters.
 */
public data class BotCommand @JvmOverloads constructor(
    val name: String,
    val description: String? = null,
)

/** Bot commands as returned by `MaxClient.editMyCommands`. */
public data class BotCommandsInfo(
    val commands: List<BotCommand>? = null,
)

/** Request body of `PATCH /me/commands`. */
public data class BotCommandsPatch(
    val commands: List<BotCommand>,
)
