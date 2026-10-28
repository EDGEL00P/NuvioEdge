package com.nuvio.tv.data.sports

import com.nuvio.tv.data.iptv.orEmptyOnReadError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SportsFollowPreferences(
    val leagues: Set<String> = emptySet(),
    val teams: Set<String> = emptySet()
) {
    fun followsLeague(league: String): Boolean = leagueKey(league) in leagues

    fun followsTeam(league: String, team: TeamLine): Boolean = teamKey(league, team)?.let(teams::contains) == true

    fun includes(game: TickerGame): Boolean = followsLeague(game.league) ||
        (game.kind == TickerKind.TEAMS && (followsTeam(game.league, game.away) || followsTeam(game.league, game.home)))

    companion object {
        fun leagueKey(label: String): String = ALL_LEAGUES.firstOrNull { it.label == label }?.path
            ?: label.trim().lowercase(java.util.Locale.ROOT)

        fun teamKey(league: String, team: TeamLine): String? {
            val identity = team.providerId?.trim()?.takeIf(String::isNotEmpty)?.let { "id:$it" }
                ?: team.abbreviation.trim().takeIf { it.isNotEmpty() && it != "?" }
                    ?.lowercase(java.util.Locale.ROOT)?.let { "name:$it" }
                ?: return null
            return "${leagueKey(league)}|$identity"
        }
    }
}

/** Scores and odds for the ticker, from ESPN's free public scoreboard feed (unofficial, no key). */
@OptIn(ExperimentalCoroutinesApi::class)
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
    private val followedLeaguesKey = stringSetPreferencesKey("followed_leagues_v1")
    private val followedTeamsKey = stringSetPreferencesKey("followed_teams_v1")
    private val hideScoresKey = booleanPreferencesKey("hide_scores_v1")
    private val channelOverridesKey = stringPreferencesKey("event_channel_overrides_v1")
    private val teamChannelOverridesKey = stringPreferencesKey("team_channel_overrides_v1")
    private val channelOverridesSerializer = MapSerializer(String.serializer(), String.serializer())
    private val preferencesJson = Json { ignoreUnknownKeys = true }

    /** On by default. */
    val enabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.orEmptyOnReadError().map { it[enabledKey] ?: true }
    }

    suspend fun setEnabled(value: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[enabledKey] = value }
    }

    val hideScores: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.orEmptyOnReadError().map { it[hideScoresKey] ?: false }
    }

    suspend fun toggleHideScores() {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[hideScoresKey] = !(prefs[hideScoresKey] ?: false)
        }
    }

    val channelOverrides: Flow<Map<String, String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.orEmptyOnReadError().map { prefs ->
            runCatching {
                preferencesJson.decodeFromString(channelOverridesSerializer, prefs[channelOverridesKey] ?: "{}")
            }.getOrDefault(emptyMap())
        }
    }

    suspend fun setChannelOverride(game: TickerGame, channelId: String?) {
        val eventKey = eventChannelKey(game)
        require(game.id.isNotBlank() && eventKey.length <= 512)
        require(channelId == null || (channelId.isNotBlank() && channelId.length <= 512))
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val saved = preferencesJson.decodeFromString(channelOverridesSerializer, prefs[channelOverridesKey] ?: "{}")
            val updated = saved.toMutableMap()
            updated.remove(eventKey)
            if (channelId != null) updated[eventKey] = channelId
            while (updated.size > 1_000) updated.remove(updated.keys.first())
            prefs[channelOverridesKey] = preferencesJson.encodeToString(channelOverridesSerializer, updated)
        }
    }

    val teamChannelOverrides: Flow<Map<String, String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.orEmptyOnReadError().map { prefs ->
            runCatching {
                preferencesJson.decodeFromString(channelOverridesSerializer, prefs[teamChannelOverridesKey] ?: "{}")
            }.getOrDefault(emptyMap())
        }
    }

    suspend fun setTeamChannelOverride(league: String, team: TeamLine, channelId: String?) {
        val teamKey = teamChannelKey(league, team) ?: return
        require(teamKey.length <= 512)
        require(channelId == null || (channelId.isNotBlank() && channelId.length <= 512))
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val saved = preferencesJson.decodeFromString(channelOverridesSerializer, prefs[teamChannelOverridesKey] ?: "{}")
            val updated = saved.toMutableMap()
            updated.remove(teamKey)
            if (channelId != null) updated[teamKey] = channelId
            while (updated.size > 1_000) updated.remove(updated.keys.first())
            prefs[teamChannelOverridesKey] = preferencesJson.encodeToString(channelOverridesSerializer, updated)
        }
    }

    val follows: Flow<SportsFollowPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.orEmptyOnReadError().map { prefs ->
            SportsFollowPreferences(prefs[followedLeaguesKey].orEmpty(), prefs[followedTeamsKey].orEmpty())
        }
    }

    suspend fun toggleFollowedLeague(league: String) {
        val key = SportsFollowPreferences.leagueKey(league)
        if (key.isBlank()) return
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val current = prefs[followedLeaguesKey].orEmpty()
            prefs[followedLeaguesKey] = if (key in current) current - key else current + key
        }
    }

    suspend fun toggleFollowedTeam(league: String, team: TeamLine) {
        val key = SportsFollowPreferences.teamKey(league, team) ?: return
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val current = prefs[followedTeamsKey].orEmpty()
            prefs[followedTeamsKey] = if (key in current) current - key else current + key
        }
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
        nowMs: Long = System.currentTimeMillis(),
        onFirstGames: suspend (List<TickerGame>) -> Unit = {},
        limitPerLeague: Boolean = true
    ): List<TickerGame> = withContext(Dispatchers.IO) {
        val permits = Semaphore(4)
        fun snapshot(): List<TickerGame> {
            val games = leagues.flatMap { cache[it.path]?.games.orEmpty() }
            return if (limitPerLeague) select(games, nowMs) else order(games.filter { isRelevant(it, nowMs) })
        }
        val cached = snapshot()
        val published = java.util.concurrent.atomic.AtomicBoolean(cached.isNotEmpty())
        if (cached.isNotEmpty()) onFirstGames(cached)
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
                        if (!published.get()) {
                            val available = snapshot()
                            if (available.isNotEmpty() && published.compareAndSet(false, true)) {
                                onFirstGames(available)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        snapshot()
    }

    private fun fetchLeague(league: SportsLeague): List<TickerGame> =
        EspnScoreboardParser.parse(fetchText("$BASE/${league.path}/scoreboard"), league.label, league.format)

    /** Box score, standings, injuries and headlines for a team-sport game; null when unavailable. */
    suspend fun fetchDetail(game: TickerGame): GameDetail? = withContext(Dispatchers.IO) {
        val league = ALL_LEAGUES.firstOrNull { it.label == game.league && it.format == FeedFormat.TEAMS }
            ?: return@withContext null
        try {
            EspnSummaryParser.parse(fetchText("$BASE/${league.path}/summary?event=${game.id}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchText(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = IptvSafety.requireResponseBody(response.body)
            val bytes = ByteArrayOutputStream()
            IptvSafety.copyLimited(body.byteStream(), bytes, MAX_BYTES)
            bytes.toString(Charsets.UTF_8.name())
        }

    companion object {
        fun eventChannelKey(game: TickerGame): String = "${SportsFollowPreferences.leagueKey(game.league)}|${game.id}"

        fun teamChannelKey(league: String, team: TeamLine): String? =
            SportsFollowPreferences.teamKey(league, team)?.let { "team|$it" }

        fun resolveChannelOverride(
            game: TickerGame,
            eventOverrides: Map<String, String>,
            teamOverrides: Map<String, String>
        ): Pair<String?, Boolean> {
            eventOverrides[eventChannelKey(game)]?.let { return it to false }
            val teamAssignments = listOf(game.away, game.home)
                .mapNotNull { team -> teamChannelKey(game.league, team)?.let(teamOverrides::get) }
                .distinct()
            return when (teamAssignments.size) {
                0 -> null to false
                1 -> teamAssignments.single() to false
                else -> null to true
            }
        }

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
