package com.nuvio.tv.data.sports

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.IptvSafety
import com.nuvio.tv.data.local.ProfileDataStoreFactory
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
        factory.get(pid, FEATURE).data.map { it[enabledKey] ?: true }
    }

    suspend fun setEnabled(value: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[enabledKey] = value }
    }

    /** Today's games across [leagues]; a league that fails is skipped so one outage can't blank the ticker. */
    suspend fun fetchGames(leagues: List<SportsLeague> = MAJOR_LEAGUES): List<TickerGame> =
        withContext(Dispatchers.IO) {
            val permits = Semaphore(4)
            coroutineScope {
                leagues.map { league ->
                    async { permits.withPermit { runCatching { fetchLeague(league) }.getOrDefault(emptyList()) } }
                }.awaitAll()
            }.flatten().let(::order)
        }

    private fun fetchLeague(league: SportsLeague): List<TickerGame> {
        val url = "$BASE/${league.path}/scoreboard"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = ByteArrayOutputStream()
            IptvSafety.copyLimited(response.body.byteStream(), bytes, MAX_BYTES)
            return EspnScoreboardParser.parse(bytes.toString(Charsets.UTF_8.name()), league.label)
        }
    }

    companion object {
        private const val FEATURE = "sports_ticker"
        private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
        private const val MAX_BYTES = 4L * 1024 * 1024

        /** Live games first, then upcoming, then finals; order within each group as ESPN lists them. */
        fun order(games: List<TickerGame>): List<TickerGame> =
            games.sortedBy { game ->
                when (game.state) {
                    GameState.LIVE -> 0
                    GameState.PRE -> 1
                    GameState.FINAL -> 2
                }
            }
    }
}
