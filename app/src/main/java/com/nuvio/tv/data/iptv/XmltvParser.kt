// XMLTV guide parsing for Nuvio Live. Behaviour modelled on VesperTV lib/services/epg_service.dart
// (Copyright (C) 2026 NoXqc, GPL-3.0); rewritten as a streaming SAX parser for NuvioTV.
package com.nuvio.tv.data.iptv

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

data class XmltvResult(
    /** Guide channel id -> programmes sorted by start time. */
    val programmes: Map<String, List<EpgProgramme>>,
    /** Normalized display name -> guide channel id, for playlists without tvg-id. */
    val channelIdsByName: Map<String, String>
)

/**
 * Streams an XMLTV document (plain or gzip) and keeps only programmes overlapping
 * [nowMs] - [pastWindowMs] .. [nowMs] + [futureWindowMs], so multi-hundred-MB guides stay small
 * in memory.
 */
class XmltvParser(
    private val nowMs: Long,
    private val pastWindowMs: Long = 2 * HOUR_MS,
    private val futureWindowMs: Long = 24 * HOUR_MS
) {

    /**
     * @param wantedIds guide ids to keep (tvg-id / epg_channel_id); null with [wantedNames] null = keep all.
     * @param wantedNames normalized channel names; a guide channel whose display-name matches is kept too.
     */
    fun parse(
        input: InputStream,
        wantedIds: Set<String>? = null,
        wantedNames: Set<String>? = null
    ): XmltvResult {
        val keepAll = wantedIds == null && wantedNames == null
        val handler = Handler(
            windowStart = nowMs - pastWindowMs,
            windowEnd = nowMs + futureWindowMs,
            keepAll = keepAll,
            wantedIds = HashSet(wantedIds.orEmpty()),
            wantedNames = wantedNames.orEmpty()
        )
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        // Entity-expansion and size limits where the platform supports them.
        runCatching { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        // Guides come from third-party servers: never resolve external entities (XXE).
        listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        ).forEach { feature -> runCatching { factory.setFeature(feature, false) } }
        // Parse through the XMLReader: SAXParser.parse(source, handler) would replace this resolver.
        val reader = factory.newSAXParser().xmlReader.apply {
            contentHandler = handler
            errorHandler = handler
            entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
        }
        try {
            reader.parse(InputSource(maybeGunzip(input)))
        } catch (e: org.xml.sax.SAXParseException) {
            // A bad character or a cut-off download: keep everything read before it.
        }
        return XmltvResult(
            programmes = handler.programmes.mapValues { (_, list) -> fillMissingStops(list.sortedBy { it.startMs }) },
            channelIdsByName = handler.namesToIds
        )
    }

    /** `stop` is optional in XMLTV: a programme without one ends when the next starts (at most 3 h). */
    private fun fillMissingStops(sorted: List<EpgProgramme>): List<EpgProgramme> =
        sorted.mapIndexed { i, p ->
            if (p.endMs != UNKNOWN_STOP) p
            else p.copy(endMs = minOf(sorted.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE, p.startMs + DEFAULT_LENGTH_MS))
        }

    private class Handler(
        private val windowStart: Long,
        private val windowEnd: Long,
        private val keepAll: Boolean,
        private val wantedIds: MutableSet<String>,
        private val wantedNames: Set<String>
    ) : DefaultHandler() {
        val programmes = HashMap<String, MutableList<EpgProgramme>>()
        val namesToIds = HashMap<String, String>()

        private val text = StringBuilder()
        private var capturing = false
        private var channelId: String? = null
        private var progChannel: String? = null
        private var progStart = 0L
        private var progEnd = 0L
        private var progTitle: String? = null
        private var progDesc: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "channel" -> channelId = attributes.getValue("id")?.trim()
                "display-name" -> if (channelId != null) startCapture()
                "programme" -> {
                    val start = parseXmltvTime(attributes.getValue("start"))
                    val stop = parseXmltvTime(attributes.getValue("stop"))
                    val channel = attributes.getValue("channel")?.trim()
                    val latestEnd = stop ?: start?.plus(DEFAULT_LENGTH_MS)
                    progChannel = if (channel != null && start != null && latestEnd != null &&
                        latestEnd > windowStart && start < windowEnd &&
                        (keepAll || channel in wantedIds)
                    ) channel else null
                    progStart = start ?: 0L
                    progEnd = stop ?: UNKNOWN_STOP
                    progTitle = null
                    progDesc = null
                }
                "title" -> if (progChannel != null && progTitle == null) startCapture()
                "desc" -> if (progChannel != null && progDesc == null) startCapture()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) {
                val room = IptvSafety.MAX_TEXT_CHARS - text.length
                if (room > 0) text.append(ch, start, minOf(length, room))
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "display-name" -> if (capturing) {
                    val id = channelId
                    val key = normalizeChannelName(endCapture())
                    if (id != null && key.isNotEmpty()) {
                        namesToIds.putIfAbsent(key, id)
                        if (key in wantedNames) wantedIds += id
                    }
                }
                "channel" -> channelId = null
                "title" -> if (capturing) progTitle = endCapture()
                "desc" -> if (capturing) progDesc = endCapture().take(MAX_DESC_CHARS)
                "programme" -> {
                    val channel = progChannel
                    if (channel != null) {
                        programmes.getOrPut(channel) { ArrayList() } += EpgProgramme(
                            startMs = progStart,
                            endMs = progEnd,
                            title = progTitle?.takeIf { it.isNotBlank() } ?: "",
                            description = progDesc?.takeIf { it.isNotBlank() }
                        )
                    }
                    progChannel = null
                }
            }
        }

        private fun startCapture() {
            text.setLength(0)
            capturing = true
        }

        private fun endCapture(): String {
            capturing = false
            return text.toString().trim()
        }
    }

    companion object {
        const val HOUR_MS = 3_600_000L

        private fun maybeGunzip(input: InputStream): InputStream {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
        }

        /** Parses "yyyyMMddHHmmss[ +hhmm]" (seconds and offset optional; no offset = UTC). */
        fun parseXmltvTime(value: String?): Long? {
            val s = value?.trim() ?: return null
            if (s.length < 12) return null
            fun num(from: Int, to: Int): Int? = s.substring(from, to).toIntOrNull()
            val year = num(0, 4) ?: return null
            val month = num(4, 6) ?: return null
            val day = num(6, 8) ?: return null
            val hour = num(8, 10) ?: return null
            val minute = num(10, 12) ?: return null
            val second = if (s.length >= 14 && s[12].isDigit()) num(12, 14) ?: 0 else 0
            if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
            var offsetMinutes = 0
            val rest = s.substring(if (s.length >= 14 && s[12].isDigit()) 14 else 12).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val digits = rest.substring(1).replace(":", "")
                val hh = digits.take(2).toIntOrNull()
                val mm = digits.drop(2).take(2).toIntOrNull()
                if (hh != null && mm != null) {
                    offsetMinutes = (hh * 60 + mm) * if (rest[0] == '-') -1 else 1
                }
            }
            val days = daysFromCivil(year, month, day)
            val epochSeconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
            return epochSeconds * 1000L
        }

        private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468L
        }

        private const val UNKNOWN_STOP = Long.MIN_VALUE
        private const val DEFAULT_LENGTH_MS = 3 * 60 * 60 * 1000L
        /** Enough for a guide preview line; full 4K-character descriptions cost too much memory on TV boxes. */
        private const val MAX_DESC_CHARS = 280

        private val QUALITY_SUFFIX = Regex("(fhd|uhd|hd|sd|4k|hevc)$")
        private val BRACKETED = Regex("""\(.*?\)|\[.*?]""")

        /** "BBC One HD" / "bbc.one" / "BBC ONE (UK)" all normalise close to "bbcone". */
        fun normalizeChannelName(name: String): String {
            val cleaned = name.lowercase()
                .replace(BRACKETED, "")
                .filter { it.isLetterOrDigit() }
            return cleaned.replace(QUALITY_SUFFIX, "").ifEmpty { cleaned }
        }
    }
}
