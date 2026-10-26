package ru.securitytrip.maxapi.jdbc

import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.model.update.Update
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * Remembers the keys ([UpdateDeduplicator.keyOf]) of handled updates in the database table [table]
 * for [ttl], so a repeated delivery is dropped even when another instance of the bot received the
 * first one. Max retries webhook deliveries for up to 8 hours, hence the default of 9 hours.
 *
 * Every [isNew] inserts a row, and at most once a minute rows older than [ttl] are deleted. Create the
 * table with [createTable], or yourself:
 *
 * ```sql
 * CREATE TABLE max_seen_update (
 *     update_key VARCHAR(255) NOT NULL PRIMARY KEY,
 *     seen_at    BIGINT       NOT NULL
 * )
 * ```
 */
public class JdbcUpdateDeduplicator @JvmOverloads constructor(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
    /** How long a key is remembered. */
    public val ttl: Duration = Duration.ofHours(9),
) : UpdateDeduplicator {
    /** Name of the table. */
    public val table: String = Sql.table(table)

    // Instances that clean up at the same time do no harm: the deletes are idempotent.
    @Volatile
    private var lastCleanup = 0L

    init {
        require(ttl > Duration.ZERO) { "ttl must be positive" }
    }

    override fun isNew(update: Update): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCleanup >= CLEANUP_INTERVAL_MS) {
            lastCleanup = now
            deleteExpired(now)
        }
        return dataSource.connection.use { connection ->
            try {
                connection.prepareStatement("INSERT INTO $table (update_key, seen_at) VALUES (?, ?)").use {
                    it.setString(1, UpdateDeduplicator.keyOf(update))
                    it.setLong(2, now)
                    it.executeUpdate()
                }
                true
            } catch (e: SQLException) {
                if (!Sql.isDuplicateKey(e)) throw e
                false
            }
        }
    }

    /** Creates the table unless it exists and returns this deduplicator. */
    public fun createTable(): JdbcUpdateDeduplicator = apply {
        Sql.createTable(dataSource, table, "CREATE TABLE $table (update_key VARCHAR(255) NOT NULL PRIMARY KEY, seen_at BIGINT NOT NULL)")
    }

    private fun deleteExpired(now: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM $table WHERE seen_at < ?").use {
                it.setLong(1, now - ttl.toMillis())
                it.executeUpdate()
            }
        }
    }

    public companion object {
        /** Default name of the table. */
        public const val DEFAULT_TABLE: String = "max_seen_update"

        private const val CLEANUP_INTERVAL_MS = 60_000L
    }
}
