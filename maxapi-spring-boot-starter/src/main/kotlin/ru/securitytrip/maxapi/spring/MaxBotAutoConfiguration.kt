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
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.events.MaxEventListener

/**
 * Configures a [MaxClient] and a [MaxBot] when `max.bot.token` is set, and starts receiving updates
 * as `max.bot.mode` says (see [MaxBotProperties]).
 *
 * Handlers come from beans: every [MaxBotConfigurer] is called with the bot, and every [Router] bean
 * is included, both in `@Order` order. Define your own `MaxClient`, `MaxBot` or [BotConfig] bean to
 * replace the default one; [MaxClientConfigCustomizer] beans adjust the client settings.
 * [MaxEventListener] beans receive the events of the client and, unless you define a `BotConfig`
 * bean, of the bot.
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
            .eventListener(MaxEventListener.composite(listeners.orderedStream().toList()))
        customizers.orderedStream().forEach { it.customize(builder) }
        return MaxClient(builder.build())
    }

    @Bean
    @ConditionalOnMissingBean
    public fun maxBot(
        client: MaxClient,
        config: ObjectProvider<BotConfig>,
        configurers: ObjectProvider<MaxBotConfigurer>,
        routers: ObjectProvider<Router>,
        listeners: ObjectProvider<MaxEventListener>,
    ): MaxBot {
        val default = { BotConfig(eventListener = MaxEventListener.composite(listeners.orderedStream().toList())) }
        val bot = MaxBot(client, config.getIfAvailable(default))
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
}

/** Called with the auto-configured bot to register handlers: `@Bean MaxBotConfigurer handlers() { return bot -> ...; }`. */
public fun interface MaxBotConfigurer {
    public fun configure(bot: MaxBot)
}

/** Adjusts the settings of the auto-configured client, e.g. the retry policy or the `OkHttpClient`. */
public fun interface MaxClientConfigCustomizer {
    public fun customize(builder: MaxClientConfig.Builder)
}
