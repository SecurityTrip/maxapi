package ru.securitytrip.maxapi.spring

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException
import ru.securitytrip.maxapi.webapp.WebAppData
import ru.securitytrip.maxapi.webapp.WebAppDataException
import ru.securitytrip.maxapi.webapp.WebAppUser
import java.time.Clock

/**
 * Resolves [MaxWebAppUser] parameters of controller methods: reads `initData` where [settings] say,
 * checks it with [WebAppData.validate] and the bot token, and answers 401 when it is missing or invalid.
 * The checked data is kept in a request attribute, so several parameters check it once.
 */
public class MaxWebAppArgumentResolver @JvmOverloads constructor(
    private val botToken: String,
    private val settings: MaxBotProperties.WebApp = MaxBotProperties.WebApp(),
    private val clock: Clock = Clock.systemUTC(),
) : HandlerMethodArgumentResolver {
    init {
        require(botToken.isNotBlank()) { "botToken must not be blank" }
    }

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(MaxWebAppUser::class.java) &&
            (parameter.parameterType == WebAppData::class.java || parameter.parameterType == WebAppUser::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request = checkNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) { "@MaxWebAppUser works only in servlet requests" }
        val data = validated(request) ?: run {
            if (!parameter.getParameterAnnotation(MaxWebAppUser::class.java)!!.required) return null
            throw unauthorized("No initData in the request", null)
        }
        if (parameter.parameterType == WebAppData::class.java) return data
        return data.user ?: throw unauthorized("initData has no user", null)
    }

    private fun validated(request: HttpServletRequest): WebAppData? {
        (request.getAttribute(ATTRIBUTE) as? WebAppData)?.let { return it }
        val initData = read(request)?.takeIf { it.isNotEmpty() } ?: return null
        val data = try {
            WebAppData.validate(initData, botToken, settings.maxAge, clock.instant())
        } catch (e: WebAppDataException) {
            throw unauthorized(e.message!!, e)
        }
        request.setAttribute(ATTRIBUTE, data)
        return data
    }

    private fun read(request: HttpServletRequest): String? = when (settings.source) {
        MaxBotProperties.WebApp.Source.HEADER -> request.getHeader(settings.header)
        MaxBotProperties.WebApp.Source.PARAMETER -> request.getParameter(settings.parameter)
        MaxBotProperties.WebApp.Source.BODY -> request.inputStream.readNBytes(MAX_BODY).toString(Charsets.UTF_8).trim()
    }

    private fun unauthorized(reason: String, cause: Throwable?) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason, cause)

    public companion object {
        /** Name of the request attribute that holds the checked [WebAppData]. */
        public const val ATTRIBUTE: String = "ru.securitytrip.maxapi.spring.WebAppData"

        /** How much of the body [MaxBotProperties.WebApp.Source.BODY] reads: `initData` is far shorter. */
        private const val MAX_BODY = 64 * 1024
    }
}
