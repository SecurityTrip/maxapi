# Module maxapi-redis

Stores of a maxapi bot in Redis through a Lettuce connection: dialog states
([RedisStateStorage][ru.securitytrip.maxapi.redis.RedisStateStorage]), the long polling marker
([RedisMarkerStore][ru.securitytrip.maxapi.redis.RedisMarkerStore]) and the keys of handled updates
([RedisUpdateDeduplicator][ru.securitytrip.maxapi.redis.RedisUpdateDeduplicator]).

# Package ru.securitytrip.maxapi.redis

Redis implementations of `StateStorage`, `MarkerStore` and `UpdateDeduplicator`.
