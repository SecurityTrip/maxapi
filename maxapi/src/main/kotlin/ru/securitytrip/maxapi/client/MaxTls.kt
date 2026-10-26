package ru.securitytrip.maxapi.client

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for the Max API.
 *
 * The API server `platform-api2.max.ru` has a certificate issued under the Russian Trusted Root CA
 * of the Ministry of Digital Development, which JDK trust stores do not include, so a plain JVM fails
 * with `PKIX path building failed`. The library bundles that root certificate, and the `OkHttpClient`
 * it creates trusts it in addition to the certificates the JVM trusts (see
 * [MaxClientConfig.trustRussianRootCa]). The trust of the rest of the JVM is not changed: there is no
 * need for `-Djavax.net.ssl.trustStore` or for importing the certificate into `cacerts`.
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
     * `javax.net.ssl.trustStore`) and [russianTrustedRootCa].
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

    private fun createTrustManager(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val certificates = trustManagerOf(null).acceptedIssuers + russianTrustedRootCa
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca-$index", certificate) }
        return trustManagerOf(store)
    }

    /** The PKIX trust manager for [store]; `null` means the JVM's default trust store. */
    private fun trustManagerOf(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers[0] as X509TrustManager
    }
}
