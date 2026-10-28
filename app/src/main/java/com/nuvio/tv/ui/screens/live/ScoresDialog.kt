@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.sports.GameDetail
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.SportsFollowPreferences
import com.nuvio.tv.data.sports.SportsTickerRepository
import com.nuvio.tv.data.sports.TeamLine
import com.nuvio.tv.data.sports.TickerGame
import com.nuvio.tv.data.sports.TickerKind
import com.nuvio.tv.data.sports.withoutScores
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.ticker.SportsScorePipActivity
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onDismiss: () -> Unit,
    follows: SportsFollowPreferences = SportsFollowPreferences(),
    onFollowLeague: (String) -> Unit = {},
    onFollowTeam: (String, TeamLine) -> Unit = { _, _ -> },
    hideScores: Boolean = true,
    onToggleHideScores: () -> Unit = {},
    channelOverrides: Map<String, String> = emptyMap(),
    saveChannelOverride: suspend (TickerGame, String?) -> Unit = { _, _ -> },
    teamChannelOverrides: Map<String, String> = emptyMap(),
    saveTeamChannelOverride: suspend (String, TeamLine, String?) -> Unit = { _, _, _ -> },
    sourceNames: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val pipSupported = remember(context) { SportsScorePipActivity.isSupported(context) }
    var pipFailed by remember { mutableStateOf(false) }
    var games by remember { mutableStateOf<List<TickerGame>?>(null) }
    var selected by remember { mutableStateOf<TickerGame?>(null) }
    var correcting by remember { mutableStateOf<SportsChannelCorrection?>(null) }
    var savingCorrection by remember { mutableStateOf(false) }
    var correctionError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var mySports by rememberSaveable { mutableStateOf(false) }
    val visibleGames = games?.let { loaded -> if (mySports) loaded.filter(follows::includes) else loaded }
    LaunchedEffect(Unit) {
        val loaded = try {
            loadGames()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        games = loaded
        if (selected == null) selected = loaded.firstOrNull()
    }
    LaunchedEffect(visibleGames) {
        selected = visibleGames?.firstOrNull { it.id == selected?.id && it.league == selected?.league }
            ?: visibleGames?.firstOrNull()
    }

    val correction = correcting
    val correctionGame = correction?.game
    if (correction != null && correctionGame != null) {
        val correctionTeam = correction.team
        SportsChannelPicker(
            game = correctionGame,
            channels = channels,
            sourceNames = sourceNames,
            title = if (correctionTeam == null) {
                stringResource(R.string.sports_assign_channel)
            } else {
                stringResource(
                    R.string.sports_assign_team_channel,
                    correctionTeam.shortName ?: correctionTeam.abbreviation
                )
            },
            currentChannelId = if (correctionTeam == null) {
                channelOverrides[SportsTickerRepository.eventChannelKey(correctionGame)]
            } else {
                SportsTickerRepository.teamChannelKey(correctionGame.league, correctionTeam)?.let(teamChannelOverrides::get)
            },
            saving = savingCorrection,
            failed = correctionError,
            onDismiss = { if (!savingCorrection) correcting = null },
            onSelect = onSelect@{ channelId ->
                if (savingCorrection) return@onSelect
                savingCorrection = true
                correctionError = false
                scope.launch {
                    try {
                        if (correctionTeam == null) {
                            saveChannelOverride(correctionGame, channelId)
                        } else {
                            saveTeamChannelOverride(correctionGame.league, correctionTeam, channelId)
                        }
                        correcting = null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        correctionError = true
                    } finally {
                        savingCorrection = false
                    }
                }
            }
        )
        return
    }

    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scores_title), width = 1000.dp) {
        TabRow(selectedTabIndex = if (mySports) 1 else 0) {
            Tab(selected = !mySports, onFocus = { mySports = false }, onClick = { mySports = false }) {
                Text(stringResource(R.string.sports_all), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Tab(selected = mySports, onFocus = { mySports = true }, onClick = { mySports = true }) {
                Text(stringResource(R.string.sports_my), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            val list = visibleGames
            when {
                list == null -> Box(Modifier.width(360.dp)) { LoadingIndicator(Modifier.size(32.dp)) }
                list.isEmpty() -> Text(
                    stringResource(if (mySports) R.string.sports_my_empty else R.string.scores_empty),
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.width(360.dp)
                )
                else -> LazyColumn(
                    modifier = Modifier.width(360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(list, key = { it.league + ":" + it.id }) { game ->
                        ListItem(
                            selected = game == selected,
                            onClick = { selected = game },
                            colors = ListItemDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                selectedContainerColor = NuvioTheme.colors.BackgroundElevated,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.FocusContent
                            ),
                            headlineContent = { Text(matchupText(if (hideScores) game.withoutScores() else game), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOf(game.league, if (hideScores) stringResource(R.string.sports_scores_hidden) else game.status).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            selected?.let { game ->
                val (channelOverrideId, channelOverrideConflict) = remember(game, channelOverrides, teamChannelOverrides) {
                    SportsTickerRepository.resolveChannelOverride(game, channelOverrides, teamChannelOverrides)
                }
                GamePanel(
                    game, loadDetail, channels, guide, nowMs, onPlay, Modifier.weight(1f),
                    follows, onFollowLeague, onFollowTeam, hideScores,
                    channelOverrideId,
                    channelOverrideConflict,
                    onAssignChannel = { correctionError = false; correcting = SportsChannelCorrection(game) },
                    onAssignTeamChannel = { team ->
                        correctionError = false
                        correcting = SportsChannelCorrection(game, team)
                    }
                )
            }
        }
        if (pipFailed) {
            Text(stringResource(R.string.sports_pip_unavailable), color = NuvioTheme.colors.Error)
        }
        SettingsDialogActionRow {
            Row(
                modifier = Modifier.toggleable(value = hideScores, role = Role.Checkbox, onValueChange = { onToggleHideScores() }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Checkbox(checked = hideScores, onCheckedChange = null)
                Text(stringResource(R.string.sports_hide_scores), modifier = Modifier.padding(end = 12.dp))
            }
            if (pipSupported) {
                IconButton(onClick = {
                    if (SportsScorePipActivity.open(context)) onDismiss() else pipFailed = true
                }) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = stringResource(R.string.sports_pip_title))
                }
            }
            SettingsDialogActionButton(
                text = stringResource(if (tickerOn) R.string.ticker_on else R.string.ticker_off),
                onClick = onToggleTicker
            )
            SettingsDialogActionButton(stringResource(R.string.scores_close), onClick = onDismiss, primary = true)
        }
    }
}

private data class SportsChannelCorrection(val game: TickerGame, val team: TeamLine? = null)

@Composable
private fun GamePanel(
    game: TickerGame,
    loadDetail: suspend (TickerGame) -> GameDetail?,
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    onPlay: (IptvChannel) -> Unit,
    modifier: Modifier,
    follows: SportsFollowPreferences,
    onFollowLeague: (String) -> Unit,
    onFollowTeam: (String, TeamLine) -> Unit,
    hideScores: Boolean,
    channelOverrideId: String?,
    channelOverrideConflict: Boolean,
    onAssignChannel: () -> Unit,
    onAssignTeamChannel: (TeamLine) -> Unit
) {
    var detail by remember(game) { mutableStateOf<GameDetail?>(null) }
    var detailLoaded by remember(game) { mutableStateOf(false) }
    var matches by remember(game) { mutableStateOf<List<ChannelMatch>?>(null) }
    LaunchedEffect(game, hideScores) {
        matches = withContext(Dispatchers.Default) { channelsShowing(game, channels, guide, nowMs) }
        detail = null
        if (!hideScores && game.kind == TickerKind.TEAMS) {
            detail = try {
                loadDetail(game)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        detailLoaded = true
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section {
            Text(matchupText(if (hideScores) game.withoutScores() else game), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
            Text(
                listOfNotNull(game.league, game.event, if (hideScores) stringResource(R.string.sports_scores_hidden) else game.status.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (game.state == GameState.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
            )
            game.leaders.takeUnless { hideScores }.orEmpty().forEach { leader ->
                Text(
                    listOfNotNull(leader.position, leader.name, leader.score).joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
            FollowSportsButton(
                followed = follows.followsLeague(game.league),
                label = game.league,
                onClick = { onFollowLeague(game.league) }
            )
            if (game.kind == TickerKind.TEAMS) {
                listOf(game.away, game.home)
                    .filter { SportsFollowPreferences.teamKey(game.league, it) != null }
                    .distinctBy { SportsFollowPreferences.teamKey(game.league, it) }
                    .forEach { team ->
                        FollowSportsButton(
                            followed = follows.followsTeam(game.league, team),
                            label = team.fullName ?: team.shortName ?: team.abbreviation,
                            onClick = { onFollowTeam(game.league, team) }
                        )
                        Button(onClick = { onAssignTeamChannel(team) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    R.string.sports_assign_team_channel,
                                    team.shortName ?: team.abbreviation
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
            }
        }

        Button(onClick = onAssignChannel, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sports_assign_channel))
        }
        if (channelOverrideId != null) {
            val assigned = channels.firstOrNull { it.id == channelOverrideId }
            if (assigned == null) {
                Text(stringResource(R.string.sports_assigned_channel_missing), color = NuvioTheme.colors.Warning)
            } else {
                Text(stringResource(R.string.sports_assigned_channel), color = NuvioTheme.colors.TextSecondary)
                Button(onClick = { onPlay(assigned) }, modifier = Modifier.fillMaxWidth()) {
                    Text(assigned.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (channelOverrideConflict) {
            Section { Muted(stringResource(R.string.sports_team_channel_conflict)) }
        }
        matches?.takeIf { channelOverrideId == null && !channelOverrideConflict }?.let { found ->
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
                        Text(if (hideScores) match.channel.name else "${match.channel.name} · ${match.programme.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        when {
            hideScores -> Section { Muted(stringResource(R.string.sports_scores_hidden)) }
            game.kind != TickerKind.TEAMS -> Section { Muted(stringResource(R.string.scores_team_sports_only)) }
            !detailLoaded -> LoadingIndicator(Modifier.size(28.dp))
            detail == null || detail?.isEmpty == true -> Section { Muted(stringResource(R.string.scores_no_detail)) }
            else -> DetailSections(game, detail!!)
        }
    }
}

@Composable
private fun SportsChannelPicker(
    game: TickerGame,
    channels: List<IptvChannel>,
    sourceNames: Map<String, String>,
    title: String,
    currentChannelId: String?,
    saving: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    var query by remember(game.league, game.id) { mutableStateOf("") }
    val filtered by produceState<List<IptvChannel>?>(initialValue = null, channels, query) {
        delay(120)
        val search = query.trim()
        value = withContext(Dispatchers.Default) {
            channels.asSequence().filter { channel ->
                search.isEmpty() || channel.name.contains(search, ignoreCase = true) ||
                    channel.group.contains(search, ignoreCase = true) ||
                    sourceNames[channel.sourceId]?.contains(search, ignoreCase = true) == true
            }.take(500).toList()
        }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 900.dp) {
        InputField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.sports_channel_search),
            modifier = Modifier.fillMaxWidth()
        )
        if (failed) Text(stringResource(R.string.sports_channel_save_failed), color = NuvioTheme.colors.Error)
        val results = filtered
        when {
            results == null -> LoadingIndicator(Modifier.size(28.dp))
            results.isEmpty() -> Text(stringResource(R.string.sports_no_matching_channels), color = NuvioTheme.colors.TextSecondary)
            else -> {
                if (results.size >= 500) Text(stringResource(R.string.sports_channel_limit), color = NuvioTheme.colors.TextSecondary)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(results, key = { it.id }) { channel ->
                        ListItem(
                            selected = channel.id == currentChannelId,
                            enabled = !saving,
                            onClick = { onSelect(channel.id) },
                            headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(sourceNames[channel.sourceId], channel.group.takeIf(String::isNotBlank)).joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }
        SettingsDialogActionRow {
            if (currentChannelId != null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.sports_channel_automatic),
                    onClick = { onSelect(null) },
                    enabled = !saving
                )
            }
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = onDismiss, enabled = !saving)
        }
    }
}

@Composable
private fun FollowSportsButton(followed: Boolean, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (followed) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(if (followed) R.string.sports_following else R.string.sports_follow, label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
