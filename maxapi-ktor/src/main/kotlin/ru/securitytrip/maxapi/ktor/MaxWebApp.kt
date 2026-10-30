package ru.securitytrip.maxapi.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import ru.securitytrip.maxapi.webapp.WebAppData
import ru.securitytrip.maxapi.webapp.WebAppDataException
import ru.securitytrip.maxapi.webapp.WebAppUser
import java.time.Clock
import java.time.Duration

/**
 * Checks the `initData` of a mini app on every call of the routes it is installed in, and answers 401
 * when it is missing or invalid. Handlers read the checked data with [maxInitData] and [maxWebAppUser]:
 *
 * ```kotlin
 * routing {
 *     route("/api") {
 *         install(MaxWebApp) { botToken = System.getenv("MAX_BOT_TOKEN") }
 *         get("/me") { call.respondText("Hello, ${call.maxWebAppUser().firstName}") }
 *     }
 * }
 * ```
 *
 * By default the mini app sends `window.WebApp.initData` in the [MaxWebAppConfig.DEFAULT_HEADER]
 * header; [MaxWebAppConfig.initData] reads it from anywhere else.
 */
public val MaxWebApp: RouteScopedPlugin<MaxWebAppConfig> = createRouteScopedPlugin("MaxWebApp", ::MaxWebAppConfig) {
    val config = pluginConfig
    require(config.botToken.isNotBlank()) { "Set botToken of the MaxWebApp plugin" }
    require(!config.maxAge.isNegative) { "maxAge of the MaxWebApp plugin must not be negative: ${config.maxAge}" }
    onCall { call ->
        val initData = config.extract(call)
        if (initData.isNullOrEmpty()) {
            call.respondText("No initData in the request", status = HttpStatusCode.Unauthorized)
            return@onCall
        }
        val data = try {
            WebAppData.validate(initData, config.botToken, config.maxAge, config.clock.instant())
        } catch (e: WebAppDataException) {
            call.respondText(e.message!!, status = HttpStatusCode.Unauthorized)
            return@onCall
        }
        if (config.requireUser && data.user == null) {
            call.respondText("initData has no user", status = HttpStatusCode.Unauthorized)
            return@onCall
        }
        call.attributes.put(INIT_DATA, data)
    }
}

/** Settings of [MaxWebApp]. */
public class MaxWebAppConfig {
    /** Token of the bot the mini app belongs to. Required. */
    public var botToken: String = ""

    /** How old `auth_date` may be; one hour by default, as the Max documentation recommends. */
    public var maxAge: Duration = WebAppData.DEFAULT_MAX_AGE

    /** Whether `initData` without a user is answered with 401; `true` by default. */
    public var requireUser: Boolean = true

    /** The clock `auth_date` is compared with; tests set a fixed one. */
    public var clock: Clock = Clock.systemUTC()

    internal var extract: suspend (ApplicationCall) -> String? = { it.request.headers[DEFAULT_HEADER] }

    /**
     * Where `initData` is in the call, e.g. `initData { it.request.queryParameters["initData"] }` or
     * `initData { it.receiveText() }`; `null` or empty answers 401.
     */
    public fun initData(extract: suspend (ApplicationCall) -> String?) {
        this.extract = extract
    }

    /** Reads `initData` from the header [name]. */
    public fun header(name: String) {
        initData { it.request.headers[name] }
    }

    public companion object {
        /** The header `initData` is read from by default. */
        public const val DEFAULT_HEADER: String = "X-Max-Init-Data"
    }
}

private val INIT_DATA = AttributeKey<WebAppData>("MaxWebAppData")

/**
 * The `initData` that [MaxWebApp] checked for this call.
 * @throws IllegalStateException if [MaxWebApp] is not installed in the route.
 */
public fun ApplicationCall.maxInitData(): WebAppData =
    attributes.getOrNull(INIT_DATA) ?: throw IllegalStateException("Install the MaxWebApp plugin in this route to get initData")

/**
 * The user who opened the mini app, from [maxInitData].
 * @throws IllegalStateException if [MaxWebApp] is not installed in the route, or `initData` has no user
 *   and [MaxWebAppConfig.requireUser] is off.
 */
public fun ApplicationCall.maxWebAppUser(): WebAppUser = checkNotNull(maxInitData().user) { "initData has no user" }
