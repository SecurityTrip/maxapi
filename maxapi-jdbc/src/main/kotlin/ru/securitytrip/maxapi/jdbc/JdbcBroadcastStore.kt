package ru.securitytrip.maxapi.jdbc

import ru.securitytrip.maxapi.bot.BlockingBroadcastStore
import ru.securitytrip.maxapi.bot.BroadcastOutcome
import ru.securitytrip.maxapi.bot.BroadcastTarget
import java.sql.PreparedStatement
import javax.sql.DataSource

/**
 * Keeps the progress of broadcasts ([ru.securitytrip.maxapi.bot.BroadcastStore]) in the database table
 * [table], one row per broadcast and target, so a broadcast continues after a restart.
 *
 * Create the table with [createTable], or yourself:
 *
 * ```sql
 * CREATE TABLE max_broadcast (
 *     broadcast_id VARCHAR(255) NOT NULL,
 *     target       VARCHAR(32)  NOT NULL,
 *     outcome      VARCHAR(16)  NOT NULL,
 *     updated_at   BIGINT       NOT NULL,
 *     PRIMARY KEY (broadcast_id, target)
 * )
 * ```
 *
 * Rows stay until the broadcast is deleted with [delete].
 */
public class JdbcBroadcastStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
) : BlockingBroadcastStore() {
    /** Name of the table. */
    public val table: String = Sql.table(table)

    override fun load(broadcastId: String): Map<BroadcastTarget, BroadcastOutcome> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT target, outcome FROM $table WHERE broadcast_id = ?").use { statement ->
            statement.setString(1, broadcastId)
            statement.executeQuery().use { rows ->
                val result = LinkedHashMap<BroadcastTarget, BroadcastOutcome>()
                while (rows.next()) result[BroadcastTarget.parse(rows.getString(1))] = BroadcastOutcome.valueOf(rows.getString(2))
                result
            }
        }
    }

    override fun save(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome) {
        val now = System.currentTimeMillis()
        // Both statements take the columns in the same order.
        val bind = { statement: PreparedStatement ->
            statement.setString(1, outcome.name)
            statement.setLong(2, now)
            statement.setString(3, broadcastId)
            statement.setString(4, target.key)
        }
        dataSource.connection.use { connection ->
            Sql.upsert(
                connection,
                "UPDATE $table SET outcome = ?, updated_at = ? WHERE broadcast_id = ? AND target = ?",
                "INSERT INTO $table (outcome, updated_at, broadcast_id, target) VALUES (?, ?, ?, ?)",
                bindUpdate = bind,
                bindInsert = bind,
            )
        }
    }

    override fun remove(broadcastId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM $table WHERE broadcast_id = ?").use {
                it.setString(1, broadcastId)
                it.executeUpdate()
            }
        }
    }

    /** Creates the table unless it exists and returns this store. */
    public fun createTable(): JdbcBroadcastStore = apply {
        Sql.createTable(
            dataSource, table,
            "CREATE TABLE $table (broadcast_id VARCHAR(255) NOT NULL, target VARCHAR(32) NOT NULL, outcome VARCHAR(16) NOT NULL, " +
                "updated_at BIGINT NOT NULL, PRIMARY KEY (broadcast_id, target))",
        )
    }

    public companion object {
        /** Default name of the table. */
        public const val DEFAULT_TABLE: String = "max_broadcast"
    }
}
