# Module maxapi-jdbc

Stores of a maxapi bot in an SQL database through a `DataSource`: dialog states
([JdbcStateStorage][ru.securitytrip.maxapi.jdbc.JdbcStateStorage]), the long polling marker
([JdbcMarkerStore][ru.securitytrip.maxapi.jdbc.JdbcMarkerStore]) and the keys of handled updates
([JdbcUpdateDeduplicator][ru.securitytrip.maxapi.jdbc.JdbcUpdateDeduplicator]), the chats of the bot
([JdbcChatStore][ru.securitytrip.maxapi.jdbc.JdbcChatStore]) and the progress of broadcasts
([JdbcBroadcastStore][ru.securitytrip.maxapi.jdbc.JdbcBroadcastStore]). Only standard SQL
is used, so any JDBC database works; the tests run on H2 and PostgreSQL.

# Package ru.securitytrip.maxapi.jdbc

JDBC implementations of `StateStorage`, `MarkerStore`, `UpdateDeduplicator`, `ChatStore` and `BroadcastStore`.
