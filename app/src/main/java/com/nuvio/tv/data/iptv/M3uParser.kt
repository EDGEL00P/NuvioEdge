// Ported from VesperTV lib/services/m3u_parser.dart (Copyright (C) 2026 NoXqc, GPL-3.0).
// Modified for NuvioTV: comma-safe names, header guide URL, per-channel HTTP headers.
package com.nuvio.tv.data.iptv

import java.io.BufferedReader

/**
 * Parses M3U / M3U8 playlists one line at a time, so huge Xtream `get.php` playlists are never
 * held in memory as a single string.
 *
 * Supports `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`, `group-title`, the `#EXTGRP:` fallback,
 * `#EXTVLCOPT:http-user-agent=` / `http-referrer=` and the header's `url-tvg` / `x-tvg-url`.
 */
class M3uParser(private val sourceId: String) {

    private val channels = ArrayList<IptvChannel>()
    private var headerEpgUrl: String? = null
    private var attrs: MutableMap<String, String> = HashMap()
    private var pendingName: String? = null
    private var pendingHeaders: MutableMap<String, String> = HashMap()
    private var counter = 0
    private val seen = HashMap<String, Int>()

    fun consumeLine(rawLine: String) {
        val line = rawLine.trim().removePrefix("﻿")
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
                attrs["user-agent"]?.takeIf { it.isNotBlank() }?.let { pendingHeaders["User-Agent"] = it }
            }
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val option = line.substringAfter(':')
                val key = option.substringBefore('=').trim().lowercase()
                val value = option.substringAfter('=', "").trim()
                if (value.isNotEmpty()) {
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

    fun parse(reader: BufferedReader): IptvPlaylist {
        reader.useLines { lines -> lines.forEach(::consumeLine) }
        return result()
    }

    fun result(): IptvPlaylist = IptvPlaylist(channels.toList(), headerEpgUrl)

    private fun finishEntry(url: String) {
        val name = pendingName
        if (name != null) {
            counter++
            val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: UNCATEGORIZED
            // Stable across refreshes and reordering (favourites survive), unique for duplicates.
            val base = "${attrs["tvg-id"].orEmpty()}|$name|$group"
            val occurrence = seen.merge(base, 1, Int::plus) ?: 1
            val stableKey = "%08x".format(base.hashCode()) + if (occurrence > 1) "-$occurrence" else ""
            channels += IptvChannel(
                id = "$sourceId::$stableKey",
                sourceId = sourceId,
                name = name.ifBlank { attrs["tvg-name"].orEmpty().ifBlank { "Channel $counter" } },
                group = group,
                streamUrl = url,
                logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
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
