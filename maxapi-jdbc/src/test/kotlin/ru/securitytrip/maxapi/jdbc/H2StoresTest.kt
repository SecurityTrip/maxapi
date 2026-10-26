package ru.securitytrip.maxapi.jdbc

import org.h2.jdbcx.JdbcDataSource
import javax.sql.DataSource

class H2StoresTest : JdbcStoresContract() {
    override val dataSource: DataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:maxapi;DB_CLOSE_DELAY=-1") }
}
