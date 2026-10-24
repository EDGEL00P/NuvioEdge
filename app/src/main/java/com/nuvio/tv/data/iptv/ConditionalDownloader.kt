package com.nuvio.tv.data.iptv

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Server validators remembered with each cached download. */
internal data class Validators(val etag: String?, val lastModified: String?)

/**
 * Downloads into the encrypted cache, asking the server whether the file changed first
 * (If-None-Match / If-Modified-Since). Large playlists and guides are then only downloaded again
 * when they actually change.
 */
internal class ConditionalDownloader(
    private val http: OkHttpClient,
    private val cache: IptvFileCache
) {
    /** @return true when new content was stored, false when the cached copy is still current. */
    fun fetch(key: String, url: String, maxBytes: Long): Boolean {
        val validators = if (cache.ageMs(key) != null) cache.readValidators(key) else null
        val request = Request.Builder().url(url).apply {
            validators?.etag?.let { header("If-None-Match", it) }
            validators?.lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304 && validators != null) {
                cache.touch(key)
                return false
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > maxBytes) throw IptvSafety.TooLargeException()
            cache.write(key) { out -> IptvSafety.copyLimited(body.byteStream(), out, maxBytes) }
            cache.writeValidators(key, Validators(response.header("ETag"), response.header("Last-Modified")))
            return true
        }
    }
}
