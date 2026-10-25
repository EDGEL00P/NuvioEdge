@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.nuvio.tv.ui.components.ticker

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.nuvio.tv.data.sports.TickerGame
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.nuvio.tv.data.sports.TeamLine
import com.nuvio.tv.data.sports.TickerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsTickerViewModel @Inject constructor(
    private val repository: SportsTickerRepository
) : ViewModel() {

    val enabled: StateFlow<Boolean> = repository.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Polls every minute only while the ticker is enabled and on screen (WhileSubscribed). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val games: StateFlow<List<TickerGame>> = enabled
        .flatMapLatest { on ->
            if (!on) flowOf(emptyList())
            else flow {
                while (true) {
                    val games = try {
                        repository.fetchGames()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // offline or ESPN down: keep showing the last scores
                    }
                    if (games != null) emit(games)
                    delay(REFRESH_MS)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's games for the Scores screen, whether or not the ticker is showing. */
    suspend fun gamesNow(): List<TickerGame> = repository.fetchGames()

    suspend fun detail(game: TickerGame): com.nuvio.tv.data.sports.GameDetail? = repository.fetchDetail(game)

    fun toggle() {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.setEnabled(!enabled.value) }
    }

    val overLiveChannels: StateFlow<Boolean> = repository.overLiveChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setEnabled(value: Boolean) {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.setEnabled(value) }
    }

    fun setOverLiveChannels(value: Boolean) {
        viewModelScope.launch(com.nuvio.tv.ui.screens.live.liveErrors) { repository.setOverLiveChannels(value) }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}

/** Sports palette for the ticker; it sits over video, so it doesn't follow the app theme. */
private object TickerColors {
    val bar = Brush.verticalGradient(listOf(Color(0xB3000000), Color(0xD9000000)))
    val edge = Color(0xFFFACC15)
    val leagueTag = Color(0xFF1D4ED8)
    val live = Color(0xFFE11D48)
    val text = Color(0xFFF8FAFC)
    val muted = Color(0xFF94A3B8)
    val odds = Color(0xFFFACC15)
}

/** Height of the ticker strip; screens that show it keep this much room free at the bottom. */
val SportsTickerHeight = 34.dp

/**
 * A slim scrolling score and odds strip, like a sportsbook ticker: every game in one line moving
 * right to left. It never takes focus, so it can't interfere with the remote. Shown by the Live
 * TV and Sports screens, and over live channels while the player controls are hidden.
 */
@Composable
fun SportsTickerStrip(modifier: Modifier = Modifier, viewModel: SportsTickerViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val games by viewModel.games.collectAsStateWithLifecycle()
    if (!enabled || games.isEmpty()) return
    SportsTickerMarquee(games, modifier)
}

@Composable
fun SportsTickerMarquee(games: List<TickerGame>, modifier: Modifier = Modifier) {
    val live = stringResource(R.string.ticker_live)
    val lineLabel = stringResource(R.string.ticker_line)
    val totalLabel = stringResource(R.string.ticker_total)
    val moneylineLabel = stringResource(R.string.ticker_moneyline)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SportsTickerHeight)
            .background(TickerColors.bar),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter).background(TickerColors.edge.copy(alpha = 0.6f)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    repeatDelayMillis = 0,
                    initialDelayMillis = 0,
                    spacing = MarqueeSpacing(48.dp),
                    velocity = 56.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            games.forEach { game ->
                val line = remember(game, live, lineLabel, totalLabel, moneylineLabel) {
                    tickerLine(game, live, lineLabel, totalLabel, moneylineLabel)
                }
                LeagueTag(game.league)
                Text(
                    text = line,
                    fontSize = 15.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Text("  \u25C6  ", color = TickerColors.edge, fontSize = 11.sp, maxLines = 1)
            }
        }
        // Soft fades at both ends so items appear and leave smoothly, like a broadcast ticker.
        val fade = Color(0xD9000000)
        Box(
            Modifier.fillMaxHeight().width(36.dp).align(Alignment.CenterStart)
                .background(Brush.horizontalGradient(listOf(fade, Color.Transparent)))
        )
        Box(
            Modifier.fillMaxHeight().width(36.dp).align(Alignment.CenterEnd)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, fade)))
        )
    }
}

@Composable
private fun LeagueTag(league: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TickerColors.leagueTag)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(league, color = TickerColors.text, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * One game as a single styled line: "PIT 21 @ CLE 17  LIVE Q3 8:42  LINE PIT -3  O/U 45.5", or
 * "1 S. Scheffler -16  T2 R. McIlroy -14" for golf and racing.
 */
internal fun tickerLine(
    game: TickerGame,
    liveLabel: String,
    lineLabel: String,
    totalLabel: String,
    moneylineLabel: String
): AnnotatedString = buildAnnotatedString {
    val text = SpanStyle(color = TickerColors.text, fontWeight = FontWeight.Bold)
    val muted = SpanStyle(color = TickerColors.muted)
    val gold = SpanStyle(color = TickerColors.odds, fontWeight = FontWeight.Black)
    when (game.kind) {
        TickerKind.LEADERBOARD -> {
            if (game.leaders.isEmpty()) {
                withStyle(text) { append(game.event.orEmpty()) }
            } else {
                game.event?.let { withStyle(muted) { append("$it  ") } }
                game.leaders.forEachIndexed { i, leader ->
                    if (i > 0) append("   ")
                    withStyle(muted) { append(leader.position + " ") }
                    withStyle(text) { append(leader.name) }
                    leader.score?.let { withStyle(gold) { append(" $it") } }
                }
            }
        }
        TickerKind.TEAMS, TickerKind.HEAD_TO_HEAD -> {
            val showScores = game.state != GameState.PRE
            val final = game.state == GameState.FINAL
            fun side(team: TeamLine) {
                withStyle(if (final && team.winner) gold else text) { append(team.abbreviation) }
                team.score?.takeIf { showScores }?.let { withStyle(text) { append(" $it") } }
            }
            side(game.away)
            withStyle(muted) { append(if (game.kind == TickerKind.TEAMS) "  @  " else "  vs  ") }
            side(game.home)
        }
    }
    if (game.state == GameState.LIVE) {
        append("  ")
        withStyle(SpanStyle(color = TickerColors.live, fontWeight = FontWeight.Black)) { append("\u25CF $liveLabel") }
    }
    game.status.ifBlank { null }?.let { withStyle(muted) { append("  $it") } }
    game.odds?.let { odds ->
        odds.line?.let { append("   "); withStyle(muted) { append("$lineLabel ") }; withStyle(gold) { append(it) } }
        odds.total?.let { append("   "); withStyle(muted) { append("$totalLabel ") }; withStyle(gold) { append(it) } }
        if (odds.moneylineAway != null && odds.moneylineHome != null) {
            append("   ")
            withStyle(muted) { append("$moneylineLabel ") }
            withStyle(gold) { append("${game.away.abbreviation} ${odds.moneylineAway} · ${game.home.abbreviation} ${odds.moneylineHome}") }
        }
    }
}
