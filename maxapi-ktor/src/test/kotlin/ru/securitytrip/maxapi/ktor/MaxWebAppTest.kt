package ru.securitytrip.maxapi.ktor

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The `MaxWebApp` plugin. */
class MaxWebAppTest {
    private val token = "test-bot-token"
    private val authDate = 1_771_409_719L
    private val fixed = Clock.fixed(Instant.ofEpochSecond(authDate + 60), ZoneOffset.UTC)
    private val handled = CopyOnWriteArrayList<String>()

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    /** `initData` signed as https://dev.max.ru/docs/webapps/validation describes. */
    private fun initData(user: String? = """{"id":42,"first_name":"Anna"}""", botToken: String = token): String {
        val fields = sortedMapOf("auth_date" to authDate.toString(), "query_id" to "q1")
        if (user != null) fields["user"] = user
        val launchParams = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val hash = hmac(hmac("WebAppData".toByteArray(), botToken), launchParams).joinToString("") { "%02x".format(it) }
        return fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" } + "&hash=$hash"
    }

    @Test
    fun `checks initData from the header`(): Unit = testApplication {
        routing {
            route("/api") {
                install(MaxWebApp) {
                    botToken = token
                    clock = fixed
                }
                get("/me") {
                    handled += "me"
                    call.respondText("${call.maxWebAppUser().userId} ${call.maxInitData().queryId}")
                }
            }
            get("/open") { call.respondText(runCatching { call.maxInitData() }.exceptionOrNull()!!.message!!) }
        }

        val ok = client.get("/api/me") { header(MaxWebAppConfig.DEFAULT_HEADER, initData()) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("42 q1", ok.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(MaxWebAppConfig.DEFAULT_HEADER, "") }.status)
        val forged = client.get("/api/me") { header(MaxWebAppConfig.DEFAULT_HEADER, initData().replace("q1", "q2")) }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertContains(forged.bodyAsText(), "signature")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(MaxWebAppConfig.DEFAULT_HEADER, initData(botToken = "x")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(MaxWebAppConfig.DEFAULT_HEADER, initData(user = null)) }.status)
        // The handler ran only for the valid call.
        assertEquals(listOf("me"), handled)

        assertContains(client.get("/open").bodyAsText(), "Install the MaxWebApp plugin")
    }

    @Test
    fun `old initData and other sources`(): Unit = testApplication {
        routing {
            route("/strict") {
                install(MaxWebApp) {
                    botToken = token
                    clock = fixed
                    maxAge = Duration.ofSeconds(59)
                }
                get { call.respondText("never") }
            }
            route("/header") {
                install(MaxWebApp) {
                    botToken = token
                    clock = fixed
                    header("X-Init")
                }
                get { call.respondText(call.maxWebAppUser().firstName) }
            }
            route("/body") {
                install(MaxWebApp) {
                    botToken = token
                    clock = fixed
                    requireUser = false
                    initData { it.receiveText() }
                }
                post {
                    val data = call.maxInitData()
                    call.respondText(data.user?.firstName ?: runCatching { call.maxWebAppUser() }.exceptionOrNull()!!.message!!)
                }
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/strict") { header(MaxWebAppConfig.DEFAULT_HEADER, initData()) }.status)
        assertEquals("Anna", client.get("/header") { header("X-Init", initData()) }.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/header") { header(MaxWebAppConfig.DEFAULT_HEADER, initData()) }.status)
        assertEquals("Anna", client.post("/body") { setBody(initData()) }.bodyAsText())
        assertEquals("initData has no user", client.post("/body") { setBody(initData(user = null)) }.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.post("/body").status)
    }

    @Test
    fun `wrong settings`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication { routing { route("/a") { install(MaxWebApp) } } ; client.get("/a") }
        }
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                routing {
                    route("/a") {
                        install(MaxWebApp) {
                            botToken = token
                            maxAge = Duration.ofSeconds(-1)
                        }
                    }
                }
                client.get("/a")
            }
        }
    }
}
