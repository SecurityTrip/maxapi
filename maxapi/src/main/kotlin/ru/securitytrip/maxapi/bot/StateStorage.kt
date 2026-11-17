package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.securitytrip.maxapi.model.update.Update
import java.time.Duration

/**
 * Identifies a dialog whose state is kept: a user in a chat. Either part may be `null` when the update
 * does not have it, but not both.
 */
public data class StateKey(val chatId: Long?, val userId: Long?) {
    public companion object {
        /** The key of [update], or `null` if it has neither a chat nor a user. */
        @JvmStatic
        public fun of(update: Update): StateKey? {
            val chatId = update.chatId
            val userId = update.user?.userId
            return if (chatId == null && userId == null) null else StateKey(chatId, userId)
        }
    }
}

/**
 * State of a dialog: the step of a conversation ([name]) and the values collected so far ([data]).
 * Values are strings so that any storage can keep them.
 */
public data class DialogState @JvmOverloads constructor(
    val name: String?,
    val data: Map<String, String> = emptyMap(),
)

/**
 * Where the bot keeps [DialogState]s, set with [BotConfig.stateStorage]. The bot reads the state of
 * every update before handlers run and writes it when a handler changes it.
 *
 * The default [InMemoryStateStorage] loses states on restart. Implement this interface (Kotlin) or
 * extend [BlockingStateStorage] (Java) to keep them in Redis, a database, and so on.
 */
public interface StateStorage {
    /** The state for [key], or `null` if there is none. */
    public suspend fun get(key: StateKey): DialogState?

    /** Stores [state] for [key]; `null` removes it. */
    public suspend fun set(key: StateKey, state: DialogState?)
}

/**
 * Keeps states in memory; they are lost on restart. A state not changed for [ttl] expires, and when
 * there are [maxSize] states the least recently used one is dropped, so that dialogs users abandon
 * do not take memory forever. Call `clearState()` when a dialog ends.
 *
 * @property maxSize most states kept.
 * @property ttl how long a state is kept after its last change; `null` keeps it until it is dropped for [maxSize].
 */
public class InMemoryStateStorage @JvmOverloads constructor(
    public val maxSize: Int = DEFAULT_MAX_SIZE,
    public val ttl: Duration? = DEFAULT_TTL,
) : StateStorage {
    init {
        require(maxSize >= 1) { "maxSize must be >= 1" }
        require(ttl == null || ttl > Duration.ZERO) { "ttl must be positive" }
    }

    private class Entry(val state: DialogState, val expires: Long)

    private val states = object : LinkedHashMap<StateKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StateKey, Entry>): Boolean = size > maxSize
    }

    /** The current time in nanoseconds; replaced in tests. */
    internal var clock: () -> Long = System::nanoTime

    override suspend fun get(key: StateKey): DialogState? = synchronized(states) {
        val entry = states[key] ?: return null
        if (clock() - entry.expires < 0) return entry.state
        states.remove(key)
        null
    }

    override suspend fun set(key: StateKey, state: DialogState?) {
        synchronized(states) {
            if (state == null) {
                states.remove(key)
            } else {
                val ttlNanos = ttl?.toNanos() ?: Long.MAX_VALUE
                states[key] = Entry(state, clock() + minOf(ttlNanos, Long.MAX_VALUE / 2))
            }
        }
    }

    /** Number of stored states, expired ones not yet removed included. */
    public val size: Int get() = synchronized(states) { states.size }

    public companion object {
        /** Default of [maxSize]. */
        public const val DEFAULT_MAX_SIZE: Int = 100_000

        /** Default of [ttl]: one day. */
        @JvmField
        public val DEFAULT_TTL: Duration = Duration.ofDays(1)
    }
}

/**
 * Base class for a [StateStorage] with blocking I/O, e.g. JDBC, for Java. [load] and [save] run on
 * `Dispatchers.IO`.
 *
 * ```java
 * class JdbcStateStorage extends BlockingStateStorage {
 *     protected DialogState load(StateKey key) { ... }
 *     protected void save(StateKey key, DialogState state) { ... }
 * }
 * ```
 */
public abstract class BlockingStateStorage : StateStorage {
    /** The state for [key], or `null` if there is none. */
    protected abstract fun load(key: StateKey): DialogState?

    /** Stores [state] for [key]; `null` removes it. */
    protected abstract fun save(key: StateKey, state: DialogState?)

    final override suspend fun get(key: StateKey): DialogState? = withContext(Dispatchers.IO) { load(key) }

    final override suspend fun set(key: StateKey, state: DialogState?): Unit = withContext(Dispatchers.IO) { save(key, state) }
}
