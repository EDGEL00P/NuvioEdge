package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Extracts a nearby still for seek previews without seeking or rebuilding the active player. */
internal class PlayerTrickplayFrameLoader {
    private val extractionMutex = Mutex()
    private val frameCache = object : LruCache<String, Bitmap>(MAX_CACHE_KILOBYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    suspend fun frameAt(
        sourceUrl: String,
        headers: Map<String, String>,
        positionMs: Long
    ): Bitmap? {
        if (!PlayerTrickplayFrameRequest.supports(sourceUrl)) return null
        val bucketMs = PlayerTrickplayFrameRequest.bucketMs(positionMs)
        val cacheKey = cacheKey(sourceUrl, headers, bucketMs)
        frameCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            extractionMutex.withLock {
                frameCache.get(cacheKey)?.let { return@withLock it }
                val retriever = MediaMetadataRetriever()
                try {
                    val uri = Uri.parse(sourceUrl)
                    if (uri.scheme.equals("file", ignoreCase = true)) {
                        retriever.setDataSource(uri.path ?: return@withLock null)
                    } else {
                        retriever.setDataSource(sourceUrl, headers)
                    }
                    retriever.getFrameAtTime(
                        bucketMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )?.also { bitmap -> frameCache.put(cacheKey, bitmap) }
                } catch (_: RuntimeException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }

    fun clear() {
        frameCache.evictAll()
    }

    private fun cacheKey(sourceUrl: String, headers: Map<String, String>, bucketMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceUrl.toByteArray(Charsets.UTF_8))
        headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        val sourceHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return "$sourceHash:$bucketMs"
    }

    private companion object {
        const val MAX_CACHE_KILOBYTES = 24 * 1024
    }
}

internal object PlayerTrickplayFrameRequest {
    const val FRAME_BUCKET_MS = 5_000L

    fun bucketMs(positionMs: Long): Long =
        (positionMs.coerceAtLeast(0L) / FRAME_BUCKET_MS) * FRAME_BUCKET_MS

    fun supports(sourceUrl: String): Boolean =
        Uri.parse(sourceUrl).scheme?.lowercase() in setOf("http", "https", "file")
}
