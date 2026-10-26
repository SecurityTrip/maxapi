# Module maxapi-jdbc

Stores of a maxapi bot in an SQL database through a `DataSource`: dialog states
([JdbcStateStorage][ru.securitytrip.maxapi.jdbc.JdbcStateStorage]), the long polling marker
([JdbcMarkerStore][ru.securitytrip.maxapi.jdbc.JdbcMarkerStore]) and the keys of handled updates
([JdbcUpdateDeduplicator][ru.securitytrip.maxapi.jdbc.JdbcUpdateDeduplicator]). Only standard SQL
is used, so any JDBC database works; the tests run on H2 and PostgreSQL.

# Package ru.securitytrip.maxapi.jdbc

JDBC implementations of `StateStorage`, `MarkerStore` and `UpdateDeduplicator`.
