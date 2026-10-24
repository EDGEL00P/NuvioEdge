// Ported from VesperTV lib/services/xtream_api_service.dart (Copyright (C) 2026 NoXqc, GPL-3.0).
// Modified for NuvioTV.
package com.nuvio.tv.data.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Talks to an Xtream Codes panel via `player_api.php`. Network I/O is done by the caller. */
object XtreamClient {

    data class Account(val status: String?, val expiresAtMs: Long?, val maxConnections: Int?)

    private val json = Json { ignoreUnknownKeys = true }

    fun normalizeServer(raw: String): String {
        var server = raw.trim().trimEnd('/')
        if (!server.contains("://")) server = "http://$server"
        // People often paste a full player_api.php or get.php link.
        server = server.substringBefore("/player_api.php").substringBefore("/get.php")
        return server.trimEnd('/')
    }

    private fun base(source: IptvSource): HttpUrl =
        normalizeServer(source.url).toHttpUrlOrNull() ?: throw IptvException("Invalid server address")

    fun apiUrl(source: IptvSource, action: String? = null): HttpUrl =
        base(source).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", source.username)
            .addQueryParameter("password", source.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()

    fun xmltvUrl(source: IptvSource): String =
        base(source).newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", source.username)
            .addQueryParameter("password", source.password)
            .build()
            .toString()

    /**
     * .ts rather than .m3u8 (from VesperTV): HLS keeps re-fetching a manifest during playback and
     * relays often don't retry a failed mid-session refresh; plain TS is one continuous connection.
     */
    fun liveStreamUrl(source: IptvSource, streamId: String): String =
        base(source).newBuilder()
            .addPathSegment("live")
            .addPathSegment(source.username)
            .addPathSegment(source.password)
            .addPathSegment("$streamId.ts")
            .build()
            .toString()

    fun parseAccount(body: String): Account {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw IptvException("The server did not return an Xtream response")
        val user = root["user_info"] as? JsonObject ?: throw IptvException("Login rejected")
        val auth = user.string("auth")
        if (auth != "1" && auth != "true") throw IptvException("Login rejected")
        return Account(
            status = user.string("status"),
            expiresAtMs = user.string("exp_date")?.toLongOrNull()?.times(1000),
            maxConnections = user.string("max_connections")?.toIntOrNull()
        )
    }

    fun parseCategories(body: String): Map<String, String> {
        val array = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyMap()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("category_id") ?: return@mapNotNull null
            id to (obj.string("category_name")?.takeIf { it.isNotBlank() } ?: M3uParser.UNCATEGORIZED)
        }.toMap()
    }

    fun parseLiveStreams(body: String, source: IptvSource, categories: Map<String, String>): List<IptvChannel> {
        val array = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: throw IptvException("The server returned an invalid channel list")
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val streamId = obj.string("stream_id") ?: return@mapNotNull null
            val name = obj.string("name")?.trim().orEmpty().ifEmpty { "Channel $streamId" }
            IptvChannel(
                id = "${source.id}::$streamId",
                sourceId = source.id,
                name = name,
                group = obj.string("category_id")?.let(categories::get) ?: M3uParser.UNCATEGORIZED,
                streamUrl = liveStreamUrl(source, streamId),
                logoUrl = obj.string("stream_icon")?.takeIf(IptvSafety::isWebUrl),
                epgId = obj.string("epg_channel_id")?.takeIf { it.isNotBlank() },
                epgName = name,
                number = obj.string("num")?.toIntOrNull()
            )
        }
    }

    /** Xtream panels mix numbers, strings and nulls for the same field. */
    private fun JsonObject.string(key: String): String? = when (val value: JsonElement? = this[key]) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it != "null" }
        else -> null
    }
}
