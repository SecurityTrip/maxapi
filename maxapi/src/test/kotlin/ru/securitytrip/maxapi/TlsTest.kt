package ru.securitytrip.maxapi

import okhttp3.OkHttpClient
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxTls
import ru.securitytrip.maxapi.events.MaxEventListener
import java.security.MessageDigest
import java.time.Duration
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TlsTest {
    @Test
    fun `the bundled certificate is the Russian Trusted Root CA`() {
        val certificate = MaxTls.russianTrustedRootCa
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
        // The root the certificate chain of platform-api2.max.ru ends with
        assertEquals("D26D2D0231B7C39F92CC738512BA54103519E4405D68B5BD703E9788CA8ECF31", fingerprint)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        certificate.checkValidity()
    }

    @Test
    fun `the trust manager adds the root CA to the certificates the JVM trusts`() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
        val system = (factory.trustManagers[0] as X509TrustManager).acceptedIssuers.toSet()
        val trusted = MaxTls.trustManager.acceptedIssuers.toSet()
        assertEquals(system + MaxTls.russianTrustedRootCa, trusted)
        assertTrue(system.isNotEmpty())
        MaxTls.trustManager.checkServerTrusted(arrayOf(MaxTls.russianTrustedRootCa), "RSA")
    }

    @Test
    fun `the client's own OkHttpClient trusts the root CA unless turned off`() {
        MaxClient("token").use { assertSame(MaxTls.trustManager, it.transport.http.x509TrustManager) }
        MaxClient(MaxClientConfig("token", trustRussianRootCa = false)).use {
            assertNotSame(MaxTls.trustManager, it.transport.http.x509TrustManager)
        }
        val config = MaxClientConfig.builder("token").trustRussianRootCa(false).build()
        assertEquals(false, config.trustRussianRootCa)
    }

    @Test
    fun `a supplied OkHttpClient is used as is or configured with MaxTls`() {
        val plain = OkHttpClient()
        MaxClient(MaxClientConfig("token", httpClient = plain)).use { assertSame(plain, it.transport.http) }
        val configured = MaxTls.trustRussianRootCa(OkHttpClient.Builder()).build()
        assertSame(MaxTls.trustManager, configured.x509TrustManager)
        assertEquals("TLS", MaxTls.sslContext.protocol)
    }

    @Test
    fun `the constructor of 1_0 still exists`() {
        val types = arrayOf(
            String::class.java, String::class.java, OkHttpClient::class.java, ru.securitytrip.maxapi.client.RetryPolicy::class.java,
            Int::class.javaPrimitiveType, Duration::class.java, String::class.java,
            ru.securitytrip.maxapi.client.RateLimit::class.java, MaxEventListener::class.java,
        )
        val config = MaxClientConfig::class.java.getConstructor(*types).newInstance(
            "token", "https://example.test", null, ru.securitytrip.maxapi.client.RetryPolicy.NONE, 2, Duration.ZERO, "agent", null, MaxEventListener.NONE,
        )
        assertEquals("https://example.test", config.baseUrl)
        assertEquals(2, config.attachmentReadyRetries)
        assertTrue(config.trustRussianRootCa)
    }
}
