package ru.securitytrip.maxapi.client

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for the Max API.
 *
 * The API server `platform-api2.max.ru` has a certificate issued under the Russian Trusted Root CA
 * of the Ministry of Digital Development, which JDK trust stores do not include, so a plain JVM fails
 * with `PKIX path building failed`. The library bundles that root certificate, and the `OkHttpClient`
 * it creates trusts it in addition to the certificates the JVM trusts (see
 * [MaxClientConfig.trustRussianRootCa]), and only for the hosts of Max (`max.ru` and its subdomains):
 * other hosts, such as the upload hosts, are checked against the certificates the JVM trusts alone.
 * The trust of the rest of the JVM is not changed: there is no need for `-Djavax.net.ssl.trustStore`
 * or for importing the certificate into `cacerts`.
 *
 * An `OkHttpClient` you pass in [MaxClientConfig.httpClient] is used as is. Make it trust the
 * certificate with [trustRussianRootCa]:
 *
 * ```kotlin
 * val http = MaxTls.trustRussianRootCa(OkHttpClient.Builder()).proxy(proxy).build()
 * ```
 *
 * Other HTTP clients can use [sslContext] or [trustManager].
 */
public object MaxTls {
    /** The bundled Russian Trusted Root CA certificate, valid until 2032-02-27. */
    @JvmField
    public val russianTrustedRootCa: X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(MaxTls::class.java.getResource("russian-trusted-root-ca.pem").readBytes()))
        as X509Certificate

    /**
     * Trusts the certificates the JVM trusts (its default trust store, or the one set with
     * `javax.net.ssl.trustStore`), and [russianTrustedRootCa] for the hosts of Max. When the TLS
     * library does not tell the host (the methods without a socket or engine), it trusts
     * [russianTrustedRootCa] for any host.
     */
    @JvmField
    public val trustManager: X509TrustManager = createTrustManager()

    /** TLS context that uses [trustManager]. */
    @JvmField
    public val sslContext: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    /** Makes [builder] use [trustManager] and returns it. */
    @JvmStatic
    public fun trustRussianRootCa(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)

    /** Whether [host] belongs to Max, the only hosts the Russian Trusted Root CA is trusted for. */
    internal fun isMaxHost(host: String?): Boolean = host != null && (host.equals("max.ru", true) || host.endsWith(".max.ru", true))

    private fun createTrustManager(): X509TrustManager {
        val jvm = trustManagerOf(null)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val certificates = jvm.acceptedIssuers + russianTrustedRootCa
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca-$index", certificate) }
        return hostAware(jvm, trustManagerOf(store))
    }

    /**
     * [HostTrustManager] of [jvm] and [max]. A security provider whose trust managers are not
     * `X509ExtendedTrustManager` does not tell the host, so then [max] trusts the Russian CA for any host.
     */
    internal fun hostAware(jvm: X509TrustManager, max: X509TrustManager): X509TrustManager =
        if (jvm is X509ExtendedTrustManager && max is X509ExtendedTrustManager) HostTrustManager(jvm, max) else max

    /** The PKIX trust manager for [store]; `null` means the JVM's default trust store. */
    private fun trustManagerOf(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Checks servers of Max against [max], which adds the Russian CA, and other servers against [jvm]. */
    internal class HostTrustManager(
        private val jvm: X509ExtendedTrustManager,
        private val max: X509ExtendedTrustManager,
    ) : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            val host = (socket as? SSLSocket)?.handshakeSession?.peerHost
            (if (isMaxHost(host)) max else jvm).checkServerTrusted(chain, authType, socket)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
            (if (isMaxHost(engine?.peerHost)) max else jvm).checkServerTrusted(chain, authType, engine)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            max.checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            jvm.checkClientTrusted(chain, authType, socket)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
            jvm.checkClientTrusted(chain, authType, engine)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            jvm.checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = max.acceptedIssuers
    }
}
