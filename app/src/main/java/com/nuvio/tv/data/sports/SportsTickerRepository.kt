package com.nuvio.tv.data.sports

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.IptvSafety
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Scores and odds for the ticker, from ESPN's free public scoreboard feed (unofficial, no key). */
@Singleton
class SportsTickerRepository @Inject constructor(
    okHttpClient: OkHttpClient,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val http = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val enabledKey = booleanPreferencesKey("ticker_enabled")

    /** On by default; the only ticker setting. */
    val enabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.catch { e -> if (e is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }.map { it[enabledKey] ?: true }
    }

    suspend fun setEnabled(value: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[enabledKey] = value }
    }

    private class LeagueEntry(val games: List<TickerGame>, val dueAtMs: Long)

    /** Last result per league; a league is only downloaded again when it is due (see [nextPollMs]). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, LeagueEntry>()

    /**
     * What the ticker shows now, across every sport. Called every minute while the ticker is visible,
     * but each league is only re-downloaded when due: every minute while it has a live game, every
     * 5 minutes when one starts within 2 hours, otherwise every 30 minutes. A league that fails keeps
     * its last scores and retries in 5 minutes, so one outage can't blank the ticker.
     */
    suspend fun fetchGames(
        leagues: List<SportsLeague> = ALL_LEAGUES,
        nowMs: Long = System.currentTimeMillis()
    ): List<TickerGame> = withContext(Dispatchers.IO) {
        val permits = Semaphore(4)
        coroutineScope {
            leagues.filter { (cache[it.path]?.dueAtMs ?: 0L) <= nowMs }.map { league ->
                async {
                    permits.withPermit {
                        cache[league.path] = try {
                            val games = fetchLeague(league)
                            LeagueEntry(games, nowMs + nextPollMs(games, nowMs))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LeagueEntry(cache[league.path]?.games.orEmpty(), nowMs + RETRY_MS)
                        }
                    }
                }
            }.awaitAll()
        }
        select(leagues.flatMap { cache[it.path]?.games.orEmpty() }, nowMs)
    }

    private fun fetchLeague(league: SportsLeague): List<TickerGame> {
        val url = "$BASE/${league.path}/scoreboard"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = ByteArrayOutputStream()
            IptvSafety.copyLimited(response.body.byteStream(), bytes, MAX_BYTES)
            return EspnScoreboardParser.parse(bytes.toString(Charsets.UTF_8.name()), league.label, league.format)
        }
    }

    companion object {
        private const val FEATURE = "sports_ticker"
        private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val RETRY_MS = 5 * MINUTE
        /** Most items one league may put in the rotation, so a 100-match day can't crowd out other sports. */
        const val PER_LEAGUE = 4

        fun startMs(game: TickerGame): Long? =
            game.startIso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

        fun nextPollMs(games: List<TickerGame>, nowMs: Long): Long = when {
            games.any { it.state == GameState.LIVE } -> MINUTE
            games.any { g -> g.state == GameState.PRE && startMs(g)?.let { it - nowMs <= 2 * HOUR } == true } -> 5 * MINUTE
            else -> 30 * MINUTE
        }

        /** Live always; upcoming within 36 h; finished within 18 h (5 days for golf/racing events, dated by their start). */
        fun isRelevant(game: TickerGame, nowMs: Long): Boolean {
            val start = startMs(game) ?: return true
            return when (game.state) {
                GameState.LIVE -> true
                GameState.PRE -> start - nowMs <= 36 * HOUR
                GameState.FINAL -> nowMs - start <= if (game.kind == TickerKind.LEADERBOARD) 5 * 24 * HOUR else 18 * HOUR
            }
        }

        fun select(games: List<TickerGame>, nowMs: Long): List<TickerGame> =
            order(
                games.filter { isRelevant(it, nowMs) }
                    .groupBy { it.league }
                    .flatMap { (_, inLeague) -> order(inLeague).take(PER_LEAGUE) }
            )

        /** Live first, then upcoming (soonest first), then finals (latest first). Ties keep ESPN's order. */
        fun order(games: List<TickerGame>): List<TickerGame> =
            games.sortedWith(
                compareBy<TickerGame> { game ->
                    when (game.state) {
                        GameState.LIVE -> 0
                        GameState.PRE -> 1
                        GameState.FINAL -> 2
                    }
                }.thenBy { game ->
                    val start = startMs(game) ?: 0L
                    when (game.state) {
                        GameState.LIVE -> 0L
                        GameState.PRE -> start
                        GameState.FINAL -> -start
                    }
                }
            )
    }
}
