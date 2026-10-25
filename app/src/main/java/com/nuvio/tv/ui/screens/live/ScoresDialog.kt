@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.sports.GameDetail
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.TickerGame
import com.nuvio.tv.data.sports.TickerKind
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Today's games across every sport, with box score, standings, injuries and headlines for team
 * sports, and the channels in the user's own guide that are showing the selected game.
 */
@Composable
internal fun ScoresDialog(
    loadGames: suspend () -> List<TickerGame>,
    loadDetail: suspend (TickerGame) -> GameDetail?,
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    tickerOn: Boolean,
    onToggleTicker: () -> Unit,
    onPlay: (IptvChannel) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scores_title), width = 1000.dp) {
        ScoresBrowser(
            loadGames = loadGames,
            loadDetail = loadDetail,
            channels = channels,
            guide = guide,
            nowMs = nowMs,
            onPlay = onPlay,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
        )
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(if (tickerOn) R.string.ticker_on else R.string.ticker_off),
                onClick = onToggleTicker
            )
            SettingsDialogActionButton(stringResource(R.string.scores_close), onClick = onDismiss, primary = true)
        }
    }
}

/**
 * Game list on the left, the selected game on the right. Used by the Scores dialog and the
 * Sports screen; with [refreshMs] the games reload on that interval while shown.
 */
@Composable
internal fun ScoresBrowser(
    loadGames: suspend () -> List<TickerGame>,
    loadDetail: suspend (TickerGame) -> GameDetail?,
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    onPlay: (IptvChannel) -> Unit,
    modifier: Modifier = Modifier,
    refreshMs: Long? = null,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    var games by remember { mutableStateOf<List<TickerGame>?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val loaded = runCatching { loadGames() }.getOrNull()
            if (loaded != null || games == null) games = loaded ?: emptyList()
            if (selectedKey == null || games.orEmpty().none { gameKey(it) == selectedKey }) {
                selectedKey = games.orEmpty().firstOrNull()?.let(::gameKey)
            }
            kotlinx.coroutines.delay(refreshMs ?: break)
        }
    }
    val selected = games?.firstOrNull { gameKey(it) == selectedKey }

    Row(modifier = modifier) {
        val list = games
        when {
            list == null -> Box(Modifier.width(360.dp)) { LoadingIndicator(Modifier.size(32.dp)) }
            list.isEmpty() -> Text(
                stringResource(R.string.scores_empty),
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.width(360.dp)
            )
            else -> LazyColumn(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding)
            ) {
                items(list, key = ::gameKey) { game ->
                    ListItem(
                        selected = gameKey(game) == selectedKey,
                        onClick = { selectedKey = gameKey(game) },
                        colors = ListItemDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            selectedContainerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.FocusContent
                        ),
                        headlineContent = { Text(matchupText(game), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOf(game.league, game.status).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = if (game.state == GameState.LIVE) {
                            { LiveBadge() }
                        } else null
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        selected?.let { game ->
            GamePanel(game, loadDetail, channels, guide, nowMs, onPlay, Modifier.weight(1f))
        }
    }
}

private fun gameKey(game: TickerGame): String = game.league + ":" + game.id

@Composable
private fun LiveBadge() {
    val live = androidx.compose.ui.graphics.Color(0xFFE11D48)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(live, androidx.compose.foundation.shape.CircleShape))
        Text(
            text = stringResource(R.string.ticker_live),
            style = MaterialTheme.typography.labelSmall,
            color = live,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

@Composable
private fun GamePanel(
    game: TickerGame,
    loadDetail: suspend (TickerGame) -> GameDetail?,
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    onPlay: (IptvChannel) -> Unit,
    modifier: Modifier
) {
    // Keyed by the game, not the scores: a refresh with new scores keeps the loaded detail.
    val key = gameKey(game)
    var detail by remember(key) { mutableStateOf<GameDetail?>(null) }
    var detailLoaded by remember(key) { mutableStateOf(false) }
    var matches by remember(key) { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(key, channels, guide) {
        matches = withContext(Dispatchers.Default) { channelsShowing(game, channels, guide, nowMs) }
    }
    LaunchedEffect(key) {
        if (game.kind == TickerKind.TEAMS) detail = runCatching { loadDetail(game) }.getOrNull()
        detailLoaded = true
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section {
            Text(matchupText(game), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
            Text(
                listOfNotNull(game.league, game.event, game.status.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (game.state == GameState.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
            )
            game.leaders.forEach { leader ->
                Text(
                    listOfNotNull(leader.position, leader.name, leader.score).joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }

        // Channels in the user's guide showing this game.
        matches?.let { found ->
            if (found.isEmpty()) {
                if (game.kind != TickerKind.LEADERBOARD) Section { Muted(stringResource(R.string.scores_not_in_guide)) }
            } else {
                Text(stringResource(R.string.scores_on_your_channels), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary)
                found.forEach { match ->
                    Button(
                        onClick = { onPlay(match.channel) },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${match.channel.name} · ${match.programme.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        when {
            game.kind != TickerKind.TEAMS -> Section { Muted(stringResource(R.string.scores_team_sports_only)) }
            !detailLoaded -> LoadingIndicator(Modifier.size(28.dp))
            detail == null || detail?.isEmpty == true -> Section { Muted(stringResource(R.string.scores_no_detail)) }
            else -> DetailSections(game, detail!!)
        }
    }
}

@Composable
private fun DetailSections(game: TickerGame, detail: GameDetail) {
    if (detail.stats.isNotEmpty()) {
        Section {
            Heading(stringResource(R.string.scores_box_score))
            TableRow(listOf("", game.away.abbreviation, game.home.abbreviation), bold = true)
            detail.stats.forEach { TableRow(listOf(it.label, it.away, it.home)) }
        }
    }
    detail.standings.take(2).forEach { table ->
        Section {
            Heading(table.title)
            TableRow(listOf("") + table.columns, bold = true)
            table.rows.take(10).forEach { row -> TableRow(listOf(row.team) + row.values) }
        }
    }
    if (detail.injuries.isNotEmpty()) {
        Section {
            Heading(stringResource(R.string.scores_injuries))
            detail.injuries.forEach { Muted("${it.team}  ${it.player} — ${it.status}") }
        }
    }
    if (detail.headlines.isNotEmpty()) {
        Section {
            Heading(stringResource(R.string.scores_headlines))
            detail.headlines.forEach { Muted(it) }
        }
    }
}

/** A focusable block, so the D-pad can scroll through the panel one section at a time. */
@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .focusable()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary)

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)

@Composable
private fun TableRow(cells: List<String>, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { i, cell ->
            Text(
                text = cell,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (i == 0) Modifier.weight(2f) else Modifier.weight(1f)
            )
        }
    }
}
