package com.nuvio.tv.data.iptv

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class LiveStreamGuardTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        LiveStreamGuard.clearForTest()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        LiveStreamGuard.clearForTest()
    }

    @Test
    fun `protects the host of a stream URL, case-insensitively`() {
        LiveStreamGuard.protect("http://Panel.Example.com:8080/live/user/pass/1.ts")
        assertTrue(LiveStreamGuard.isProtected("panel.example.com"))
        assertFalse(LiveStreamGuard.isProtected("other.example.com"))
        LiveStreamGuard.protect("not a url")
        LiveStreamGuard.protect(null)
    }

    @Test
    fun `https to http downgrade is detected`() {
        assertTrue(IptvSafety.isInsecureRedirect(requestIsHttps = true, location = "http://evil.example/live/u/p/1.ts"))
        assertFalse(IptvSafety.isInsecureRedirect(requestIsHttps = true, location = "https://cdn.example/1.ts"))
        assertFalse(IptvSafety.isInsecureRedirect(requestIsHttps = false, location = "http://cdn.example/1.ts"))
    }

    @Test
    fun `a redirect from a protected server protects where it points`() {
        LiveStreamGuard.protect(server.url("/").toString())
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example.net/live/u/p/1.ts"))
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addNetworkInterceptor(LiveStreamGuard.redirectGuard())
            .build()
        client.newCall(Request.Builder().url(server.url("/live/u/p/1.ts")).build()).execute().use {
            assertEquals(302, it.code)
        }
        assertTrue(LiveStreamGuard.isProtected("cdn.example.net"))
    }

    @Test
    fun `redirects from unprotected servers are left alone`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example.org/x"))
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addNetworkInterceptor(LiveStreamGuard.redirectGuard())
            .build()
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertFalse(LiveStreamGuard.isProtected("cdn.example.org"))
    }

    @Test
    fun `real TLS downgrade redirects never deliver credentials to HTTP`() {
        val (context, trust) = testTls()
        MockWebServer().use { secure ->
            secure.useHttps(context.socketFactory, false)
            secure.start()
            val client = OkHttpClient.Builder()
                .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory, trust)
                .addNetworkInterceptor(LiveStreamGuard.redirectGuard(always = true))
                .build()
            val targets = listOf(server.url("/live/user/secret/1.ts").toString(), "http:\\\\127.0.0.1:${server.port}/live/user/secret/1.ts")
            for (status in listOf(301, 302, 303, 307, 308)) {
                for (target in targets) {
                    secure.enqueue(MockResponse().setResponseCode(status).setHeader("Location", target))
                    assertThrows(IptvSafety.InsecureRedirectException::class.java) {
                        client.newCall(Request.Builder().url(secure.url("/live/user/secret/1.ts").newBuilder().host("127.0.0.1").build()).build()).execute().close()
                    }
                }
            }
            assertEquals(10, secure.requestCount)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `untrusted TLS fails without retrying as an insecure request`() {
        val (context, _) = testTls()
        MockWebServer().use { secure ->
            secure.useHttps(context.socketFactory, false)
            secure.start()
            val client = OkHttpClient.Builder().addNetworkInterceptor(LiveStreamGuard.redirectGuard(always = true)).build()
            assertThrows(SSLHandshakeException::class.java) {
                client.newCall(Request.Builder().url(secure.url("/live/user/secret/1.ts").newBuilder().host("127.0.0.1").build()).build()).execute().close()
            }
            assertEquals(0, secure.requestCount)
            assertEquals(0, server.requestCount)
        }
    }

    private fun testTls(): Pair<SSLContext, X509TrustManager> {
        val password = "changeit".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            requireNotNull(LiveStreamGuardTest::class.java.getResourceAsStream("/subtitle-redirect-test.p12")).use { load(it, password) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        return SSLContext.getInstance("TLS").apply { init(keys, null, null) } to trust
    }
}
