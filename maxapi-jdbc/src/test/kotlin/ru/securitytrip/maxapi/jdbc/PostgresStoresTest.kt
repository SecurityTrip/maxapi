package ru.securitytrip.maxapi.jdbc

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/** Runs the tests on PostgreSQL in Docker: its SQL and error codes differ from H2. */
class PostgresStoresTest : JdbcStoresContract() {
    override val dataSource: DataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username
        password = postgres.password
    }

    private companion object {
        val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
    }
}
