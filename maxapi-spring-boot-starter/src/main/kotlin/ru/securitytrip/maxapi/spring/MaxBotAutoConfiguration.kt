package ru.securitytrip.maxapi.spring

import okhttp3.Credentials
import okhttp3.OkHttpClient
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
import ru.securitytrip.maxapi.bot.InMemoryUpdateDeduplicator
import ru.securitytrip.maxapi.bot.MarkerStore
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.bot.StateStorage
import ru.securitytrip.maxapi.bot.SuspendUpdateDeduplicator
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.events.MaxEventListener
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse

/**
 * Configures a [MaxClient] and a [MaxBot] when `max.bot.token` is set and not blank, and starts receiving
 * updates as `max.bot.mode` says (see [MaxBotProperties]).
 *
 * Handlers come from beans: every [MaxBotConfigurer] is called with the bot, and every [Router] bean
 * is included, both in `@Order` order. Define your own `MaxClient`, `MaxBot` or [BotConfig] bean to
 * replace the default one; [MaxClientConfigCustomizer] beans adjust the client settings and
 * [BotConfigCustomizer] beans the bot settings. [MaxEventListener] beans receive the events of the
 * client and, unless you define a `BotConfig` bean, of the bot. So do [StateStorage], [MarkerStore],
 * [UpdateDeduplicator] (or [SuspendUpdateDeduplicator]) and [ChatStore] beans, e.g. those that
 * `max.bot.storage` creates; without a deduplicator bean, an [InMemoryUpdateDeduplicator] drops repeated
 * deliveries. The [Broadcaster] bean sends broadcasts with the client and keeps their progress in the
 * [BroadcastStore] bean.
 *
 * On startup it checks that the application does not have older versions of Kotlin, kotlinx.coroutines
 * and Jackson than `maxapi` needs, as the dependency management of an older Spring Boot could set them.
 */
@AutoConfiguration
@ConditionalOnClass(MaxBot::class)
@ConditionalOnMaxBotToken
@EnableConfigurationProperties(MaxBotProperties::class)
public class MaxBotAutoConfiguration {
    init {
        // Here rather than in a bean, so that the check runs with a MaxClient bean of the application too.
        DependencyVersions.check()
    }

    @Bean
    @ConditionalOnMissingBean
    public fun maxClient(
        properties: MaxBotProperties,
        customizers: ObjectProvider<MaxClientConfigCustomizer>,
        listeners: ObjectProvider<MaxEventListener>,
    ): MaxClient {
        val builder = MaxClientConfig.builder(properties.token)
            .baseUrl(properties.baseUrl)
            .httpClient(httpClientBuilder(properties))
            .trustRussianRootCa(properties.trustRussianRootCa)
            .rateLimit(properties.rateLimit.limit)
            .eventListener(MaxEventListener.composite(listeners.orderedStream().toList()))
        customizers.orderedStream().forEach { it.customize(builder) }
        return MaxClient(builder.build())
    }

    /**
     * Settings of the bot from `max.bot.*`, with the storages and event listeners defined as beans, adjusted
     * by the [BotConfigCustomizer] beans. An [UpdateDeduplicator] bean is preferred to a
     * [SuspendUpdateDeduplicator] bean; the bot calls the non-blocking method of a bean that implements
     * both. Without either, an [InMemoryUpdateDeduplicator] is used.
     */
    @Bean
    @ConditionalOnMissingBean
    public fun maxBotConfig(
        properties: MaxBotProperties,
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ObjectProvider<ChatStore>,
        suspendDeduplicator: ObjectProvider<SuspendUpdateDeduplicator>,
        customizers: ObjectProvider<BotConfigCustomizer>,
    ): BotConfig {
        val builder = botConfig(properties, listeners, stateStorage, markerStore, deduplicator, chatStore.ifAvailable, suspendDeduplicator)
        customizers.orderedStream().forEach { it.customize(builder) }
        return builder.build()
    }

    /** The factory method of 1.4.0, kept for binary compatibility; Spring calls only the `@Bean` one. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun maxBotConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ObjectProvider<ChatStore>,
        suspendDeduplicator: ObjectProvider<SuspendUpdateDeduplicator>,
    ): BotConfig = botConfig(MaxBotProperties(), listeners, stateStorage, markerStore, deduplicator, chatStore.ifAvailable, suspendDeduplicator).build()

    /** A factory method without the non-blocking deduplicator, hidden in 1.4.0 as well; kept for binary compatibility. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun maxBotConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ObjectProvider<ChatStore>,
    ): BotConfig = botConfig(MaxBotProperties(), listeners, stateStorage, markerStore, deduplicator, chatStore.ifAvailable, null).build()

    /** The factory method of 1.3, kept for binary compatibility; Spring calls only the `@Bean` one. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun maxBotConfig(
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
    ): BotConfig = botConfig(MaxBotProperties(), listeners, stateStorage, markerStore, deduplicator, null, null).build()

    private fun botConfig(
        properties: MaxBotProperties,
        listeners: ObjectProvider<MaxEventListener>,
        stateStorage: ObjectProvider<StateStorage>,
        markerStore: ObjectProvider<MarkerStore>,
        deduplicator: ObjectProvider<UpdateDeduplicator>,
        chatStore: ChatStore?,
        suspendDeduplicator: ObjectProvider<SuspendUpdateDeduplicator>?,
    ): BotConfig.Builder {
        val blocking = deduplicator.ifAvailable
        val suspending = if (blocking == null) suspendDeduplicator?.ifAvailable else null
        return BotConfig.builder()
            .parallelism(properties.parallelism)
            .queueCapacity(properties.queueCapacity)
            .pollTimeoutSeconds(properties.pollTimeout.seconds.toInt())
            .handlerTimeout(properties.handlerTimeout.takeUnless { it.isZero })
            .autoAnswerCallbacks(properties.autoAnswerCallbacks)
            .stateStorage(stateStorage.getIfAvailable(::InMemoryStateStorage))
            .markerStore(markerStore.ifAvailable)
            .deduplicator(blocking ?: if (suspending == null) InMemoryUpdateDeduplicator() else null)
            .suspendDeduplicator(suspending)
            .eventListener(MaxEventListener.composite(listeners.orderedStream().toList()))
            .chatStore(chatStore)
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
            webhookEndpoint(bot, properties.webhook, properties.webhookSecret())
    }

    /** The webhook endpoint, in reactive web applications (Spring WebFlux) with `max.bot.mode=webhook`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(ReactiveRouterFunction::class)
    @ConditionalOnProperty(prefix = "max.bot", name = ["mode"], havingValue = "webhook")
    public class ReactiveWebhookConfiguration {
        @Bean
        public fun maxBotWebhookEndpoint(bot: MaxBot, properties: MaxBotProperties): ReactiveRouterFunction<ReactiveServerResponse> =
            reactiveWebhookEndpoint(bot, properties.webhook, properties.webhookSecret())
    }
}

/** The `OkHttpClient` of the client: the timeouts and the proxy of [properties]. */
internal fun httpClientBuilder(properties: MaxBotProperties): OkHttpClient.Builder {
    val builder = OkHttpClient.Builder()
        .connectTimeout(properties.connectTimeout)
        .readTimeout(properties.readTimeout)
        .writeTimeout(properties.readTimeout)
    val proxy = properties.proxy?.takeIf { it.isNotBlank() } ?: return builder
    val uri = URI(proxy)
    val scheme = uri.scheme?.lowercase()
    require(scheme in PROXY_SCHEMES && uri.host != null && uri.port > 0) {
        "max.bot.proxy must be like http://host:port or socks://host:port: $proxy"
    }
    val type = if (scheme!!.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
    // OkHttp sends credentials only to HTTP proxies; a SOCKS proxy would reject the requests without a word.
    require(type == Proxy.Type.HTTP || uri.rawUserInfo == null) { "max.bot.proxy supports credentials only for http proxies: $proxy" }
    builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port)))
    val userInfo = uri.rawUserInfo ?: return builder
    val credentials = Credentials.basic(
        URLDecoder.decode(userInfo.substringBefore(':'), Charsets.UTF_8),
        URLDecoder.decode(userInfo.substringAfter(':', ""), Charsets.UTF_8),
    )
    return builder.proxyAuthenticator { _, response ->
        // A second 407 means the credentials are wrong: give up instead of asking forever.
        if (response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder().header("Proxy-Authorization", credentials).build()
    }
}

private val PROXY_SCHEMES = setOf("http", "https", "socks", "socks4", "socks5")

/** Called with the auto-configured bot to register handlers: `@Bean MaxBotConfigurer handlers() { return bot -> ...; }`. */
public fun interface MaxBotConfigurer {
    public fun configure(bot: MaxBot)
}

/** Adjusts the settings of the auto-configured client, e.g. the retry policy or the `OkHttpClient`. */
public fun interface MaxClientConfigCustomizer {
    public fun customize(builder: MaxClientConfig.Builder)
}

/**
 * Adjusts the settings of the auto-configured bot after `max.bot.*` and the storage beans set them,
 * without replacing the whole [BotConfig] (since 1.5.0):
 *
 * ```kotlin
 * @Bean
 * fun pollLimit() = BotConfigCustomizer { it.pollLimit(50) }
 * ```
 */
public fun interface BotConfigCustomizer {
    public fun customize(builder: BotConfig.Builder)
}
