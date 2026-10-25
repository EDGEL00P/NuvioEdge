package com.nuvio.tv.ui.screens.live

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.nuvio.tv.ui.screens.settings.MemoryBudget

/** What one hardware video decoder declares about itself. */
internal data class DecoderCaps(
    /** Decoders of this type that can run at once (`concurrent-instances`). */
    val instances: Int,
    /** Highest 1080p frame rate it can decode: measured when the maker published it, else declared. */
    val maxFps1080p: Double?
)

/** The parts of a TV or box that decide how much live video it can play at once. */
internal data class DeviceProfile(
    val totalRamMb: Long,
    val lowRamDevice: Boolean,
    val smallHeap: Boolean,
    val decoders: Map<String, DecoderCaps>
) {
    companion object {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // MemoryBudget is marked unstable upstream
        fun read(context: Context): DeviceProfile {
            val am = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
            return DeviceProfile(
                totalRamMb = memory.totalMem / (1024L * 1024L),
                lowRamDevice = am?.isLowRamDevice == true,
                smallHeap = MemoryBudget.isLowRamTier,
                decoders = DecoderBudget.probe()
            )
        }
    }
}

/**
 * How many multiview tiles this device can play. Three limits, the lowest wins:
 *  - decoder count and speed as the device declares them (MediaCodecInfo). Many devices leave the
 *    count at Android's default of 32 and declare speed as the codec standard's limit, so this
 *    rarely decides on its own;
 *  - total RAM and the low-RAM flag, which usually decide;
 *  - [learnedLimit], lowered for the session when the TV runs out of decoders or drops frames.
 */
internal object DecoderBudget {
    const val MAX_TILES = 6
    val TILE_OPTIONS = listOf(1, 2, 4, 6)
    private val LIVE_MIMES = listOf("video/avc", "video/hevc")

    /** Frame rate budgeted per tile; most live channels are 25-30 fps. 50/60 fps channels are caught by the overload check. */
    private const val TILE_FPS = 30.0

    /** Lowered for the rest of the session when a TV runs out of decoders or keeps dropping frames. */
    @Volatile
    var learnedLimit: Int = MAX_TILES
        private set

    fun tilesFor(profile: DeviceProfile, learned: Int = learnedLimit): Int {
        val known = LIVE_MIMES.mapNotNull { profile.decoders[it] }
        // Every tile may get either codec, so the scarcest decoder sets the limit.
        val byInstances = known.minOfOrNull { it.instances } ?: 2 // nothing reported: assume two
        val byThroughput = known.mapNotNull { it.maxFps1080p }.minOrNull()
            ?.let { (it / TILE_FPS).toInt() } ?: MAX_TILES
        val byMemory = when {
            // Android TV 14 allows 1 GB (1080p) and 1.5 GB (4K) devices; they declare themselves low-RAM.
            profile.lowRamDevice || profile.totalRamMb < 1_536 -> 2
            profile.totalRamMb < 2_560 || profile.smallHeap -> 4
            else -> MAX_TILES
        }
        return minOf(byInstances, byThroughput, byMemory, learned, MAX_TILES).coerceAtLeast(1)
    }

    /** Largest layout option that fits [max] tiles. */
    fun bestOption(max: Int): Int = TILE_OPTIONS.last { it <= max.coerceAtLeast(1) }

    /** The TV failed or struggled at [tiles]: never offer that many again this session. */
    fun learnFailureAt(tiles: Int): Int {
        val lower = TILE_OPTIONS.lastOrNull { it < tiles } ?: 1
        learnedLimit = minOf(learnedLimit, lower)
        return learnedLimit
    }

    internal fun resetForTest() {
        learnedLimit = MAX_TILES
    }

    /** Reads the hardware decoders. Software decoders are ignored: several at once would freeze a TV CPU. */
    fun probe(): Map<String, DecoderCaps> = runCatching {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && isHardware(it) }
        LIVE_MIMES.mapNotNull { mime ->
            val caps = infos.mapNotNull { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@mapNotNull null
                runCatching { info.getCapabilitiesForType(type) }.getOrNull()
            }
            caps.maxByOrNull { it.maxSupportedInstances }?.let { best ->
                mime to DecoderCaps(best.maxSupportedInstances, fps1080p(best))
            }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun fps1080p(caps: MediaCodecInfo.CodecCapabilities): Double? {
        val video = caps.videoCapabilities ?: return null
        return runCatching {
            if (!video.isSizeSupported(1920, 1080)) return@runCatching 0.0
            (video.getAchievableFrameRatesFor(1920, 1080) ?: video.getSupportedFrameRatesFor(1920, 1080)).upper
        }.getOrNull()
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            // Before Android 10 there is no flag; Android's own software decoders are OMX.google.* / c2.android.*
            !info.name.startsWith("OMX.google.", ignoreCase = true) && !info.name.startsWith("c2.android.", ignoreCase = true)
        }
}

/** Grid shape for each tile count: (columns, rows). */
internal fun gridFor(tiles: Int): Pair<Int, Int> = when {
    tiles <= 1 -> 1 to 1
    tiles == 2 -> 2 to 1
    tiles <= 4 -> 2 to 2
    else -> 3 to 2
}

/** Largest video each tile asks for; adaptive (HLS/DASH) streams then pick lighter variants. */
internal fun maxTileVideoSize(tiles: Int): Pair<Int, Int> = when {
    tiles <= 1 -> 1920 to 1080
    tiles == 2 -> 1280 to 720
    else -> 960 to 540
}

/** Bytes of video each tile may buffer, so several streams fit in a TV's memory. */
internal fun tileBufferBytes(lowMemory: Boolean): Int = if (lowMemory) 4 * 1024 * 1024 else 12 * 1024 * 1024
