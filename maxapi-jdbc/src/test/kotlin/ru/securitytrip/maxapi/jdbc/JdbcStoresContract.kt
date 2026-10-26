package ru.securitytrip.maxapi.jdbc

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.StateStorage
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.update.Update
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same tests for every database: only standard SQL is used, so they must pass on each. */
abstract class JdbcStoresContract {
    abstract val dataSource: DataSource

    private val prefix = "t${counter.incrementAndGet()}_"
    private val tables = mutableListOf<String>()

    private fun table(name: String) = (prefix + name).also { tables += it }

    @AfterTest
    fun dropTables() {
        dataSource.connection.use { connection ->
            for (table in tables) connection.createStatement().use { it.executeUpdate("DROP TABLE IF EXISTS $table") }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    @Test
    fun `states are stored, replaced and removed`(): Unit = runBlocking {
        val storage: StateStorage = JdbcStateStorage(dataSource, table("state")).createTable()
        val key = StateKey(1, 2)
        assertNull(storage.get(key))

        storage.set(key, DialogState("name", mapOf("city" to "Казань", "quote" to "a \"b\"")))
        assertEquals(DialogState("name", mapOf("city" to "Казань", "quote" to "a \"b\"")), storage.get(key))

        storage.set(key, DialogState(null))
        assertEquals(DialogState(null), storage.get(key))

        storage.set(StateKey(1, null), DialogState("chat"))
        storage.set(StateKey(null, 2), DialogState("user"))
        assertEquals("chat", storage.get(StateKey(1, null))?.name)
        assertEquals("user", storage.get(StateKey(null, 2))?.name)

        storage.set(key, null)
        assertNull(storage.get(key))
        assertEquals("chat", storage.get(StateKey(1, null))?.name)
    }

    @Test
    fun `abandoned states are deleted by age`(): Unit = runBlocking {
        val storage = JdbcStateStorage(dataSource, table("state")).createTable()
        storage.set(StateKey(1, 1), DialogState("old"))
        storage.set(StateKey(2, 2), DialogState("new"))
        execute("UPDATE ${storage.table} SET updated_at = 0 WHERE state_key = '1:1'")

        assertEquals(1, storage.deleteOlderThan(Duration.ofHours(1)))
        assertNull(storage.get(StateKey(1, 1)))
        assertEquals("new", storage.get(StateKey(2, 2))?.name)
    }

    @Test
    fun `createTable keeps an existing table and its rows`(): Unit = runBlocking {
        val name = table("state")
        JdbcStateStorage(dataSource, name).createTable().set(StateKey(1, 1), DialogState("kept"))
        assertEquals("kept", JdbcStateStorage(dataSource, name).createTable().get(StateKey(1, 1))?.name)
        assertEquals(JdbcStateStorage.DEFAULT_TABLE, JdbcStateStorage(dataSource).table)
    }

    @Test
    fun `markers are kept per bot`(): Unit = runBlocking {
        val name = table("marker")
        val first = JdbcMarkerStore(dataSource, name).createTable()
        val second = JdbcMarkerStore(dataSource, name, botId = "second").createTable()
        assertNull(first.load())

        first.save(10)
        first.save(42)
        second.save(7)
        assertEquals(42, first.load())
        assertEquals(7, second.load())
        assertEquals(42, JdbcMarkerStore(dataSource, name).load())
        assertEquals("default", JdbcMarkerStore(dataSource).botId)
        assertEquals(JdbcMarkerStore.DEFAULT_TABLE, JdbcMarkerStore(dataSource).table)
    }

    @Test
    fun `repeated updates are dropped, also by another instance`() {
        val name = table("seen")
        val first = JdbcUpdateDeduplicator(dataSource, name).createTable()
        val second = JdbcUpdateDeduplicator(dataSource, name)

        assertTrue(first.isNew(message("mid.1")))
        assertFalse(first.isNew(message("mid.1")))
        assertFalse(second.isNew(message("mid.1")))
        assertTrue(second.isNew(message("mid.2")))
        assertEquals(Duration.ofHours(9), first.ttl)
        assertEquals(JdbcUpdateDeduplicator.DEFAULT_TABLE, JdbcUpdateDeduplicator(dataSource).table)
        assertFailsWith<IllegalArgumentException> { JdbcUpdateDeduplicator(dataSource, ttl = Duration.ZERO) }
    }

    @Test
    fun `expired keys are deleted`() {
        val name = table("seen")
        JdbcUpdateDeduplicator(dataSource, name).createTable()
        execute("INSERT INTO $name (update_key, seen_at) VALUES ('${keyOf(message("mid.old"))}', 0)")

        // A new instance cleans up on its first call.
        val deduplicator = JdbcUpdateDeduplicator(dataSource, name, Duration.ofHours(1))
        assertTrue(deduplicator.isNew(message("mid.new")))
        assertTrue(deduplicator.isNew(message("mid.old")))
    }

    @Test
    fun `table names are checked`() {
        for (bad in listOf("", "1table", "max state", "x; DROP TABLE y", "a.b.c")) {
            assertFailsWith<IllegalArgumentException> { JdbcStateStorage(dataSource, bad) }
        }
        assertEquals("public.max_state", JdbcStateStorage(dataSource, "public.max_state").table)
    }

    @Test
    fun `an insert that loses a race updates the row instead`() {
        val name = table("marker")
        JdbcMarkerStore(dataSource, name).createTable().apply { runBlocking { save(1) } }
        dataSource.connection.use { connection ->
            // The update matches nothing, as if the row did not exist yet, so the insert collides.
            var inserts = 0
            Sql.upsert(
                connection,
                "UPDATE $name SET marker = ? WHERE bot_id = ? AND 1 = 0",
                "INSERT INTO $name (bot_id, marker) VALUES (?, ?)",
                bindUpdate = { it.setLong(1, 2); it.setString(2, "default") },
                bindInsert = { inserts++; it.setString(1, "default"); it.setLong(2, 2) },
            )
            assertEquals(1, inserts)

            assertFailsWith<SQLException> {
                Sql.upsert(
                    connection,
                    "UPDATE $name SET marker = ? WHERE bot_id = ?",
                    "INSERT INTO $name (bot_id, no_such_column) VALUES (?, ?)",
                    bindUpdate = { it.setLong(1, 3); it.setString(2, "other") },
                    bindInsert = { it.setString(1, "other"); it.setLong(2, 3) },
                )
            }
        }
        assertFalse(Sql.isDuplicateKey(SQLException("no state")))
    }

    @Test
    fun `other database errors are not mistaken for duplicates`() {
        val name = table("seen")
        execute("CREATE TABLE $name (update_key INTEGER NOT NULL PRIMARY KEY, seen_at BIGINT NOT NULL)")
        assertFailsWith<SQLException> { JdbcUpdateDeduplicator(dataSource, name).isNew(message("mid.1")) }
        assertFailsWith<SQLException> { JdbcUpdateDeduplicator(dataSource, table("missing")).isNew(message("mid.1")) }
    }

    private fun message(mid: String): Update = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1,"message":{"recipient":{"chat_id":1,"chat_type":"dialog"},
           "timestamp":1,"body":{"mid":"$mid","seq":1,"text":"hi"}}}""",
    )

    private fun keyOf(update: Update) = ru.securitytrip.maxapi.bot.UpdateDeduplicator.keyOf(update)

    private companion object {
        val counter = AtomicInteger()
    }
}
