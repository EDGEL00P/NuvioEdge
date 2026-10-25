package com.nuvio.tv.data.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader

/** Limits and checks applied to everything a playlist, panel or guide server sends us. */
internal object IptvSafety {
    const val MAX_PLAYLIST_BYTES = 512L * 1024 * 1024
    const val MAX_GUIDE_BYTES = 512L * 1024 * 1024
    const val MAX_API_BYTES = 64L * 1024 * 1024
    const val MAX_LOGIN_BYTES = 1L * 1024 * 1024
    const val MAX_LINE_CHARS = 64 * 1024
    const val MAX_TEXT_CHARS = 4 * 1024
    const val MAX_HEADER_CHARS = 256

    /**
     * Schemes the player may open from a playlist. The player's Media3 DefaultDataSource would also
     * open file, asset, content, data and resource URIs on the device, so only network schemes pass.
     */
    // No rtmp: the bundled player has no RTMP extension, so those channels could never play.
    private val STREAM_SCHEMES = setOf("http", "https", "rtsp", "udp")

    private fun scheme(url: String): String {
        val trimmed = url.trim()
        val idx = trimmed.indexOf("://")
        return if (idx <= 0) "" else trimmed.substring(0, idx).lowercase()
    }

    fun isAllowedStream(url: String): Boolean = scheme(url) in STREAM_SCHEMES

    fun isWebUrl(url: String): Boolean = scheme(url).let { it == "http" || it == "https" }

    /** Strips control characters (CR/LF would break the request) and caps the length. */
    fun cleanHeaderValue(value: String): String? =
        // Printable ASCII only: OkHttp rejects other characters in header values.
        value.filter { it in ' '..'~' }.trim().take(MAX_HEADER_CHARS).ifEmpty { null }

    private val XTREAM_PREFIXES = setOf("live", "movie", "series", "timeshift")
    private val XTREAM_SHORT_ID = Regex("""\d+(\.\w{1,5})?""")

    /** [url] without query, user info or Xtream login path segments, for anything stored or logged. */
    fun redactLogin(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?')
        val builder = parsed.newBuilder().query(null).fragment(null).username("").password("")
        val segments = parsed.pathSegments
        if (segments.size >= 4 && segments[0].lowercase() in XTREAM_PREFIXES) {
            builder.setPathSegment(1, "redacted").setPathSegment(2, "redacted")
        } else if (segments.size == 3 && XTREAM_SHORT_ID.matches(segments[2])) {
            builder.setPathSegment(0, "redacted").setPathSegment(1, "redacted")
        }
        return builder.build().toString()
    }

    /** True when an https request is redirected to plain http, which would resend the login unencrypted. */
    fun isInsecureRedirect(requestIsHttps: Boolean, location: String?): Boolean =
        requestIsHttps && location?.trim()?.startsWith("http://", ignoreCase = true) == true

    /** Copies at most [maxBytes]; throws instead of filling the disk. */
    fun copyLimited(input: InputStream, out: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > maxBytes) throw TooLargeException()
            out.write(buffer, 0, n)
        }
    }

    /** Line reader that never holds more than [maxChars] of one line; longer lines are skipped. */
    fun forEachLineCapped(reader: Reader, maxChars: Int, action: (String) -> Unit) {
        val buffer = CharArray(16 * 1024)
        val line = StringBuilder()
        var overflow = false
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buffer[i]
                if (c == '\n' || c == '\r') {
                    if (!overflow && line.isNotEmpty()) action(line.toString())
                    line.setLength(0)
                    overflow = false
                } else if (!overflow) {
                    if (line.length < maxChars) {
                        line.append(c)
                    } else {
                        overflow = true
                        line.setLength(0)
                    }
                }
            }
        }
        if (!overflow && line.isNotEmpty()) action(line.toString())
    }

    class TooLargeException : IOException("Response exceeds the size limit")
    /** A ProtocolException, which OkHttp never retries, so the request stops at the first attempt. */
    class InsecureRedirectException : java.net.ProtocolException("Blocked a redirect from https to http")
}
