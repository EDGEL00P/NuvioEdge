package com.nuvio.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class IptvSafetyTest {

    @Test
    fun `only network stream schemes are allowed`() {
        listOf("http://a/b.ts", "HTTPS://a/b.m3u8", "rtsp://cam/1", "udp://@239.0.0.1:1234").forEach {
            assertTrue(it, IptvSafety.isAllowedStream(it))
        }
        listOf(
            "file:///data/data/com.nuvio.tv/shared_prefs/x.xml",
            "content://media/external/video/1",
            "asset:///video.mp4",
            "data:video/mp4;base64,AAAA",
            "android.resource://com.nuvio.tv/raw/x",
            "javascript:alert(1)",
            "intent://x#Intent;end",
            "/sdcard/movie.mp4",
            ""
        ).forEach { assertFalse(it, IptvSafety.isAllowedStream(it)) }
        assertTrue(IptvSafety.isWebUrl("https://logo/x.png"))
        assertFalse(IptvSafety.isWebUrl("rtsp://cam/1"))
    }

    @Test
    fun `header values lose control characters and are capped`() {
        assertEquals("VLC/3.0X-Evil: 1", IptvSafety.cleanHeaderValue(" VLC/3.0\r\nX-Evil: 1 "))
        assertNull(IptvSafety.cleanHeaderValue("\r\n\t"))
        assertEquals(IptvSafety.MAX_HEADER_CHARS, IptvSafety.cleanHeaderValue("a".repeat(1000))!!.length)
    }

    @Test
    fun `https to http redirects are insecure`() {
        assertTrue(IptvSafety.isInsecureRedirect(true, "http://cdn.example/x"))
        assertFalse(IptvSafety.isInsecureRedirect(true, "https://cdn.example/x"))
        assertFalse(IptvSafety.isInsecureRedirect(true, "/relative/path"))
        assertFalse(IptvSafety.isInsecureRedirect(false, "http://cdn.example/x"))
        assertFalse(IptvSafety.isInsecureRedirect(true, null))
    }

    @Test
    fun `insecure redirect error is never retried by OkHttp`() {
        assertTrue(IptvSafety.InsecureRedirectException() is java.net.ProtocolException)
    }

    @Test
    fun `copies are size limited`() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, IptvSafety.copyLimited(ByteArray(10).inputStream(), out, 10))
        assertThrows(IptvSafety.TooLargeException::class.java) {
            IptvSafety.copyLimited(ByteArray(11).inputStream(), ByteArrayOutputStream(), 10)
        }
    }

    @Test
    fun `line reader skips over-long lines and handles CRLF`() {
        val lines = mutableListOf<String>()
        val input = "a\r\n" + "x".repeat(50) + "\nb\rc"
        IptvSafety.forEachLineCapped(input.reader(), maxChars = 10) { lines += it }
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `rtmp is refused and header values are plain ASCII`() {
        assertFalse(IptvSafety.isAllowedStream("rtmp://live/x"))
        assertEquals("Mozilla/5.0", IptvSafety.cleanHeaderValue("Mozilla/5.0 \u00e9\u2603"))
    }
}
