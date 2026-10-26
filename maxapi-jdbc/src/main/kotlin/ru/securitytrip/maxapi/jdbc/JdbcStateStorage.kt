package ru.securitytrip.maxapi.jdbc

import ru.securitytrip.maxapi.bot.BlockingStateStorage
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.StateKey
import java.time.Duration
import javax.sql.DataSource

/**
 * Keeps dialog states in the database table [table], so they survive restarts and are shared by all
 * instances of the bot. Works with any JDBC database.
 *
 * Create the table with [createTable], or yourself (the values of `state_data` are JSON objects; make
 * the column larger, e.g. `TEXT`, if dialogs collect a lot of data):
 *
 * ```sql
 * CREATE TABLE max_dialog_state (
 *     state_key  VARCHAR(64)   NOT NULL PRIMARY KEY,
 *     state_name VARCHAR(255),
 *     state_data VARCHAR(4000) NOT NULL,
 *     updated_at BIGINT        NOT NULL
 * )
 * ```
 *
 * States are kept until a handler clears them; [deleteOlderThan] removes abandoned dialogs.
 */
public class JdbcStateStorage @JvmOverloads constructor(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
) : BlockingStateStorage() {
    /** Name of the table. */
    public val table: String = Sql.table(table)

    override fun load(key: StateKey): DialogState? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT state_name, state_data FROM $table WHERE state_key = ?").use { statement ->
            statement.setString(1, keyOf(key))
            statement.executeQuery().use { rows ->
                if (rows.next()) DialogState(rows.getString(1), Sql.decode(rows.getString(2))) else null
            }
        }
    }

    override fun save(key: StateKey, state: DialogState?) {
        dataSource.connection.use { connection ->
            if (state == null) {
                connection.prepareStatement("DELETE FROM $table WHERE state_key = ?").use {
                    it.setString(1, keyOf(key))
                    it.executeUpdate()
                }
                return
            }
            val data = Sql.encode(state.data)
            val now = System.currentTimeMillis()
            Sql.upsert(
                connection,
                "UPDATE $table SET state_name = ?, state_data = ?, updated_at = ? WHERE state_key = ?",
                "INSERT INTO $table (state_key, state_name, state_data, updated_at) VALUES (?, ?, ?, ?)",
                bindUpdate = {
                    it.setString(1, state.name)
                    it.setString(2, data)
                    it.setLong(3, now)
                    it.setString(4, keyOf(key))
                },
                bindInsert = {
                    it.setString(1, keyOf(key))
                    it.setString(2, state.name)
                    it.setString(3, data)
                    it.setLong(4, now)
                },
            )
        }
    }

    /** Creates the table unless it exists and returns this storage. */
    public fun createTable(): JdbcStateStorage = apply {
        Sql.createTable(
            dataSource, table,
            "CREATE TABLE $table (state_key VARCHAR(64) NOT NULL PRIMARY KEY, state_name VARCHAR(255), " +
                "state_data VARCHAR(4000) NOT NULL, updated_at BIGINT NOT NULL)",
        )
    }

    /** Deletes the states not changed for [age], e.g. dialogs users abandoned, and returns how many. */
    public fun deleteOlderThan(age: Duration): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM $table WHERE updated_at < ?").use {
            it.setLong(1, System.currentTimeMillis() - age.toMillis())
            it.executeUpdate()
        }
    }

    private fun keyOf(key: StateKey): String = "${key.chatId ?: ""}:${key.userId ?: ""}"

    public companion object {
        /** Default name of the table. */
        public const val DEFAULT_TABLE: String = "max_dialog_state"
    }
}
