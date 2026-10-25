package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.SportsTickerRepository
import com.nuvio.tv.data.sports.TeamLine
import com.nuvio.tv.data.sports.TickerGame
import com.nuvio.tv.data.sports.TickerKind
import kotlin.math.abs

/** A channel whose guide shows [programme] around the game's time; [score] = sides named (1 or 2). */
internal data class ChannelMatch(val channel: IptvChannel, val programme: EpgProgramme, val score: Int)

/** Words a guide title would use for one side: "steelers", "pittsburgh"; for a player, the surname. */
internal fun sideNames(side: TeamLine): List<String> {
    val names = ArrayList<String>()
    val short = side.shortName
    val full = side.fullName
    if (short != null) names += short
    if (full != null) names += if (short != null) full.removeSuffix(short).trim() else full
    if (short == null && full == null) names += side.abbreviation.substringAfterLast(' ')
    return names.map { it.lowercase() }.filter { it.length >= 4 }.distinct()
}

private const val NEAR_MS = 30 * 60_000L

/**
 * Channels in the user's guide showing [game]: programmes within 30 minutes of the game's time
 * (now, for a live game) whose title or description names a team or player. Both sides named
 * ranks first. Golf and racing are skipped: their titles rarely name the event the same way.
 */
internal fun channelsShowing(
    game: TickerGame,
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    max: Int = 5
): List<ChannelMatch> {
    if (game.kind == TickerKind.LEADERBOARD) return emptyList()
    val sides = listOf(sideNames(game.away), sideNames(game.home))
        .filter { it.isNotEmpty() }
        .map { names -> names.map { Regex("\\b" + Regex.escape(it) + "\\b") } }
    if (sides.isEmpty()) return emptyList()
    val at = if (game.state == GameState.LIVE) nowMs else SportsTickerRepository.startMs(game) ?: nowMs
    return channels.mapNotNull { channel ->
        guide[channel.id].orEmpty().asSequence()
            .filter { it.endMs > at - NEAR_MS && it.startMs < at + NEAR_MS }
            .map { programme ->
                val text = (programme.title + " " + programme.description.orEmpty()).lowercase()
                programme to sides.count { patterns -> patterns.any { it.containsMatchIn(text) } }
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.let { (programme, score) -> ChannelMatch(channel, programme, score) }
    }.sortedWith(compareByDescending<ChannelMatch> { it.score }.thenBy { abs(it.programme.startMs - at) })
        .take(max)
}

/** "PIT 21 @ NO 17", "J. Sinner vs C. Alcaraz", "United States GP: 1 K. Antonelli". */
internal fun matchupText(game: TickerGame): String = when (game.kind) {
    TickerKind.LEADERBOARD -> listOfNotNull(game.event, game.leaders.firstOrNull()?.let { "${it.position} ${it.name}" })
        .joinToString(": ")
    else -> {
        val scores = game.state != GameState.PRE
        fun side(t: TeamLine) = listOfNotNull(t.abbreviation, t.score?.takeIf { scores }).joinToString(" ")
        side(game.away) + (if (game.kind == TickerKind.TEAMS) " @ " else " vs ") + side(game.home)
    }
}
