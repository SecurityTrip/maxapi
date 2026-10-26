package ru.securitytrip.maxapi.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import ru.securitytrip.maxapi.client.MaxClientConfig

/**
 * Settings of the auto-configured bot, under `max.bot`:
 *
 * ```yaml
 * max:
 *   bot:
 *     token: ${MAX_BOT_TOKEN}
 *     mode: webhook            # polling (default), webhook or none
 *     sync-commands: true
 *     webhook:
 *       path: /max/webhook
 *       url: https://bot.example.com/max/webhook
 *       secret: ${MAX_WEBHOOK_SECRET}
 * ```
 */
@ConfigurationProperties("max.bot")
public class MaxBotProperties {
    /** Bot access token. The auto-configuration is active only when it is set. */
    public var token: String = ""

    /** Base URL of the API; override it only for tests or proxies. */
    public var baseUrl: String = MaxClientConfig.DEFAULT_BASE_URL

    /**
     * Whether the client trusts the Russian Trusted Root CA, which the API server's certificate is
     * issued under and which JDKs do not include. Turn it off only if the JVM already trusts that CA.
     */
    public var trustRussianRootCa: Boolean = true

    /** How the bot receives updates. */
    public var mode: Mode = Mode.POLLING

    /** Whether to show the commands registered with descriptions in the bot menu on startup. */
    public var syncCommands: Boolean = false

    /** Webhook settings, used when [mode] is [Mode.WEBHOOK]. */
    public val webhook: Webhook = Webhook()

    /** How the bot receives updates. */
    public enum class Mode {
        /** Long polling, started with the application context and stopped gracefully with it. */
        POLLING,

        /** A `POST` endpoint at [Webhook.path] in the application's Spring MVC server. */
        WEBHOOK,

        /** Nothing is started; deliver updates yourself, e.g. with `MaxBot.processUpdate`. */
        NONE,
    }

    public class Webhook {
        /** Path of the endpoint. */
        public var path: String = "/max/webhook"

        /**
         * Public HTTPS URL of the endpoint. When set, the bot subscribes to it on startup, so
         * that Max starts delivering updates there; otherwise subscribe yourself.
         */
        public var url: String? = null

        /** Secret Max sends in the `X-Max-Bot-Api-Secret` header; requests with another value get 401. */
        public var secret: String? = null

        /**
         * `true` answers 200 right after parsing the update and runs the handler in the background;
         * `false` (default) answers after the handler completes.
         */
        public var async: Boolean = false
    }
}
