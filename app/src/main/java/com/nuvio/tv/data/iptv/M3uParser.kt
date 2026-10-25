// Ported from VesperTV by NoXqc (lib/services/m3u_parser.dart), GPL-3.0.
// Modified for NuvioEdge, October 2026: comma-safe names, header guide URL, per-channel HTTP
// headers, 64-bit channel ids, channel limit, films and series skipped.
package com.nuvio.tv.data.iptv

import java.io.Reader

/**
 * Parses M3U / M3U8 playlists one line at a time, so huge Xtream `get.php` playlists are never
 * held in memory as a single string.
 *
 * Supports `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`, `group-title`, the `#EXTGRP:` fallback,
 * `#EXTVLCOPT:http-user-agent=` / `http-referrer=` and the header's `url-tvg` / `x-tvg-url`.
 */
/** @param maxChannels stop adding channels after this many (memory limit on small TV boxes). */
class M3uParser(private val sourceId: String, private val maxChannels: Int = Int.MAX_VALUE) {

    private val channels = ArrayList<IptvChannel>()
    private var headerEpgUrl: String? = null
    private var attrs: MutableMap<String, String> = HashMap()
    private var pendingName: String? = null
    private var pendingHeaders: MutableMap<String, String> = HashMap()
    private var counter = 0
    private val seen = HashMap<String, Int>()
    private val usedKeys = HashSet<String>()
    private var truncated = false

    fun consumeLine(rawLine: String) {
        val line = rawLine.trim().removePrefix("\uFEFF")
        if (line.isEmpty()) return
        when {
            line.startsWith("#EXTM3U") -> {
                val header = parseAttributes(line)
                headerEpgUrl = (header["url-tvg"] ?: header["x-tvg-url"])
                    ?.split(',')
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
            }
            line.startsWith("#EXTINF") -> {
                attrs = parseAttributes(line)
                pendingName = extractName(line)
                pendingHeaders = HashMap()
                attrs["user-agent"]?.let(IptvSafety::cleanHeaderValue)?.let { pendingHeaders["User-Agent"] = it }
            }
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val option = line.substringAfter(':')
                val key = option.substringBefore('=').trim().lowercase()
                val value = IptvSafety.cleanHeaderValue(option.substringAfter('=', ""))
                if (value != null) {
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
            }
            line.startsWith("#EXTGRP:") -> attrs["group-title"] = line.substringAfter(':').trim()
            line.startsWith("#") -> Unit // #EXT-X-*, comments, unsupported directives
            else -> finishEntry(line)
        }
    }

    fun parse(reader: Reader): IptvPlaylist {
        reader.use { IptvSafety.forEachLineCapped(it, IptvSafety.MAX_LINE_CHARS, ::consumeLine) }
        return result()
    }

    fun result(): IptvPlaylist = IptvPlaylist(channels.toList(), headerEpgUrl, truncated)

    private fun finishEntry(url: String) {
        val name = pendingName
        // Only network streams: a playlist must not point the player at files on the device.
        if (name != null && channels.size >= maxChannels && IptvSafety.isAllowedStream(url)) truncated = true
        // Xtream "m3u_plus" playlists also list films and series; those aren't live channels.
        if (name != null && IptvSafety.isAllowedStream(url) && !isVodUrl(url) && channels.size < maxChannels) {
            counter++
            val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: UNCATEGORIZED
            // Stable across refreshes and reordering (favourites survive), unique for duplicates.
            val base = "${attrs["tvg-id"].orEmpty()}|$name|$group"
            val occurrence = seen.merge(base, 1, Int::plus) ?: 1
            // 64-bit hash: a 32-bit one collides in ~25% of 50k-channel playlists (shared favourites,
            // duplicate list keys). The loop keeps ids unique even if two names ever hash alike.
            var stableKey = stableHash(base) + if (occurrence > 1) "-$occurrence" else ""
            while (!usedKeys.add(stableKey)) stableKey += "~"
            channels += IptvChannel(
                id = "$sourceId::$stableKey",
                sourceId = sourceId,
                name = name.ifBlank { attrs["tvg-name"].orEmpty().ifBlank { "Channel $counter" } },
                group = group,
                streamUrl = url,
                logoUrl = attrs["tvg-logo"]?.takeIf(IptvSafety::isWebUrl),
                epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                epgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                number = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
                headers = pendingHeaders.toMap()
            )
        }
        pendingName = null
        attrs = HashMap()
        pendingHeaders = HashMap()
    }

    companion object {
        private val VOD_PATH = Regex("/(movie|series)/", RegexOption.IGNORE_CASE)

        internal fun isVodUrl(url: String): Boolean = VOD_PATH.containsMatchIn(url.substringBefore('?'))

        /** FNV-1a 64-bit of the UTF-8 bytes, as 16 hex digits. Stable across runs and devices. */
        internal fun stableHash(text: String): String {
            var hash = -0x340d631b7bdddcdbL // offset basis 0xcbf29ce484222325
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001b3L
            }
            return java.lang.Long.toHexString(hash).padStart(16, '0')
        }

        const val UNCATEGORIZED = "Uncategorized"
        private val ATTR_PATTERN = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

        internal fun parseAttributes(line: String): MutableMap<String, String> {
            val map = HashMap<String, String>()
            ATTR_PATTERN.findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
            return map
        }

        /** The display name is everything after the first comma that sits outside quotes. */
        internal fun extractName(line: String): String {
            var inQuotes = false
            for (i in line.indices) {
                when (line[i]) {
                    '"' -> inQuotes = !inQuotes
                    ',' -> if (!inQuotes) return line.substring(i + 1).trim()
                }
            }
            return ""
        }
    }
}
