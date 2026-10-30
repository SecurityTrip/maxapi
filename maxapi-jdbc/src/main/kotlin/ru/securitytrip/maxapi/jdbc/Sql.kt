package ru.securitytrip.maxapi.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource

/** SQL helpers shared by the stores. Only standard SQL is used, so any JDBC database works. */
internal object Sql {
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")
    private val mapper = ObjectMapper()
    private val MAP = object : TypeReference<Map<String, String>>() {}

    /** [name] if it is a plain (optionally schema-qualified) table name; it is put into SQL as is. */
    fun table(name: String): String {
        require(IDENTIFIER.matches(name)) { "Invalid table name: $name" }
        return name
    }

    /** Runs [ddl] unless [table] exists. */
    fun createTable(dataSource: DataSource, table: String, ddl: String) {
        dataSource.connection.use { connection ->
            val exists = try {
                connection.createStatement().use { it.executeQuery("SELECT 1 FROM $table WHERE 1 = 0").close() }
                true
            } catch (e: SQLException) {
                false
            }
            if (!exists) connection.createStatement().use { it.executeUpdate(ddl) }
        }
    }

    /**
     * Updates a row with [update] and inserts it with [insert] when there was none. If another
     * instance inserts the same row in between, the insert fails with a duplicate key and the row
     * is updated again. Returns `false` if neither wrote a row: an [update] with an extra condition
     * matched none while the row exists.
     */
    fun upsert(connection: Connection, update: String, insert: String, bindUpdate: (PreparedStatement) -> Unit, bindInsert: (PreparedStatement) -> Unit): Boolean {
        val updated = connection.prepareStatement(update).use { bindUpdate(it); it.executeUpdate() }
        if (updated > 0) return true
        return try {
            connection.prepareStatement(insert).use { bindInsert(it); it.executeUpdate() }
            true
        } catch (e: SQLException) {
            if (!isDuplicateKey(e)) throw e
            connection.prepareStatement(update).use { bindUpdate(it); it.executeUpdate() } > 0
        }
    }

    /** SQLSTATE class 23 is an integrity constraint violation, e.g. a duplicate primary key. */
    fun isDuplicateKey(e: SQLException): Boolean = e.sqlState?.startsWith("23") == true

    fun encode(data: Map<String, String>): String = mapper.writeValueAsString(data)

    fun decode(json: String): Map<String, String> = mapper.readValue(json, MAP)
}
