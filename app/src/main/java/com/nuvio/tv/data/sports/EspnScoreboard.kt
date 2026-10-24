package com.nuvio.tv.data.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A league on ESPN's public scoreboard feed. Paths checked against the live feed on 2026-10-25. */
data class SportsLeague(val label: String, val path: String)

val MAJOR_LEAGUES = listOf(
    SportsLeague("NFL", "football/nfl"),
    SportsLeague("NBA", "basketball/nba"),
    SportsLeague("MLB", "baseball/mlb"),
    SportsLeague("NHL", "hockey/nhl"),
    SportsLeague("NCAAF", "football/college-football"),
    SportsLeague("NCAAM", "basketball/mens-college-basketball"),
    SportsLeague("WNBA", "basketball/wnba"),
    SportsLeague("MLS", "soccer/usa.1"),
    SportsLeague("EPL", "soccer/eng.1"),
    SportsLeague("UCL", "soccer/uefa.champions"),
    SportsLeague("AFL", "australian-football/afl"),
    SportsLeague("NRL", "rugby-league/3")
)

enum class GameState { PRE, LIVE, FINAL }

data class TeamLine(val abbreviation: String, val score: String?)

data class GameOdds(
    /** e.g. "PIT -3" */
    val line: String?,
    val total: String?,
    val moneylineAway: String?,
    val moneylineHome: String?,
    val provider: String?
) {
    val isEmpty: Boolean get() = line == null && total == null && moneylineAway == null && moneylineHome == null
}

data class TickerGame(
    val id: String,
    val league: String,
    val away: TeamLine,
    val home: TeamLine,
    val state: GameState,
    /** ESPN's short status, e.g. "Q3 8:42", "Final", "10/25 - 9:30 AM EDT". */
    val status: String,
    val startIso: String?,
    val odds: GameOdds?
)

/** Parses ESPN's site API scoreboard JSON. Unknown or missing fields are skipped, never fatal. */
object EspnScoreboardParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, league: String): List<TickerGame> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { (it as? JsonObject)?.let { event -> parseEvent(event, league) } }
    }

    private fun parseEvent(event: JsonObject, league: String): TickerGame? {
        val id = event.str("id") ?: return null
        val competition = (event["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val competitors = (competition["competitors"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: return null
        val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: return null
        val statusType = event.obj("status")?.obj("type")
        val state = when (statusType?.str("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.PRE
        }
        return TickerGame(
            id = id,
            league = league,
            away = teamLine(away),
            home = teamLine(home),
            state = state,
            status = statusType?.str("shortDetail") ?: statusType?.str("detail") ?: "",
            startIso = event.str("date"),
            odds = (competition["odds"] as? JsonArray)?.firstOrNull()?.let { parseOdds(it as? JsonObject) }
        )
    }

    private fun teamLine(competitor: JsonObject) = TeamLine(
        abbreviation = competitor.obj("team")?.let { it.str("abbreviation") ?: it.str("shortDisplayName") } ?: "?",
        score = competitor.str("score")
    )

    private fun parseOdds(odds: JsonObject?): GameOdds? {
        if (odds == null) return null
        val moneyline = odds.obj("moneyline")
        fun ml(side: String): String? =
            moneyline?.obj(side)?.let { s -> s.obj("close")?.str("odds") ?: s.obj("current")?.str("odds") ?: s.obj("open")?.str("odds") }
        val result = GameOdds(
            line = odds.str("details")?.takeIf { it.isNotBlank() },
            total = odds.str("overUnder")?.takeIf { it.isNotBlank() },
            moneylineAway = ml("away"),
            moneylineHome = ml("home"),
            provider = odds.obj("provider")?.str("name")
        )
        return result.takeUnless { it.isEmpty }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? = when (val value: JsonElement? = this[key]) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it != "null" }
        else -> null
    }
}
