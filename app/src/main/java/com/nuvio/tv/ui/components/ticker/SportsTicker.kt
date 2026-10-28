@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.nuvio.tv.ui.components.ticker

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.SportsTickerRepository
import com.nuvio.tv.data.sports.SportsFollowPreferences
import com.nuvio.tv.data.sports.TickerGame
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.nuvio.tv.data.sports.TeamLine
import com.nuvio.tv.data.sports.TickerKind
import com.nuvio.tv.data.sports.withoutScores
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsTickerViewModel @Inject constructor(
    private val repository: SportsTickerRepository
) : ViewModel() {

    private val _enabledLoaded = MutableStateFlow(false)
    val enabledLoaded: StateFlow<Boolean> = _enabledLoaded.asStateFlow()

    val enabled: StateFlow<Boolean> = repository.enabled
        .onEach { _enabledLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val follows: StateFlow<SportsFollowPreferences> = repository.follows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsFollowPreferences())

    val hideScores: StateFlow<Boolean> = repository.hideScores
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val channelOverrides: StateFlow<Map<String, String>> = repository.channelOverrides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val teamChannelOverrides: StateFlow<Map<String, String>> = repository.teamChannelOverrides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun setChannelOverride(game: TickerGame, channelId: String?) {
        repository.setChannelOverride(game, channelId)
    }

    suspend fun setTeamChannelOverride(league: String, team: TeamLine, channelId: String?) {
        repository.setTeamChannelOverride(league, team, channelId)
    }

    fun toggleHideScores() {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.toggleHideScores() }
    }

    fun toggleFollowedLeague(league: String) {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.toggleFollowedLeague(league) }
    }

    fun toggleFollowedTeam(league: String, team: TeamLine) {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.toggleFollowedTeam(league, team) }
    }

    /** Polls every minute only while the ticker is enabled and on screen (WhileSubscribed). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val games: StateFlow<List<TickerGame>> = enabled
        .flatMapLatest { on ->
            if (!on) flowOf(emptyList())
            else channelFlow {
                while (true) {
                    val games = try {
                        repository.fetchGames(onFirstGames = { send(it) })
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // offline or ESPN down: keep showing the last scores
                    }
                    if (games != null) send(games)
                    delay(REFRESH_MS)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's games for the Scores screen, whether or not the ticker is showing. */
    suspend fun gamesNow(): List<TickerGame> = repository.fetchGames(limitPerLeague = false)

    suspend fun detail(game: TickerGame): com.nuvio.tv.data.sports.GameDetail? = repository.fetchDetail(game)

    fun toggle() {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.setEnabled(!enabled.value) }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}

/** Opaque themed surfaces preserve readability over video. */
private object TickerColors {
    val bar: Brush
        @Composable @ReadOnlyComposable
        get() = Brush.horizontalGradient(listOf(NuvioTheme.colors.Background, NuvioTheme.colors.BackgroundElevated, NuvioTheme.colors.Background))
    val leagueTag: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.Secondary
    val onLeagueTag: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondary
    val oddsPanel: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundCard
    val live: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.Error
    val text: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.TextPrimary
    val muted: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.TextSecondary
    val odds: Color
        @Composable @ReadOnlyComposable
        get() = NuvioTheme.colors.Rating
}

/**
 * Score and odds strip along the bottom of every screen; it never takes remote focus.
 */
@Composable
fun SportsTickerOverlay(viewModel: SportsTickerViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val games by viewModel.games.collectAsStateWithLifecycle()
    val hideScores by viewModel.hideScores.collectAsStateWithLifecycle()
    if (!enabled || games.isEmpty()) return

    Box(modifier = Modifier.fillMaxSize()) {
        SportsTickerBar(
            games = if (hideScores) games.map { it.withoutScores() } else games,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 48.dp, end = 48.dp, bottom = 27.dp) // TV safe area
        )
    }
}

@Composable
fun SportsTickerBar(games: List<TickerGame>, modifier: Modifier = Modifier) {
    if (games.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(TickerColors.bar)
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 0,
                repeatDelayMillis = 0,
                spacing = MarqueeSpacing(24.dp),
                velocity = 36.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        games.forEach { game ->
            Box(Modifier.width(760.dp).fillMaxHeight()) {
                GameCard(game)
            }
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun GameCard(game: TickerGame) {
    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(72.dp)
                .background(TickerColors.leagueTag),
            contentAlignment = Alignment.Center
        ) {
            Text(
                game.league,
                color = TickerColors.onLeagueTag,
                fontWeight = FontWeight.Black,
                fontSize = if (game.league.length > 4) 13.sp else 15.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            when (game.kind) {
                TickerKind.LEADERBOARD -> Leaderboard(game)
                TickerKind.TEAMS, TickerKind.HEAD_TO_HEAD -> Matchup(game)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (game.state == GameState.LIVE) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TickerColors.live))
                    Text(
                        stringResource(R.string.ticker_live),
                        color = TickerColors.live,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 5.dp, end = 8.dp)
                    )
                }
                Text(
                    // An event with no running order yet shows its name on the top line instead.
                    text = listOfNotNull(game.event.takeUnless { game.kind == TickerKind.LEADERBOARD && game.leaders.isEmpty() }, game.status.ifBlank { null })
                        .joinToString(" · "),
                    color = TickerColors.muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val odds = game.odds
        if (odds != null) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(TickerColors.oddsPanel)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    odds.line?.let { OddsItem(stringResource(R.string.ticker_line), it) }
                    odds.total?.let { OddsItem(stringResource(R.string.ticker_total), it) }
                    if (odds.moneylineAway != null && odds.moneylineHome != null) {
                        OddsItem(
                            stringResource(R.string.ticker_moneyline),
                            "${game.away.abbreviation} ${odds.moneylineAway} · ${game.home.abbreviation} ${odds.moneylineHome}"
                        )
                    }
                }
                odds.provider?.let {
                    Text(stringResource(R.string.ticker_odds_by, it), color = TickerColors.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** "AWAY 21  @  HOME 17" for team games, "J. Sinner 6 6  vs  C. Alcaraz 4 3" for tennis and fights. */
@Composable
private fun Matchup(game: TickerGame) {
    val showScores = game.state != GameState.PRE
    val final = game.state == GameState.FINAL
    val separator = if (game.kind == TickerKind.TEAMS) "  @  " else "  vs  "
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamScore(game.away, showScores, highlight = final && game.away.winner)
        Text(separator, color = TickerColors.muted, fontSize = 14.sp)
        TeamScore(game.home, showScores, highlight = final && game.home.winner)
    }
}

/** "1 S. Scheffler -16   T2 R. McIlroy -14   T2 X. Schauffele -14" for golf and racing. */
@Composable
private fun Leaderboard(game: TickerGame) {
    val line = buildAnnotatedString {
        game.leaders.forEachIndexed { i, leader ->
            if (i > 0) append("    ")
            withStyle(SpanStyle(color = TickerColors.muted, fontWeight = FontWeight.Bold)) { append(leader.position + " ") }
            withStyle(SpanStyle(color = TickerColors.text, fontWeight = FontWeight.Bold)) { append(leader.name) }
            leader.score?.let { withStyle(SpanStyle(color = TickerColors.odds, fontWeight = FontWeight.Black)) { append(" $it") } }
        }
    }
    if (line.isEmpty()) {
        Text(game.event.orEmpty(), color = TickerColors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Text(line, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TeamScore(side: TeamLine, showScore: Boolean, highlight: Boolean) {
    Text(
        side.abbreviation,
        color = if (highlight) TickerColors.odds else TickerColors.text,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        maxLines = 1
    )
    val score = side.score.takeIf { showScore }
    if (score != null) {
        Text(" $score", color = TickerColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1)
    }
}

@Composable
private fun OddsItem(label: String, value: String) {
    Column {
        Text(label, color = TickerColors.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = TickerColors.odds, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private const val FLIP_MS = 6_000L
