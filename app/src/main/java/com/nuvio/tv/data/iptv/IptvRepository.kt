package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.isRedirect && IptvSafety.isInsecureRedirect(chain.request().isHttps, response.header("Location"))) {
                response.close()
                throw IptvSafety.InsecureRedirectException()
            }
            response
        }
        .build()

    /** For video players: no disk cache, logging or Sentry breadcrumbs (stream URLs can hold logins). */
    val streamClient: OkHttpClient = okHttpClient.newBuilder()
        .cache(null)
        .apply {
            interceptors().removeAll { it is HttpLoggingInterceptor || it is SentryNetworkBreadcrumbInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor || it is SentryNetworkBreadcrumbInterceptor }
        }
        .build()

    private val cache = IptvFileCache(File(context.cacheDir, "nuvio_live"), vault::cacheKey)
    private val downloader = ConditionalDownloader(http, cache)
    private val playlists = ConcurrentHashMap<String, Pair<String, IptvPlaylist>>()
    private val playlistLoadedAt = ConcurrentHashMap<String, Long>()

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
        // Validate before saving so a typo never gets stored.
        withContext(Dispatchers.IO) { fetchPlaylist(source, force = true) }
        store.save(store.current() + source)
        return source
    }

    suspend fun removeSource(id: String) {
        store.save(store.current().filterNot { it.id == id })
        playlists.remove(id)
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        store.save(store.current().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun loadChannels(sources: List<IptvSource>, force: Boolean): ChannelLoadResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val results = sources.filter { it.enabled }.map { source ->
                    async { source to runCatching { fetchPlaylist(source, force) } }
                }.awaitAll()
                ChannelLoadResult(
                    channels = results.flatMap { (_, r) -> r.getOrNull()?.channels.orEmpty() },
                    errors = results.mapNotNull { (s, r) ->
                        r.exceptionOrNull()?.let { s.name to userMessage(it) }
                    }.toMap()
                )
            }
        }

    /** Guide for [channels], keyed by channel id. */
    suspend fun loadGuide(
        sources: List<IptvSource>,
        channels: List<IptvChannel>,
        force: Boolean
    ): Map<String, List<EpgProgramme>> = withContext(Dispatchers.IO) {
        val bySource = channels.groupBy { it.sourceId }
        coroutineScope {
            sources.filter { it.enabled && bySource.containsKey(it.id) }.map { source ->
                async { runCatching { guideFor(source, bySource.getValue(source.id), force) }.getOrDefault(emptyMap()) }
            }.awaitAll()
        }.fold(HashMap()) { acc, map -> acc.apply { putAll(map) } }
    }

    private fun guideFor(
        source: IptvSource,
        channels: List<IptvChannel>,
        force: Boolean
    ): Map<String, List<EpgProgramme>> {
        val url = source.epgUrl.ifBlank {
            playlists[source.id]?.second?.headerEpgUrl?.takeIf(IptvSafety::isWebUrl)
                ?: if (source.type == IptvSourceType.XTREAM) XtreamClient.xmltvUrl(source) else null
        } ?: return emptyMap()
        val key = "epg|${source.contentKey}|$url"
        val parser = XmltvParser(nowMs = System.currentTimeMillis())
        val wantedIds = channels.mapNotNullTo(HashSet()) { it.epgId }
        val wantedNames = channels.mapTo(HashSet()) { XmltvParser.normalizeChannelName(it.epgName ?: it.name) }
        val result = cached(key, GUIDE_MAX_AGE_MS, IptvSafety.MAX_GUIDE_BYTES, force, url) {
            parser.parse(it, wantedIds, wantedNames)
        }
        return channels.mapNotNull { channel ->
            val guideId = channel.epgId?.takeIf { result.programmes.containsKey(it) }
                ?: result.channelIdsByName[XmltvParser.normalizeChannelName(channel.epgName ?: channel.name)]
            guideId?.let { id -> result.programmes[id]?.let { channel.id to it } }
        }.toMap()
    }

    private fun fetchPlaylist(source: IptvSource, force: Boolean): IptvPlaylist {
        val memo = playlists[source.id]
        val memoAge = System.currentTimeMillis() - (playlistLoadedAt[source.id] ?: 0L)
        if (!force && memo != null && memo.first == source.contentKey && memoAge < PLAYLIST_MAX_AGE_MS) return memo.second
        val playlist = when (source.type) {
            IptvSourceType.M3U -> cached(
                "m3u|${source.contentKey}", PLAYLIST_MAX_AGE_MS, IptvSafety.MAX_PLAYLIST_BYTES, force, source.url
            ) { input -> M3uParser(source.id).parse(input.reader()) }
            IptvSourceType.XTREAM -> {
                if (force || cache.ageMs("xc-streams|${source.contentKey}") == null) {
                    // Login check first so a wrong password reads as "Login rejected", not a parse error.
                    XtreamClient.parseAccount(download(XtreamClient.apiUrl(source).toString()))
                }
                val categories = cached(
                    "xc-cats|${source.contentKey}", PLAYLIST_MAX_AGE_MS, IptvSafety.MAX_API_BYTES, force,
                    XtreamClient.apiUrl(source, "get_live_categories").toString()
                ) { XtreamClient.parseCategories(it.bufferedReader().readText()) }
                val channels = cached(
                    "xc-streams|${source.contentKey}", PLAYLIST_MAX_AGE_MS, IptvSafety.MAX_API_BYTES, force,
                    XtreamClient.apiUrl(source, "get_live_streams").toString()
                ) { XtreamClient.parseLiveStreams(it.bufferedReader().readText(), source, categories) }
                IptvPlaylist(channels)
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
        parse: (InputStream) -> T
    ): T {
        val age = cache.ageMs(key)
        if (!force && age != null && age < maxAgeMs) {
            runCatching { cache.read(key, parse) }.getOrNull()?.let { return it }
        }
        try {
            downloader.fetch(key, url, maxBytes)
        } catch (e: IOException) {
            if (age != null) runCatching { cache.read(key, parse) }.getOrNull()?.let { return it }
            throw IptvException(friendly(e), e)
        }
        return cache.read(key, parse) ?: throw IptvException("Could not read the downloaded data")
    }

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
