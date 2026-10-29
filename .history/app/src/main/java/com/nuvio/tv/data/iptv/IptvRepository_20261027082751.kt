package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ChannelLoadResult(
    val channels: List<IptvChannel>,
    /** Source name -> human-readable failure. */
    val errors: Map<String, String>
)

enum class EpgSourceStatus { AVAILABLE, NOT_CONFIGURED, LOAD_ERROR }

data class EpgGuideCandidate(val guideId: String, val displayNames: List<String>)

data class EpgMappingReview(
    val sourceId: String,
    val channelId: String,
    val channelName: String,
    val currentGuideId: String?,
    val candidates: List<EpgGuideCandidate>
)

data class EpgSourceReport(
    val sourceId: String,
    val sourceName: String,
    val channelCount: Int,
    val exactIdMatches: Int = 0,
    val nameMatches: Int = 0,
    val manualMatches: Int = 0,
    val ambiguous: Int = 0,
    val unmatched: Int = 0,
    val status: EpgSourceStatus = EpgSourceStatus.AVAILABLE,
    val mappingReviews: List<EpgMappingReview> = emptyList(),
    val mappingReviewsTruncated: Boolean = false,
    val guideCandidates: List<EpgGuideCandidate> = emptyList(),
    val guideCatalogTruncated: Boolean = false
) {
    val matched: Int get() = exactIdMatches + nameMatches + manualMatches
}

data class GuideLoadResult(
    val programmesByChannel: Map<String, List<EpgProgramme>>,
    val sources: List<EpgSourceReport>
)

private data class GuideSourceResult(
    val programmesByChannel: Map<String, List<EpgProgramme>>,
    val report: EpgSourceReport
)

@Singleton
class IptvRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val store: IptvSourcesStore,
    vault: IptvVault,
    okHttpClient: OkHttpClient
) {
    /**
     * Separate client for IPTV traffic: no shared HTTP disk cache (it would hold plaintext
     * playlists), no request logging and no Sentry breadcrumbs (Xtream URLs carry the login in
     * the query and path).
     */
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .cache(null)
        .readTimeout(120, TimeUnit.SECONDS)
        .apply {
            interceptors().removeAll { it is HttpLoggingInterceptor || it is SentryNetworkBreadcrumbInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor || it is SentryNetworkBreadcrumbInterceptor }
        }
        // Never follow https -> http: the redirected request would carry the login in clear text.
        .addNetworkInterceptor(LiveStreamGuard.redirectGuard(always = true))
        .build()

    /**
     * For multiview players, built from scratch: no disk cache, logging, Sentry or forced User-Agent,
     * and 32 connections per server (OkHttp's default of 5 would leave a 6th live tile waiting forever).
     */
    val streamClient: OkHttpClient = OkHttpClient.Builder()
        .dns(com.nuvio.tv.core.network.IPv4FirstDns())
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .connectionPool(okhttp3.ConnectionPool())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor(LiveStreamGuard.redirectGuard(always = true))
        .build()

    private val cache = IptvFileCache(File(context.cacheDir, "nuvio_live"), vault::cacheKey)
    private val downloader = ConditionalDownloader(http, cache)
    private val playlists = ConcurrentHashMap<String, Pair<String, IptvPlaylist>>()
    private val playlistLoadedAt = ConcurrentHashMap<String, Long>()

    /** Parsed guides, reused until the downloaded file changes (re-parsing a big guide takes seconds). */
    private class GuideMemo(val wanted: Int, val parsedAtMs: Long, val result: XmltvResult)
    private val guides = ConcurrentHashMap<String, GuideMemo>()

    /** Xtream `max_connections` per source id, when the panel reports one. */
    private val connectionLimits = ConcurrentHashMap<String, Int>()
    /** Live stream extension per Xtream source ("ts", or "m3u8" for HLS-only accounts). */
    private val liveExtensions = ConcurrentHashMap<String, String>()

    /** One download or parse per cache entry at a time (Refresh pressed twice, two screens loading). */
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /** Channels kept per source: about 1 KB each in memory, so small-heap TV boxes keep fewer. */
    private val channelCap: Int =
        if (Runtime.getRuntime().maxMemory() < LOW_HEAP_BYTES) LOW_MEMORY_CHANNEL_CAP else CHANNEL_CAP

    val sources: Flow<List<IptvSource>> = store.sources
    val favorites: Flow<Set<String>> = store.favorites

    suspend fun toggleFavorite(channelId: String) = store.toggleFavorite(channelId)

    suspend fun addSource(
        name: String,
        type: IptvSourceType,
        url: String,
        username: String,
        password: String,
        epgUrl: String
    ): IptvSource {
        val cleanUrl = if (type == IptvSourceType.XTREAM) XtreamClient.normalizeServer(url) else url.trim()
        if (!IptvSafety.isWebUrl(cleanUrl)) {
            throw IptvException("Enter a full http:// or https:// address")
        }
        if (epgUrl.isNotBlank() && !IptvSafety.isWebUrl(epgUrl)) {
            throw IptvException("Enter a full http:// or https:// guide address")
        }
        val source = IptvSource(
            id = UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { if (type == IptvSourceType.XTREAM) "Xtream" else "Playlist" },
            type = type,
            url = cleanUrl,
            username = username.trim(),
            password = password,
            epgUrl = epgUrl.trim()
        )
        // Validate before saving so a typo never gets stored. Interruptible: Cancel stops the download.
        runInterruptible(Dispatchers.IO) {
            fetchPlaylist(source, force = true, allowStaleFallback = false)
        }
        store.update { it + source }
        return source
    }

    /**
     * Validates an edited source before replacing the saved entry. A temporary identity keeps a
     * failed validation from replacing the active in-memory playlist or its stable channel IDs.
     */
    suspend fun updateSource(
        id: String,
        name: String,
        type: IptvSourceType,
        url: String,
        username: String,
        password: String,
        epgUrl: String
    ): IptvSource {
        val existing = store.current().firstOrNull { it.id == id }
            ?: throw IptvException("This source is no longer available")
        val cleanUrl = if (type == IptvSourceType.XTREAM) XtreamClient.normalizeServer(url) else url.trim()
        if (!IptvSafety.isWebUrl(cleanUrl)) {
            throw IptvException("Enter a full http:// or https:// address")
        }
        val cleanEpgUrl = epgUrl.trim()
        if (cleanEpgUrl.isNotBlank() && !IptvSafety.isWebUrl(cleanEpgUrl)) {
            throw IptvException("Enter a full http:// or https:// guide address")
        }
        val updated = existing.copy(
            name = name.trim().ifEmpty { if (type == IptvSourceType.XTREAM) "Xtream" else "Playlist" },
            type = type,
            url = cleanUrl,
            username = username.trim(),
            password = password,
            epgUrl = cleanEpgUrl
        )
        val previousGuideUrl = guideUrl(existing)
        if (updated.contentKey != existing.contentKey) {
            val validationId = "${existing.id}_validate_${UUID.randomUUID()}"
            val validationSource = updated.copy(id = validationId)
            try {
                runInterruptible(Dispatchers.IO) {
                    fetchPlaylist(validationSource, force = true, allowStaleFallback = false)
                }
            } finally {
                playlists.remove(validationId)
                playlistLoadedAt.remove(validationId)
                connectionLimits.remove(validationId)
                liveExtensions.remove(validationId)
            }
        }

        store.update { sources ->
            if (sources.none { it.id == id }) throw IptvException("This source is no longer available")
            sources.map { if (it.id == id) updated else it }
        }

        if (updated.contentKey != existing.contentKey) {
            playlists.remove(id)
            playlistLoadedAt.remove(id)
            connectionLimits.remove(id)
            liveExtensions.remove(id)
            withContext(Dispatchers.IO) {
                listOf("m3u|${existing.contentKey}", "xc-cats|${existing.contentKey}", "xc-streams|${existing.contentKey}")
                    .forEach(cache::delete)
                deleteGuideEntries(existing.contentKey, previousGuideUrl)
            }
        }
        return updated
    }

    suspend fun removeSource(id: String) {
        val source = store.current().firstOrNull { it.id == id }
        val previousGuideUrl = source?.let(::guideUrl)
        store.update { list -> list.filterNot { it.id == id } }
        store.removeFavoritesOf(id)
        // Its encrypted copies of the playlist and guide are no longer needed.
        if (source != null) {
            withContext(Dispatchers.IO) {
                listOf("m3u|${source.contentKey}", "xc-cats|${source.contentKey}", "xc-streams|${source.contentKey}")
                    .forEach(cache::delete)
                deleteGuideEntries(source.contentKey, previousGuideUrl)
            }
        }
        playlists.remove(id)
        connectionLimits.remove(id)
        liveExtensions.remove(id)
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        store.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    /** Saves a user-confirmed mapping only when the guide id is a candidate for this channel. */
    suspend fun setManualEpgMapping(sourceId: String, channelId: String, guideId: String?) = withContext(Dispatchers.IO) {
        val source = store.current().firstOrNull { it.id == sourceId }
            ?: throw IptvException("This source is no longer available")
        if (!channelId.startsWith("$sourceId::")) throw IptvException("This channel does not belong to the selected source")
        val cleanGuideId = guideId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanGuideId != null) {
            val channel = runInterruptible { fetchPlaylist(source, force = false) }.channels.firstOrNull { it.id == channelId }
                ?: throw IptvException("This channel is no longer available")
            val url = guideUrl(source) ?: throw IptvException("No TV guide is configured for this source")
            runInterruptible { guideFor(source, listOf(channel), force = false) }
            val guideKey = "epg|${source.contentKey}|$url"
            val parsed = guides[guideKey]?.result ?: throw IptvException("Could not check the selected guide channel")
            val isCandidate = cleanGuideId in parsed.displayNamesByChannelId
            if (!isCandidate) throw IptvException("Choose a guide channel suggested for this playlist channel")
        }
        store.update { sources ->
            if (sources.none { it.id == sourceId }) throw IptvException("This source is no longer available")
            sources.map { current ->
                if (current.id != sourceId) current
                else {
                    val mappings = current.manualEpgMappings.toMutableMap()
                    if (cleanGuideId == null) mappings.remove(channelId) else mappings[channelId] = cleanGuideId
                    current.copy(manualEpgMappings = mappings)
                }
            }
        }
    }

    suspend fun loadChannels(sources: List<IptvSource>, force: Boolean): ChannelLoadResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val results = sources.filter { it.enabled }.map { source ->
                    async { source to runInterruptible { runCatching { fetchPlaylist(source, force) } } }
                }.awaitAll()
                ChannelLoadResult(
                    channels = results.flatMap { (_, r) -> r.getOrNull()?.channels.orEmpty() },
                    errors = results.mapNotNull { (s, r) ->
                        r.exceptionOrNull()?.let { s.name to userMessage(it) }
                            ?: r.getOrNull()?.takeIf { it.truncated }?.let {
                                s.name to "Showing the first ${"%,d".format(channelCap)} channels (limit for this device)"
                            }
                    }.toMap()
                )
            }
        }

    /** Guide for [channels], keyed by channel id. */
    suspend fun loadGuide(
        sources: List<IptvSource>,
        channels: List<IptvChannel>,
        force: Boolean
    ): Map<String, List<EpgProgramme>> = loadGuideWithReport(sources, channels, force).programmesByChannel

    /** Guide programmes plus truthful per-source match counts for setup/health UI. */
    suspend fun loadGuideWithReport(
        sources: List<IptvSource>,
        channels: List<IptvChannel>,
        force: Boolean
    ): GuideLoadResult = withContext(Dispatchers.IO) {
        val bySource = channels.groupBy { it.sourceId }
        val loaded = coroutineScope {
            sources.filter { it.enabled && bySource.containsKey(it.id) }.map { source ->
                async {
                    val sourceChannels = bySource.getValue(source.id)
                    try {
                        runInterruptible { guideFor(source, sourceChannels, force) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        GuideSourceResult(
                            programmesByChannel = emptyMap(),
                            report = EpgSourceReport(
                                sourceId = source.id,
                                sourceName = source.name,
                                channelCount = sourceChannels.size,
                                unmatched = sourceChannels.size,
                                status = EpgSourceStatus.LOAD_ERROR
                            )
                        )
                    }
                }
            }.awaitAll()
        }
        GuideLoadResult(
            programmesByChannel = loaded.fold(HashMap()) { acc, result -> acc.apply { putAll(result.programmesByChannel) } },
            sources = loaded.map { it.report }
        )
    }

    private fun guideFor(
        source: IptvSource,
        channels: List<IptvChannel>,
        force: Boolean
    ): GuideSourceResult {
        val url = guideUrl(source) ?: return GuideSourceResult(
            programmesByChannel = emptyMap(),
            report = EpgSourceReport(
                sourceId = source.id,
                sourceName = source.name,
                channelCount = channels.size,
                unmatched = channels.size,
                status = EpgSourceStatus.NOT_CONFIGURED
            )
        )
        val key = "epg|${source.contentKey}|$url"
        val now = System.currentTimeMillis()
        val wantedIds = channels.mapNotNullTo(HashSet()) { it.epgId }.apply {
            addAll(source.manualEpgMappings.values)
        }
        val wantedNames = channels.flatMapTo(HashSet()) { channel ->
            listOfNotNull(channel.epgName?.takeIf { it.isNotBlank() }, channel.name.takeIf { it.isNotBlank() })
                .map(XmltvParser::normalizeChannelName)
                .filter(String::isNotEmpty)
        }
        val wanted = 31 * (31 * wantedIds.hashCode() + wantedNames.hashCode()) + source.manualEpgMappings.hashCode()
        val result = synchronized(lockFor(key)) {
            val changed = refresh(key, GUIDE_MAX_AGE_MS, IptvSafety.MAX_GUIDE_BYTES, force, url)
            val memo = guides[key]
            // The parse keeps a 26 h window around "now", so re-parse every couple of hours anyway.
            if (!changed && memo != null && memo.wanted == wanted && now - memo.parsedAtMs < GUIDE_REPARSE_MS) {
                memo.result
            } else {
                val parsed = cache.read(key) { XmltvParser(nowMs = now).parse(it, wantedIds, wantedNames) }
                    ?: throw IptvException("Could not read the downloaded guide")
                guides[key] = GuideMemo(wanted, now, parsed)
                parsed
            }
        }
        var exactIdMatches = 0
        var nameMatches = 0
        var manualMatches = 0
        var ambiguous = 0
        var unmatched = 0
        var mappingReviewCount = 0
        var candidateOptionsTruncated = false
        val mappingReviews = ArrayList<EpgMappingReview>()
        val programmesByChannel = channels.mapNotNull { channel ->
            val manualGuideId = source.manualEpgMappings[channel.id]
            val displayNames = listOfNotNull(
                channel.epgName?.takeIf { it.isNotBlank() },
                channel.name.takeIf { it.isNotBlank() }
            )
            val resolution = result.resolveGuide(
                manualGuideId ?: channel.epgId,
                displayNames,
                manualOverride = manualGuideId != null
            )
            when (resolution.kind) {
                EpgMatchKind.EXACT_ID -> exactIdMatches++
                EpgMatchKind.NAME -> nameMatches++
                EpgMatchKind.MANUAL -> manualMatches++
                EpgMatchKind.AMBIGUOUS -> ambiguous++
                EpgMatchKind.UNMATCHED -> unmatched++
            }
            if (resolution.kind == EpgMatchKind.AMBIGUOUS || resolution.kind == EpgMatchKind.UNMATCHED || manualGuideId != null) {
                mappingReviewCount++
                val suggestedIds = if (resolution.candidateIds.isNotEmpty()) {
                    resolution.candidateIds
                } else {
                    displayNames.flatMap { name ->
                        result.channelIdsByName[XmltvParser.normalizeChannelName(name)].orEmpty()
                    }.toSet()
                }
                val candidateIds = (suggestedIds + manualGuideId.orEmpty()).distinct()
                if (candidateIds.size > MAX_EPG_CANDIDATES_PER_CHANNEL) candidateOptionsTruncated = true
                val candidates = candidateIds.take(MAX_EPG_CANDIDATES_PER_CHANNEL).map { id ->
                    EpgGuideCandidate(id, result.displayNamesByChannelId[id].orEmpty().take(MAX_EPG_DISPLAY_NAMES))
                }
                if (mappingReviews.size < MAX_EPG_MAPPING_REVIEWS) {
                    mappingReviews += EpgMappingReview(
                        sourceId = source.id,
                        channelId = channel.id,
                        channelName = channel.name,
                        currentGuideId = manualGuideId,
                        candidates = candidates
                    )
                }
            }
            resolution.guideId?.let { id -> result.programmes[id]?.let { channel.id to it } }
        }.toMap()
        return GuideSourceResult(
            programmesByChannel = programmesByChannel,
            report = EpgSourceReport(
                sourceId = source.id,
                sourceName = source.name,
                channelCount = channels.size,
                exactIdMatches = exactIdMatches,
                nameMatches = nameMatches,
                manualMatches = manualMatches,
                ambiguous = ambiguous,
                unmatched = unmatched,
                mappingReviews = mappingReviews,
                mappingReviewsTruncated = mappingReviewCount > mappingReviews.size || candidateOptionsTruncated,
                guideCandidates = result.displayNamesByChannelId.map { (id, names) ->
                    EpgGuideCandidate(id, names.toList())
                },
                guideCatalogTruncated = result.channelCatalogTruncated
            )
        )
    }

    private fun guideUrl(source: IptvSource): String? = source.epgUrl.ifBlank {
        playlists[source.id]?.second?.headerEpgUrl?.takeIf(IptvSafety::isWebUrl)
            ?: if (source.type == IptvSourceType.XTREAM) XtreamClient.xmltvUrl(source) else null
    }

    /** Guide URLs can come from M3U headers, so delete every cached guide for the old source key. */
    private fun deleteGuideEntries(contentKey: String, knownUrl: String?) {
        val prefix = "epg|$contentKey|"
        knownUrl?.let { url ->
            val key = prefix + url
            cache.delete(key)
            guides.remove(key)
        }
        guides.keys.filter { it.startsWith(prefix) }.forEach { key ->
            cache.delete(key)
            guides.remove(key)
        }
    }

    private fun rememberAccount(source: IptvSource, account: XtreamClient.Account) {
        account.maxConnections?.takeIf { it > 0 }?.let { connectionLimits[source.id] = it }
        liveExtensions[source.id] = account.liveExtension
    }

    private fun fetchPlaylist(
        source: IptvSource,
        force: Boolean,
        allowStaleFallback: Boolean = true
    ): IptvPlaylist {
        val memo = playlists[source.id]
        val memoAge = System.currentTimeMillis() - (playlistLoadedAt[source.id] ?: 0L)
        if (!force && memo != null && memo.first == source.contentKey && memoAge < PLAYLIST_MAX_AGE_MS) return memo.second
        val playlist = when (source.type) {
            IptvSourceType.M3U -> cached(
                "m3u|${source.contentKey}", PLAYLIST_MAX_AGE_MS, IptvSafety.MAX_PLAYLIST_BYTES, force, source.url,
                allowStaleFallback
            ) { input -> M3uParser(source.id, channelCap).parse(input.reader()) }
            IptvSourceType.XTREAM -> {
                if (force || cache.ageMs("xc-streams|${source.contentKey}") == null) {
                    // Login check first so a wrong password reads as "Login rejected", not a parse error.
                    rememberAccount(source, XtreamClient.parseAccount(download(XtreamClient.apiUrl(source).toString())))
                } else if (!liveExtensions.containsKey(source.id)) {
                    // Cached channels: still learn the account's limits, but never fail on it.
                    runCatching { XtreamClient.parseAccount(download(XtreamClient.apiUrl(source).toString())) }
                        .getOrNull()?.let { rememberAccount(source, it) }
                }
                val categories = cached(
                    "xc-cats|${source.contentKey}", PLAYLIST_MAX_AGE_MS, IptvSafety.MAX_API_BYTES, force,
                    XtreamClient.apiUrl(source, "get_live_categories").toString(), allowStaleFallback
                ) { XtreamClient.parseCategories(it.bufferedReader().readText()) }
                val channels = cached(
                    "xc-streams|${source.contentKey}", PLAYLIST_MAX_AGE_MS, IptvSafety.MAX_API_BYTES, force,
                    XtreamClient.apiUrl(source, "get_live_streams").toString(), allowStaleFallback
                ) { XtreamClient.parseLiveStreams(it, source, categories, channelCap, liveExtensions[source.id] ?: "ts") }
                IptvPlaylist(channels, truncated = channels.size >= channelCap)
            }
        }
        if (playlist.channels.isEmpty()) throw IptvException("No channels found")
        playlists[source.id] = source.contentKey to playlist
        playlistLoadedAt[source.id] = System.currentTimeMillis()
        return playlist
    }

    /**
     * Returns [parse] of the cached copy when fresh; otherwise downloads into the encrypted cache
     * first. If the download fails, a stale copy is still used.
     */
    private fun <T> cached(
        key: String,
        maxAgeMs: Long,
        maxBytes: Long,
        force: Boolean,
        url: String,
        allowStaleFallback: Boolean = true,
        parse: (InputStream) -> T
    ): T {
        synchronized(lockFor(key)) {
            val age = cache.ageMs(key)
            if (!force && age != null && age < maxAgeMs) {
                runCatching { cache.read(key, parse) }.getOrNull()?.let { return it }
            }
            try {
                downloader.fetch(key, url, maxBytes)
            } catch (e: IOException) {
                if (allowStaleFallback && age != null) {
                    runCatching { cache.read(key, parse) }.getOrNull()?.let { return it }
                }
                throw IptvException(friendly(e), e)
            }
            return cache.read(key, parse) ?: throw IptvException("Could not read the downloaded data")
        }
    }

    /**
     * Makes sure the cache holds a usable copy of [url]: downloads when stale or [force]d.
     * @return true when the cached content changed. A failed download with a copy on hand returns false.
     */
    private fun refresh(key: String, maxAgeMs: Long, maxBytes: Long, force: Boolean, url: String): Boolean {
        val age = cache.ageMs(key)
        if (!force && age != null && age < maxAgeMs) return false
        return try {
            downloader.fetch(key, url, maxBytes)
        } catch (e: IOException) {
            if (age != null) false else throw IptvException(friendly(e), e)
        }
    }

    /**
     * Smallest Xtream connection limit among [sourceIds], if any panel reported one. Multiview
     * never opens more streams than that (most accounts allow 1-2).
     */
    fun connectionLimit(sourceIds: Collection<String>): Int? = sourceIds.mapNotNull(connectionLimits::get).minOrNull()

    private fun download(url: String): String =
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                val bytes = java.io.ByteArrayOutputStream()
                IptvSafety.copyLimited(body.byteStream(), bytes, IptvSafety.MAX_LOGIN_BYTES)
                bytes.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            throw IptvException(friendly(e), e)
        }

    private fun friendly(e: IOException): String {
        val message = e.message.orEmpty()
        return when {
            e is IptvSafety.TooLargeException -> "The server sent more data than NuvioEdge allows"
            e is IptvSafety.InsecureRedirectException ->
                "The server tried to switch to an unencrypted connection, so it was blocked"
            message.startsWith("HTTP 401") || message.startsWith("HTTP 403") -> "Access denied by the server ($message)"
            message.startsWith("HTTP 404") -> "Address not found (HTTP 404)"
            message.startsWith("HTTP") -> "Server error ($message)"
            e is java.net.UnknownHostException -> "Server not found"
            e is java.net.SocketTimeoutException -> "The server took too long to respond"
            e is javax.net.ssl.SSLException -> "Secure connection failed"
            else -> "Could not connect to the server"
        }
    }

    companion object {
        /** Only our own messages reach the screen; raw exception text can contain URLs with logins. */
        fun userMessage(error: Throwable): String = when (error) {
            is IptvException -> error.message ?: GENERIC_ERROR
            is OutOfMemoryError -> "This source is too large for this device"
            else -> GENERIC_ERROR
        }

        private const val GENERIC_ERROR = "Could not load this source"
        private const val GUIDE_REPARSE_MS = 2 * 60 * 60 * 1000L
        private const val LOW_HEAP_BYTES = 512L * 1024 * 1024
        private const val LOW_MEMORY_CHANNEL_CAP = 50_000
        private const val CHANNEL_CAP = 250_000
        private const val MAX_EPG_CANDIDATES_PER_CHANNEL = 12
        private const val MAX_EPG_DISPLAY_NAMES = 4
        private const val MAX_EPG_MAPPING_REVIEWS = 100
        private const val PLAYLIST_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        // Re-checking is cheap: unchanged files come back as HTTP 304 with no body.
        private const val GUIDE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        fun nowAndNext(programmes: List<EpgProgramme>?, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> {
            if (programmes.isNullOrEmpty()) return null to null
            val index = programmes.indexOfFirst { it.endMs > nowMs }
            if (index < 0) return null to null
            val current = programmes[index].takeIf { it.startMs <= nowMs }
            val next = if (current != null) programmes.getOrNull(index + 1) else programmes[index]
            return current to next
        }
    }
}
