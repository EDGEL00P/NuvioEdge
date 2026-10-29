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
    val manualEpgMappings: Map<String, String> = emptyMap()
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
    val headers: Map<String, String> = emptyMap()
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

class IptvException(message: String, cause: Throwable? = null) : Exception(message, cause)
