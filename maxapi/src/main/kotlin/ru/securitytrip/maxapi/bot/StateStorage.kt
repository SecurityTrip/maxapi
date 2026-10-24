package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.securitytrip.maxapi.model.update.Update
import java.util.concurrent.ConcurrentHashMap

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

/** Keeps states in memory. States are lost on restart and never expire; call `clearState()` when a dialog ends. */
public class InMemoryStateStorage : StateStorage {
    private val states = ConcurrentHashMap<StateKey, DialogState>()

    override suspend fun get(key: StateKey): DialogState? = states[key]

    override suspend fun set(key: StateKey, state: DialogState?) {
        if (state == null) states.remove(key) else states[key] = state
    }

    /** Number of stored states. */
    public val size: Int get() = states.size
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
