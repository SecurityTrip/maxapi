package ru.securitytrip.maxapi.spring

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.BroadcastStore
import ru.securitytrip.maxapi.bot.Broadcaster
import ru.securitytrip.maxapi.bot.ChatStore
import ru.securitytrip.maxapi.bot.InMemoryBroadcastStore
import ru.securitytrip.maxapi.bot.InMemoryStateStorage
import ru.securitytrip.maxapi.bot.MarkerStore
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.bot.StateStorage
import ru.securitytrip.maxapi.bot.SuspendUpdateDeduplicator
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.events.MaxEventListener
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse

/**
 * Configures a [MaxClient] and a [MaxBot] when `max.bot.token` is set, and starts receiving updates
 * as `max.bot.mode` says (see [MaxBotProperties]).
 *
 * Handlers come from beans: every [MaxBotConfigurer] is called with the bot, and every [Router] bean
 * is included, both in `@Order` order. Define your own `MaxClient`, `MaxBot` or [BotConfig] bean to
 * replace the default one; [MaxClientConfigCustomizer] beans adjust the client settings.
 * [MaxEventListener] beans receive the events of the client and, unless you define a `BotConfig`
 * bean, of the bot. So do [StateStorage], [MarkerStore], [UpdateDeduplicator] (or [SuspendUpdateDeduplicator])
 * and [ChatStore] beans,
 * e.g. those that `max.bot.storage` creates. The [Broadcaster] bean sends broadcasts with the client
 * and keeps their progress in the [BroadcastStore] bean.
 */
@AutoConfiguration
@ConditionalOnClass(MaxBot::class)
@ConditionalOnProperty(prefix = "max.bot", name = ["token"])
@EnableConfigurationProperties(MaxBotProperties::class)
public class MaxBotAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun maxClient(
        properties: MaxBotProperties,
        customizers: ObjectProvider<MaxClientConfigCustomizer>,
        listeners: ObjectProvider<MaxEventListener>,
    ): MaxClient {
        val builder = MaxClientConfig.builder(properties.token)
            .baseUrl(properties.baseUrl)
            .trustRussianRootCa(properties.trustRussianRootCa)
            .rateLimit(properties.rateLimit.limit)
            .eventListener(MaxEventListener.composite(listeners.orderedStream().toList()))
        customizers.orderedStream().forEach { it.customize(builder) }
        return MaxClient(builder.build())
    }

    /**
     * Settings of the bot with the storages and event listeners defined as beans. An [UpdateDeduplicator]
     * bean is preferred to a [SuspendUpdateDeduplicator] bean; the bot calls the non-blocking method of a
     * bean that implements both.
     */
    @Bean
    @ConditionalOnMissingBean
    public fun maxBotConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ObjectProvider<ChatStore>,
        suspendDeduplicator: ObjectProvider<SuspendUpdateDeduplicator>,
    ): BotConfig = botConfig(listeners, stateStorage, markerStore, deduplicator, chatStore.ifAvailable, suspendDeduplicator)

    /** The factory method of 1.6, kept for binary compatibility; Spring calls only the `@Bean` one. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun maxBotConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ObjectProvider<ChatStore>,
    ): BotConfig = botConfig(listeners, stateStorage, markerStore, deduplicator, chatStore.ifAvailable, null)

    /** The factory method before 1.6, kept for binary compatibility; Spring calls only the `@Bean` one. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun maxBotConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
    ): BotConfig = botConfig(listeners, stateStorage, markerStore, deduplicator, null, null)

    private fun botConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ChatStore?,
        suspendDeduplicator: ObjectProvider<SuspendUpdateDeduplicator>?,
    ): BotConfig {
        val blocking = deduplicator.ifAvailable
        return BotConfig(
            stateStorage = stateStorage.getIfAvailable(::InMemoryStateStorage),
            markerStore = markerStore.ifAvailable,
            deduplicator = blocking,
            eventListener = MaxEventListener.composite(listeners.orderedStream().toList()),
            chatStore = chatStore,
            suspendDeduplicator = if (blocking == null) suspendDeduplicator?.ifAvailable else null,
        )
    }

    /** Sends broadcasts with the client, keeping their progress in the [BroadcastStore] bean, or in memory without one. */
    @Bean
    @ConditionalOnMissingBean
    public fun maxBroadcaster(client: MaxClient, store: ObjectProvider<BroadcastStore>): Broadcaster =
        Broadcaster(client, store.getIfAvailable(::InMemoryBroadcastStore))

    /** The bot; [listeners] is no longer used, they go to the [BotConfig] of [maxBotConfig]. */
    @Bean
    @ConditionalOnMissingBean
    public fun maxBot(
        client: MaxClient,
        config: ObjectProvider<BotConfig>,
        configurers: ObjectProvider<MaxBotConfigurer>,
        routers: ObjectProvider<Router>,
        @Suppress("UNUSED_PARAMETER") listeners: ObjectProvider<MaxEventListener>,
    ): MaxBot {
        val bot = MaxBot(client, config.getObject())
        configurers.orderedStream().forEach { it.configure(bot) }
        routers.orderedStream().forEach { bot.include(it) }
        return bot
    }

    @Bean
    @ConditionalOnMissingBean
    public fun maxBotLifecycle(bot: MaxBot, properties: MaxBotProperties): MaxBotLifecycle = MaxBotLifecycle(bot, properties)

    /** The webhook endpoint, in servlet web applications with `max.bot.mode=webhook`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(RouterFunction::class)
    @ConditionalOnProperty(prefix = "max.bot", name = ["mode"], havingValue = "webhook")
    public class WebhookConfiguration {
        @Bean
        public fun maxBotWebhookEndpoint(bot: MaxBot, properties: MaxBotProperties): RouterFunction<ServerResponse> =
            webhookEndpoint(bot, properties.webhook)
    }

    /** The webhook endpoint, in reactive web applications (Spring WebFlux) with `max.bot.mode=webhook`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(ReactiveRouterFunction::class)
    @ConditionalOnProperty(prefix = "max.bot", name = ["mode"], havingValue = "webhook")
    public class ReactiveWebhookConfiguration {
        @Bean
        public fun maxBotWebhookEndpoint(bot: MaxBot, properties: MaxBotProperties): ReactiveRouterFunction<ReactiveServerResponse> =
            reactiveWebhookEndpoint(bot, properties.webhook)
    }
}

/** Called with the auto-configured bot to register handlers: `@Bean MaxBotConfigurer handlers() { return bot -> ...; }`. */
public fun interface MaxBotConfigurer {
    public fun configure(bot: MaxBot)
}

/** Adjusts the settings of the auto-configured client, e.g. the retry policy or the `OkHttpClient`. */
public fun interface MaxClientConfigCustomizer {
    public fun customize(builder: MaxClientConfig.Builder)
}
