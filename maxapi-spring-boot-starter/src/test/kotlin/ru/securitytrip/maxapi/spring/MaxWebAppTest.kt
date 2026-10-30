package ru.securitytrip.maxapi.spring

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.MethodParameter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.securitytrip.maxapi.webapp.WebAppData
import ru.securitytrip.maxapi.webapp.WebAppUser
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `@MaxWebAppUser` parameters of Spring MVC controllers. */
class MaxWebAppTest {
    private val token = "test-bot-token"
    private val authDate = 1_771_409_719L
    private val clock = Clock.fixed(Instant.ofEpochSecond(authDate + 60), ZoneOffset.UTC)

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    /** `initData` signed as https://dev.max.ru/docs/webapps/validation describes. */
    private fun initData(user: String? = """{"id":42,"first_name":"Анна"}""", botToken: String = token): String {
        val fields = sortedMapOf("auth_date" to authDate.toString(), "query_id" to "q1")
        if (user != null) fields["user"] = user
        val launchParams = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val hash = hmac(hmac("WebAppData".toByteArray(), botToken), launchParams).joinToString("") { "%02x".format(it) }
        return fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" } + "&hash=$hash"
    }

    @RestController
    class Controller {
        companion object {
            const val UTF8 = "text/plain;charset=UTF-8"
        }

        @GetMapping("/me", produces = [UTF8])
        fun me(@MaxWebAppUser user: WebAppUser): String = "${user.userId} ${user.firstName}"

        @PostMapping("/data")
        fun data(@MaxWebAppUser data: WebAppData, @MaxWebAppUser user: WebAppUser): String = "${data.queryId} ${user.userId}"

        @GetMapping("/optional", produces = [UTF8])
        fun optional(@MaxWebAppUser(required = false) user: WebAppUser?): String = user?.firstName ?: "guest"

        @GetMapping("/plain")
        fun plain(@RequestHeader("X-Max-Init-Data") initData: String): String = initData.length.toString()
    }

    private fun mvc(settings: MaxBotProperties.WebApp = MaxBotProperties.WebApp()): MockMvc =
        MockMvcBuilders.standaloneSetup(Controller())
            .setCustomArgumentResolvers(MaxWebAppArgumentResolver(token, settings, clock))
            .build()

    @Test
    fun `valid initData in the header`() {
        val mvc = mvc()
        mvc.perform(get("/me").header("X-Max-Init-Data", initData()))
            .andExpect(status().isOk).andExpect(content().string("42 Анна"))
        mvc.perform(post("/data").header("X-Max-Init-Data", initData()))
            .andExpect(status().isOk).andExpect(content().string("q1 42"))
        mvc.perform(get("/optional").header("X-Max-Init-Data", initData()))
            .andExpect(status().isOk).andExpect(content().string("Анна"))
        // Plain String parameters are not resolved by it.
        mvc.perform(get("/plain").header("X-Max-Init-Data", "abc")).andExpect(status().isOk).andExpect(content().string("3"))
    }

    @Test
    fun `missing or invalid initData gets 401`() {
        val mvc = mvc()
        mvc.perform(get("/me")).andExpect(status().isUnauthorized)
        mvc.perform(get("/me").header("X-Max-Init-Data", "")).andExpect(status().isUnauthorized)
        mvc.perform(get("/me").header("X-Max-Init-Data", initData(botToken = "other"))).andExpect(status().isUnauthorized)
        mvc.perform(get("/me").header("X-Max-Init-Data", initData().replace("q1", "q2"))).andExpect(status().isUnauthorized)
        mvc.perform(get("/me").header("X-Max-Init-Data", initData().substringBefore("&hash"))).andExpect(status().isUnauthorized)
        // Signed, but without a user.
        mvc.perform(get("/me").header("X-Max-Init-Data", initData(user = null))).andExpect(status().isUnauthorized)
        mvc.perform(post("/data").header("X-Max-Init-Data", initData(user = null))).andExpect(status().isUnauthorized)
        // Not required: missing is null, invalid is still 401.
        mvc.perform(get("/optional")).andExpect(status().isOk).andExpect(content().string("guest"))
        mvc.perform(get("/optional").header("X-Max-Init-Data", "hash=00")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `expired initData gets 401`() {
        val settings = MaxBotProperties.WebApp().apply { maxAge = Duration.ofSeconds(59) }
        mvc(settings).perform(get("/me").header("X-Max-Init-Data", initData())).andExpect(status().isUnauthorized)
        settings.maxAge = Duration.ofSeconds(60)
        mvc(settings).perform(get("/me").header("X-Max-Init-Data", initData())).andExpect(status().isOk)
    }

    @Test
    fun `initData from another header, a parameter or the body`() {
        val header = MaxBotProperties.WebApp().apply { this.header = "X-Init" }
        mvc(header).perform(get("/me").header("X-Init", initData())).andExpect(status().isOk)
        mvc(header).perform(get("/me").header("X-Max-Init-Data", initData())).andExpect(status().isUnauthorized)

        val parameter = MaxBotProperties.WebApp().apply { source = MaxBotProperties.WebApp.Source.PARAMETER; this.parameter = "tg" }
        mvc(parameter).perform(get("/me").param("tg", initData())).andExpect(status().isOk)
        mvc(parameter).perform(post("/data").contentType("application/x-www-form-urlencoded").content("tg=" + URLEncoder.encode(initData(), Charsets.UTF_8)))
            .andExpect(status().isOk).andExpect(content().string("q1 42"))
        mvc(parameter).perform(get("/me")).andExpect(status().isUnauthorized)

        val body = MaxBotProperties.WebApp().apply { source = MaxBotProperties.WebApp.Source.BODY }
        mvc(body).perform(post("/data").contentType("text/plain").content(initData() + "\n"))
            .andExpect(status().isOk).andExpect(content().string("q1 42"))
        mvc(body).perform(post("/data")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the resolver on its own`() {
        assertFailsWith<IllegalArgumentException> { MaxWebAppArgumentResolver(" ") }
        val resolver = MaxWebAppArgumentResolver(token)
        val method = Controller::class.java.getMethod("me", WebAppUser::class.java)
        assertTrue(resolver.supportsParameter(MethodParameter(method, 0)))
        val plain = Controller::class.java.getMethod("plain", String::class.java)
        assertFalse(resolver.supportsParameter(MethodParameter(plain, 0)))
        // Data checked earlier in the request is reused.
        val request = org.springframework.mock.web.MockHttpServletRequest()
        val data = WebAppData.validate(initData(), token, Duration.ofDays(36500))
        request.setAttribute(MaxWebAppArgumentResolver.ATTRIBUTE, data)
        assertSame(data.user, resolver.resolveArgument(MethodParameter(method, 0), null, ServletWebRequest(request), null))
        assertEquals("X-Max-Init-Data", MaxBotProperties().webApp.header)
        assertEquals(Duration.ofHours(1), MaxBotProperties().webApp.maxAge)
        assertEquals(MaxBotProperties.WebApp.Source.HEADER, MaxBotProperties().webApp.source)
        assertEquals("initData", MaxBotProperties().webApp.parameter)
    }

    // ------------------------------------------------------------ auto-configuration

    private val webRunner = WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(MaxWebAppAutoConfiguration::class.java))

    @Test
    fun `auto-configuration registers the resolver in servlet applications with a token`() {
        webRunner.withPropertyValues("max.bot.token=t", "max.bot.web-app.source=body", "max.bot.web-app.max-age=5m").run { context ->
            val resolver = context.getBean(MaxWebAppArgumentResolver::class.java)
            val resolvers = mutableListOf<HandlerMethodArgumentResolver>()
            context.getBeansOfType(WebMvcConfigurer::class.java).values.forEach { it.addArgumentResolvers(resolvers) }
            assertEquals(listOf<HandlerMethodArgumentResolver>(resolver), resolvers)
            val properties = context.getBean(MaxBotProperties::class.java)
            assertEquals(MaxBotProperties.WebApp.Source.BODY, properties.webApp.source)
            assertEquals(Duration.ofMinutes(5), properties.webApp.maxAge)
        }
        webRunner.run { context -> assertTrue(context.getBeansOfType(MaxWebAppArgumentResolver::class.java).isEmpty()) }
        webRunner.withPropertyValues("max.bot.token=t").withClassLoader(FilteredClassLoader(WebMvcConfigurer::class.java))
            .run { context -> assertTrue(context.getBeansOfType(MaxWebAppArgumentResolver::class.java).isEmpty()) }
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MaxWebAppAutoConfiguration::class.java))
            .withPropertyValues("max.bot.token=t")
            .run { context -> assertTrue(context.getBeansOfType(MaxWebAppArgumentResolver::class.java).isEmpty()) }
    }
}
