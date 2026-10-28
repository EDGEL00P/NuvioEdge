// Nuvio Live: IPTV support for NuvioTV.
// M3U, Xtream and XMLTV handling is ported from VesperTV (https://github.com/NoXqc/VesperTV),
// Copyright (C) 2026 NoXqc, GPL-3.0. Modified for NuvioTV.
package com.nuvio.tv.data.iptv

import kotlinx.serialization.Serializable

@Serializable
enum class IptvSourceType { M3U, XTREAM }

/**
 * A user-added live TV source. Every field that can carry credentials ([url], [username],
 * [password], [epgUrl]) is only ever persisted encrypted (see [IptvSourcesStore]).
 */
@Serializable
data class IptvSource(
    val id: String,
    val name: String,
    val type: IptvSourceType,
    /** M3U playlist URL, or the Xtream panel base URL (scheme://host:port). */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional XMLTV guide URL. Blank = use the playlist's url-tvg or the Xtream xmltv.php. */
    val epgUrl: String = "",
    val enabled: Boolean = true,
    /** User-confirmed channel id -> XMLTV channel id overrides; persisted inside the encrypted source store. */
    val manualEpgMappings: Map<String, String> = emptyMap(),
    /** Added to every guide time from this source, for providers whose XMLTV clock is consistently off. */
    val guideShiftMinutes: Int = 0,
    /** Added only to archive requests when provider catch-up timestamps differ from guide time. */
    val catchupShiftMinutes: Int = 0
) {
    /** True when the playlist/panel or guide is fetched over plain HTTP. */
    val isUnencrypted: Boolean
        get() = isCleartext(url) || (epgUrl.isNotBlank() && isCleartext(epgUrl))

    /** Changes whenever anything that affects the fetched content changes. */
    val contentKey: String
        get() = listOf(type.name, url, username, password, epgUrl).joinToString("\u0000")

    override fun toString(): String = "IptvSource(id=$id, name=$name, type=$type)"

    companion object {
        fun isCleartext(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

        const val GUIDE_SHIFT_STEP_MINUTES = 30
        const val MAX_GUIDE_SHIFT_MINUTES = 12 * 60
        const val CATCHUP_SHIFT_STEP_MINUTES = 30
        const val MAX_CATCHUP_SHIFT_MINUTES = 12 * 60

        fun clampGuideShift(minutes: Int): Int {
            val stepped = Math.round(minutes / GUIDE_SHIFT_STEP_MINUTES.toFloat()) * GUIDE_SHIFT_STEP_MINUTES
            return stepped.coerceIn(-MAX_GUIDE_SHIFT_MINUTES, MAX_GUIDE_SHIFT_MINUTES)
        }

        fun clampCatchupShift(minutes: Int): Int {
            val stepped = Math.round(minutes / CATCHUP_SHIFT_STEP_MINUTES.toFloat()) * CATCHUP_SHIFT_STEP_MINUTES
            return stepped.coerceIn(-MAX_CATCHUP_SHIFT_MINUTES, MAX_CATCHUP_SHIFT_MINUTES)
        }
    }
}

data class IptvChannel(
    /** Unique within the app: "<sourceId>::<n>". */
    val id: String,
    val sourceId: String,
    val name: String,
    val group: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    /** tvg-id / epg_channel_id used to match the XMLTV guide. */
    val epgId: String? = null,
    /** tvg-name, used as a fallback guide match when [epgId] is missing. */
    val epgName: String? = null,
    val number: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Days of provider archive (Xtream `tv_archive_duration`); 0 = no catch-up. */
    val catchupDays: Int = 0,
    /** M3U archive URL template; supported tokens: {utc}, {utcend}, {duration}. */
    val catchupTemplate: String? = null,
    /** Source-level archive correction copied onto loaded channels for consistent UI eligibility. */
    val catchupShiftMinutes: Int = 0
) {
    override fun toString(): String = "IptvChannel(id=$id, name=$name, group=$group)"
}

data class IptvPlaylist(
    val channels: List<IptvChannel>,
    /** Guide URL announced in the playlist header (url-tvg / x-tvg-url), if any. */
    val headerEpgUrl: String? = null,
    /** True when the source had more channels than this device keeps (see the channel limit). */
    val truncated: Boolean = false
)

data class EpgProgramme(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String? = null
)

internal fun List<EpgProgramme>.shiftedBy(shiftMs: Long): List<EpgProgramme> =
    if (shiftMs == 0L) this else map { it.copy(startMs = it.startMs + shiftMs, endMs = it.endMs + shiftMs) }

class IptvException(message: String, cause: Throwable? = null) : Exception(message, cause)
