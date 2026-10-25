package com.nuvio.tv.data.iptv

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
