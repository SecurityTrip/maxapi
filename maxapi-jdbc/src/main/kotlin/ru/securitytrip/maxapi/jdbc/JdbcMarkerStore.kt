package ru.securitytrip.maxapi.jdbc

import ru.securitytrip.maxapi.bot.BlockingMarkerStore
import javax.sql.DataSource

/**
 * Keeps the long polling marker in the database table [table], one row per bot ([botId]), so
 * polling continues where it stopped after a restart.
 *
 * Create the table with [createTable], or yourself:
 *
 * ```sql
 * CREATE TABLE max_bot_marker (
 *     bot_id VARCHAR(64) NOT NULL PRIMARY KEY,
 *     marker BIGINT      NOT NULL
 * )
 * ```
 */
public class JdbcMarkerStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
    /** Row of this bot; give every bot that shares the table its own. */
    public val botId: String = "default",
) : BlockingMarkerStore() {
    /** Name of the table. */
    public val table: String = Sql.table(table)

    override fun loadMarker(): Long? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT marker FROM $table WHERE bot_id = ?").use { statement ->
            statement.setString(1, botId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
    }

    override fun saveMarker(marker: Long) {
        dataSource.connection.use { connection ->
            Sql.upsert(
                connection,
                "UPDATE $table SET marker = ? WHERE bot_id = ?",
                "INSERT INTO $table (bot_id, marker) VALUES (?, ?)",
                bindUpdate = {
                    it.setLong(1, marker)
                    it.setString(2, botId)
                },
                bindInsert = {
                    it.setString(1, botId)
                    it.setLong(2, marker)
                },
            )
        }
    }

    /** Creates the table unless it exists and returns this store. */
    public fun createTable(): JdbcMarkerStore = apply {
        Sql.createTable(dataSource, table, "CREATE TABLE $table (bot_id VARCHAR(64) NOT NULL PRIMARY KEY, marker BIGINT NOT NULL)")
    }

    public companion object {
        /** Default name of the table. */
        public const val DEFAULT_TABLE: String = "max_bot_marker"
    }
}
