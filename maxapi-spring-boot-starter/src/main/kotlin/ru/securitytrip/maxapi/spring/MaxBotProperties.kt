package ru.securitytrip.maxapi.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.RateLimit
import ru.securitytrip.maxapi.webapp.WebAppData
import java.time.Duration

/**
 * Settings of the auto-configured bot, under `max.bot`:
 *
 * ```yaml
 * max:
 *   bot:
 *     token: ${MAX_BOT_TOKEN}
 *     mode: webhook            # polling (default), webhook or none
 *     sync-commands: true
 *     rate-limit: max          # none (default) or max
 *     webhook:
 *       path: /max/webhook
 *       url: https://bot.example.com/max/webhook
 *       secret: ${MAX_WEBHOOK_SECRET}
 *       check-interval: 10m    # subscribe again if Max dropped the subscription
 *     web-app:
 *       source: header         # header (default), parameter or body
 *       header: X-Max-Init-Data
 *       max-age: 1h
 *     health:
 *       cache-ttl: 30s         # how long the health indicator reuses its API request
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

    /**
     * Client-side rate limit: [RateLimitPreset.MAX] keeps to the limits of Max (30 requests per second,
     * 2 answers to button presses per second in a chat); [RateLimitPreset.NONE] (default) does not limit.
     * A [MaxClientConfigCustomizer] can set any other `RateLimit`.
     */
    public var rateLimit: RateLimitPreset = RateLimitPreset.NONE

    /** How the bot receives updates. */
    public var mode: Mode = Mode.POLLING

    /** Whether to show the commands registered with descriptions in the bot menu on startup. */
    public var syncCommands: Boolean = false

    /** Webhook settings, used when [mode] is [Mode.WEBHOOK]. */
    public val webhook: Webhook = Webhook()

    /**
     * Where dialog states, the polling marker and the keys of seen updates are kept. With
     * [Storage.JDBC] and [Storage.REDIS] they survive restarts and are shared by all instances,
     * and repeated deliveries of an update are dropped.
     */
    public var storage: Storage = Storage.MEMORY

    /** How controllers of a mini app backend get `initData`, see [MaxWebAppUser]. */
    public val webApp: WebApp = WebApp()

    /** Settings of [Storage.JDBC]. */
    public val jdbc: Jdbc = Jdbc()

    /** Settings of [Storage.REDIS]. */
    public val redis: Redis = Redis()

    /** Settings of the health indicator `max`, registered with Spring Boot Actuator. */
    public val health: Health = Health()

    /** Client-side rate limits. */
    public enum class RateLimitPreset(
        /** The limit set on the client; `null` for none. */
        public val limit: RateLimit?,
    ) {
        /** Requests go as fast as they come; the server's 429 answers are retried. */
        NONE(null),

        /** [RateLimit.MAX]: the limits of Max. */
        MAX(RateLimit.MAX),
    }

    /** Where the bot keeps its state. */
    public enum class Storage {
        /** In memory: lost on restart, not shared between instances, no deduplication. */
        MEMORY,

        /** In tables of the application's `DataSource`; needs `maxapi-jdbc`. */
        JDBC,

        /** In Redis at [Redis.url]; needs `maxapi-redis`. */
        REDIS,
    }

    /** Where a mini app sends `window.WebApp.initData` and how old it may be. */
    public class WebApp {
        /** Where `initData` is in the request. */
        public var source: Source = Source.HEADER

        /** Header with `initData` when [source] is [Source.HEADER]. */
        public var header: String = "X-Max-Init-Data"

        /** Query or form parameter with `initData` when [source] is [Source.PARAMETER]. */
        public var parameter: String = "initData"

        /** How old `auth_date` may be; one hour, as the Max documentation recommends. */
        public var maxAge: Duration = WebAppData.DEFAULT_MAX_AGE

        /** Where `initData` is in the request. */
        public enum class Source {
            /** The [header]; works for any method and leaves the body to the controller. */
            HEADER,

            /** The query or form [parameter]. */
            PARAMETER,

            /** The whole request body, as plain text; the controller cannot read the body then. */
            BODY,
        }
    }

    public class Jdbc {
        /** Prefix of the table names: `<prefix>dialog_state`, `<prefix>bot_marker` and `<prefix>seen_update`. */
        public var tablePrefix: String = "max_"

        /** Whether to create missing tables on startup; turn it off when migrations create them. */
        public var createTables: Boolean = true
    }

    public class Redis {
        /** Redis URI, e.g. `redis://:password@host:6379/0`. */
        public var url: String = "redis://localhost:6379"

        /** Prefix of the keys: `<prefix>state:...`, `<prefix>marker` and `<prefix>seen:...`. */
        public var keyPrefix: String = "max:"

        /** How long a dialog state is kept after its last change; `null` (default) keeps it until it is cleared. */
        public var stateTtl: Duration? = null
    }

    /** Settings of the health indicator, see `MaxHealthAutoConfiguration`. */
    public class Health {
        /**
         * How long the indicator reuses the result of its request to the API (`getMyInfo`, or
         * `getSubscriptions` in webhook mode), so that frequent health checks do not use up the limit of
         * 30 requests per second. `0` asks on every check.
         */
        public var cacheTtl: Duration = Duration.ofSeconds(30)

        /** How long a health check waits for the API and the storage before reporting them down. */
        public var timeout: Duration = Duration.ofSeconds(5)
    }

    /** How the bot receives updates. */
    public enum class Mode {
        /** Long polling, started with the application context and stopped gracefully with it. */
        POLLING,

        /** A `POST` endpoint at [Webhook.path] in the application's Spring MVC or WebFlux server. */
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

        /**
         * How often to check that the subscription to [url] still exists, and to subscribe again if it
         * does not: Max drops a subscription after 8 hours without successful deliveries. Used only
         * when [url] is set; `0` turns the check off.
         */
        public var checkInterval: Duration = Duration.ofMinutes(10)
    }
}
