package ru.securitytrip.maxapi.jdbc

import ru.securitytrip.maxapi.bot.BlockingChatStore
import ru.securitytrip.maxapi.bot.KnownChat
import ru.securitytrip.maxapi.model.chat.ChatType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * Keeps the chats of the bot ([ru.securitytrip.maxapi.bot.ChatStore]) in the database table [table],
 * so they survive restarts and are shared by all instances of the bot.
 *
 * Create the table with [createTable], or yourself (`active` is 1 or 0, `updated_at` is the time of
 * the event in milliseconds):
 *
 * ```sql
 * CREATE TABLE max_chat (
 *     chat_id    BIGINT       NOT NULL PRIMARY KEY,
 *     chat_type  VARCHAR(16)  NOT NULL,
 *     title      VARCHAR(512),
 *     user_id    BIGINT,
 *     active     SMALLINT     NOT NULL,
 *     updated_at BIGINT       NOT NULL
 * )
 * ```
 *
 * A save is one conditional `UPDATE ... WHERE updated_at <= ?` (or an `INSERT`), so instances that
 * save the same chat at once never replace a newer record with an older one.
 */
public class JdbcChatStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
) : BlockingChatStore() {
    /** Name of the table. */
    public val table: String = Sql.table(table)

    override fun load(chatId: Long): KnownChat? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT $COLUMNS FROM $table WHERE chat_id = ?").use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { rows -> if (rows.next()) chatOf(rows) else null }
        }
    }

    override fun saveIfNewer(chat: KnownChat): Boolean = dataSource.connection.use { connection ->
        Sql.upsert(
            connection,
            "UPDATE $table SET chat_type = ?, title = ?, user_id = ?, active = ?, updated_at = ? WHERE chat_id = ? AND updated_at <= ?",
            "INSERT INTO $table (chat_type, title, user_id, active, updated_at, chat_id) VALUES (?, ?, ?, ?, ?, ?)",
            bindUpdate = {
                bind(it, chat)
                it.setLong(7, chat.updatedAt)
            },
            bindInsert = { bind(it, chat) },
        )
    }

    override fun loadActive(): List<KnownChat> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT $COLUMNS FROM $table WHERE active = 1").use { statement ->
            statement.executeQuery().use { rows -> generateSequence { if (rows.next()) chatOf(rows) else null }.toList() }
        }
    }

    /** Creates the table unless it exists and returns this store. */
    public fun createTable(): JdbcChatStore = apply {
        Sql.createTable(
            dataSource, table,
            "CREATE TABLE $table (chat_id BIGINT NOT NULL PRIMARY KEY, chat_type VARCHAR(16) NOT NULL, title VARCHAR(512), " +
                "user_id BIGINT, active SMALLINT NOT NULL, updated_at BIGINT NOT NULL)",
        )
    }

    /** Binds the columns in the order `chat_type, title, user_id, active, updated_at, chat_id`. */
    private fun bind(statement: PreparedStatement, chat: KnownChat) {
        statement.setString(1, chat.type.value)
        statement.setString(2, chat.title)
        chat.userId.let { if (it == null) statement.setNull(3, Types.BIGINT) else statement.setLong(3, it) }
        statement.setInt(4, if (chat.active) 1 else 0)
        statement.setLong(5, chat.updatedAt)
        statement.setLong(6, chat.chatId)
    }

    private fun chatOf(rows: ResultSet): KnownChat = KnownChat(
        chatId = rows.getLong(1),
        type = ChatType.entries.firstOrNull { it.value == rows.getString(2) } ?: ChatType.UNKNOWN,
        title = rows.getString(3),
        userId = rows.getLong(4).takeUnless { rows.wasNull() },
        active = rows.getInt(5) == 1,
        updatedAt = rows.getLong(6),
    )

    public companion object {
        /** Default name of the table. */
        public const val DEFAULT_TABLE: String = "max_chat"

        private const val COLUMNS = "chat_id, chat_type, title, user_id, active, updated_at"
    }
}
