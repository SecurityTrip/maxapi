package ru.securitytrip.maxapi.bot

import java.time.Duration

/**
 * Settings of a [MaxBot].
 *
 * ```kotlin
 * val bot = MaxBot(token, BotConfig(parallelism = 16))
 * ```
 *
 * ```java
 * MaxBot bot = new MaxBot(token, BotConfig.builder().parallelism(16).build());
 * ```
 */
public class BotConfig(
    /**
     * Number of workers handling long polling updates concurrently. Updates are distributed by
     * chat, so updates of one chat are always handled one at a time and in order, while different
     * chats are handled in parallel.
     */
    public val parallelism: Int = 4,
    /** Queue size per worker. When a queue is full, polling waits, which gives natural backpressure. */
    public val queueCapacity: Int = 64,
    /** How long one `getUpdates` request waits for updates, 0–90 seconds. */
    public val pollTimeoutSeconds: Int = 30,
    /** Maximum number of updates per `getUpdates` request, 1–1000. */
    public val pollLimit: Int = 100,
    /** Minimum interval between `getUpdates` requests. The API requires at least 300 ms. */
    public val minPollInterval: Duration = Duration.ofMillis(300),
    /** Upper bound of the pause after failed `getUpdates` requests; the pause doubles from 1 second. */
    public val maxErrorBackoff: Duration = Duration.ofSeconds(30),
    /** Where dialog states ([UpdateContext.state]) are kept; by default in memory. */
    public val stateStorage: StateStorage = InMemoryStateStorage(),
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(queueCapacity >= 1) { "queueCapacity must be >= 1" }
        require(pollTimeoutSeconds in 0..90) { "pollTimeoutSeconds must be in 0..90" }
        require(pollLimit in 1..1000) { "pollLimit must be in 1..1000" }
    }

    /** Builder for Java. Every setter has the same meaning as the matching constructor parameter. */
    public class Builder {
        private var parallelism = 4
        private var queueCapacity = 64
        private var pollTimeoutSeconds = 30
        private var pollLimit = 100
        private var minPollInterval = Duration.ofMillis(300)
        private var maxErrorBackoff = Duration.ofSeconds(30)
        private var stateStorage: StateStorage? = null

        public fun parallelism(value: Int): Builder = apply { parallelism = value }
        public fun queueCapacity(value: Int): Builder = apply { queueCapacity = value }
        public fun pollTimeoutSeconds(value: Int): Builder = apply { pollTimeoutSeconds = value }
        public fun pollLimit(value: Int): Builder = apply { pollLimit = value }
        public fun minPollInterval(value: Duration): Builder = apply { minPollInterval = value }
        public fun maxErrorBackoff(value: Duration): Builder = apply { maxErrorBackoff = value }
        public fun stateStorage(value: StateStorage): Builder = apply { stateStorage = value }

        public fun build(): BotConfig = BotConfig(
            parallelism, queueCapacity, pollTimeoutSeconds, pollLimit, minPollInterval, maxErrorBackoff,
            stateStorage ?: InMemoryStateStorage(),
        )
    }

    public companion object {
        /** Starts building a config for Java code. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
