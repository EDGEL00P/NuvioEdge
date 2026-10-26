package com.nuvio.tv.data.iptv

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ConditionalDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @After
    fun tearDown() = server.shutdown()

    private fun downloader(): Pair<ConditionalDownloader, IptvFileCache> {
        val cache = IptvFileCache(tmp.newFolder(), { key })
        return ConditionalDownloader(OkHttpClient(), cache) to cache
    }

    @Test
    fun `unchanged file is not downloaded again`() {
        val (dl, cache) = downloader()
        val url = server.url("/playlist.m3u").toString()

        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody("#EXTM3U v1"))
        assertTrue(dl.fetch("k", url, 1_000))
        assertNull(server.takeRequest().getHeader("If-None-Match"))

        server.enqueue(MockResponse().setResponseCode(304))
        assertFalse(dl.fetch("k", url, 1_000))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals("#EXTM3U v1", cache.read("k") { String(it.readBytes()) })

        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v2\"").setBody("#EXTM3U v2"))
        assertTrue(dl.fetch("k", url, 1_000))
        assertEquals("#EXTM3U v2", cache.read("k") { String(it.readBytes()) })
    }

    @Test
    fun `last-modified is used when there is no etag`() {
        val (dl, _) = downloader()
        val url = server.url("/guide.xml").toString()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Last-Modified", "Sat, 24 Oct 2026 10:00:00 GMT").setBody("<tv/>"))
        dl.fetch("g", url, 1_000)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(304))
        assertFalse(dl.fetch("g", url, 1_000))
        assertEquals("Sat, 24 Oct 2026 10:00:00 GMT", server.takeRequest().getHeader("If-Modified-Since"))
    }

    @Test
    fun `oversized responses are rejected and not cached`() {
        val (dl, cache) = downloader()
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(2_000)))
        assertThrows(IptvSafety.TooLargeException::class.java) {
            dl.fetch("big", server.url("/big").toString(), 1_000)
        }
        assertNull(cache.ageMs("big"))
    }

    @Test
    fun `successful response without body is rejected and not cached`() {
        val (dl, cache) = downloader()
        server.enqueue(MockResponse().setResponseCode(204))

        assertThrows(IOException::class.java) {
            dl.fetch("empty", server.url("/empty").toString(), 1_000)
        }
        assertNull(cache.ageMs("empty"))
    }

    @Test
    fun `validators stay encrypted on disk`() {
        val (dl, _) = downloader()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"secret-etag\"").setBody("data"))
        dl.fetch("k", server.url("/p").toString(), 1_000)
        val onDisk = tmp.root.walk().filter { it.isFile }.map { String(it.readBytes(), Charsets.ISO_8859_1) }.toList()
        assertTrue(onDisk.isNotEmpty())
        assertTrue(onDisk.none { it.contains("secret-etag") || it.contains("data") })
    }
}
