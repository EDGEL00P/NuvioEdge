package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.roundToInt

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
                    extractFrame(retriever, bucketMs)
                        ?.also { bitmap -> frameCache.put(cacheKey, bitmap) }
                } catch (_: RuntimeException) {
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

    private fun extractFrame(retriever: MediaMetadataRetriever, bucketMs: Long): Bitmap? {
        val timeUs = bucketMs * 1_000L
        if (PlayerTrickplayFrameRequest.usesScaledFrameApi(Build.VERSION.SDK_INT)) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                PREVIEW_FRAME_WIDTH,
                PREVIEW_FRAME_HEIGHT
            )
        }

        // Android versions before API 27 do not expose scaled extraction. Decode the nearest
        // sync frame on IO, then immediately shrink it before caching or handing it to Compose.
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val dimensions = PlayerTrickplayFrameRequest.previewDimensions(
            frame.width,
            frame.height,
            PREVIEW_FRAME_WIDTH,
            PREVIEW_FRAME_HEIGHT
        ) ?: return frame
        if (dimensions.width == frame.width && dimensions.height == frame.height) return frame

        return try {
            Bitmap.createScaledBitmap(frame, dimensions.width, dimensions.height, true)
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    private fun cacheKey(sourceUrl: String, headers: Map<String, String>, bucketMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceUrl.toByteArray(Charsets.UTF_8))
        headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        val sourceHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return "$sourceHash:$bucketMs"
    }

    private companion object {
        const val MAX_CACHE_KILOBYTES = 24 * 1024
        const val PREVIEW_FRAME_WIDTH = 480
        const val PREVIEW_FRAME_HEIGHT = 270
    }
}

internal data class PreviewFrameDimensions(val width: Int, val height: Int)

internal object PlayerTrickplayFrameRequest {
    const val FRAME_BUCKET_MS = 5_000L

    fun usesScaledFrameApi(apiLevel: Int): Boolean = apiLevel >= Build.VERSION_CODES.O_MR1

    fun bucketMs(positionMs: Long): Long =
        (positionMs.coerceAtLeast(0L) / FRAME_BUCKET_MS) * FRAME_BUCKET_MS

    fun previewDimensions(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int
    ): PreviewFrameDimensions? {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return null
        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height, 1f)
        return PreviewFrameDimensions(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    fun supports(sourceUrl: String): Boolean =
        Uri.parse(sourceUrl).scheme?.lowercase() in setOf("http", "https", "file")
}
