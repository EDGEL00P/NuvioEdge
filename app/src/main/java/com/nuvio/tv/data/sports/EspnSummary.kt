package com.nuvio.tv.data.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One box-score line: label with the away and home values. */
data class StatRow(val label: String, val away: String, val home: String)

data class StandingsRow(val team: String, val values: List<String>)

data class StandingsTable(val title: String, val columns: List<String>, val rows: List<StandingsRow>)

data class InjuryLine(val team: String, val player: String, val status: String)

/** Detail for one game, from ESPN's summary feed. Any part may be empty. */
data class GameDetail(
    val stats: List<StatRow> = emptyList(),
    val standings: List<StandingsTable> = emptyList(),
    val injuries: List<InjuryLine> = emptyList(),
    val headlines: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = stats.isEmpty() && standings.isEmpty() && injuries.isEmpty() && headlines.isEmpty()
}

/**
 * Parses `site.api.espn.com/apis/site/v2/sports/{sport}/{league}/summary?event={id}`. The shape
 * is the same across team sports (checked for NFL, NBA, EPL and AFL on 2026-10-25); missing or
 * unexpected parts are skipped.
 */
object EspnSummaryParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Standings columns worth showing, in this order, when the sport has them. */
    private val COLUMNS = listOf("GP", "W", "D", "T", "L", "PCT", "GD", "P", "PF", "PA", "TP", "PER")

    fun parse(body: String, maxStats: Int = 8, maxPerTeam: Int = 6, maxHeadlines: Int = 5): GameDetail {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return GameDetail()
        return GameDetail(
            stats = runCatching { stats(root, maxStats) }.getOrDefault(emptyList()),
            standings = runCatching { standings(root) }.getOrDefault(emptyList()),
            injuries = runCatching { injuries(root, maxPerTeam) }.getOrDefault(emptyList()),
            headlines = runCatching { headlines(root, maxHeadlines) }.getOrDefault(emptyList())
        )
    }

    private fun stats(root: JsonObject, max: Int): List<StatRow> {
        val teams = root.obj("boxscore")?.objects("teams").orEmpty()
        val away = teams.firstOrNull { it.str("homeAway") == "away" } ?: return emptyList()
        val home = teams.firstOrNull { it.str("homeAway") == "home" } ?: return emptyList()
        val homeValues = home.objects("statistics").associate { (it.str("name") ?: "") to (it.str("displayValue") ?: "") }
        return away.objects("statistics").mapNotNull { stat ->
            val name = stat.str("name") ?: return@mapNotNull null
            val label = stat.str("label") ?: name
            StatRow(label, stat.str("displayValue") ?: "", homeValues[name] ?: "")
        }.take(max)
    }

    private fun standings(root: JsonObject): List<StandingsTable> =
        root.obj("standings")?.objects("groups").orEmpty().mapNotNull { group ->
            val entries = group.obj("standings")?.objects("entries").orEmpty()
            if (entries.isEmpty()) return@mapNotNull null
            fun statsOf(entry: JsonObject): Map<String, String> = entry.objects("stats").mapNotNull { s ->
                val key = s.str("abbreviation") ?: s.str("name") ?: return@mapNotNull null
                key to (s.str("displayValue") ?: "")
            }.toMap()
            val columns = COLUMNS.filter { it in statsOf(entries.first()) }.take(6)
            StandingsTable(
                title = group.str("header") ?: "Standings",
                columns = columns,
                rows = entries.map { entry ->
                    val team = entry.str("team") ?: entry.obj("team")?.let { it.str("displayName") ?: it.str("abbreviation") } ?: "?"
                    val values = statsOf(entry)
                    StandingsRow(team, columns.map { values[it].orEmpty() })
                }
            )
        }

    private fun injuries(root: JsonObject, maxPerTeam: Int): List<InjuryLine> =
        root.objects("injuries").flatMap { block ->
            val team = block.obj("team")?.let { it.str("abbreviation") ?: it.str("displayName") } ?: "?"
            block.objects("injuries").take(maxPerTeam).mapNotNull { injury ->
                val player = injury.obj("athlete")?.str("displayName") ?: return@mapNotNull null
                val status = listOfNotNull(
                    injury.str("status"),
                    injury.obj("details")?.str("type")?.let { "($it)" }
                ).joinToString(" ")
                InjuryLine(team, player, status)
            }
        }

    private fun headlines(root: JsonObject, max: Int): List<String> =
        root.obj("news")?.objects("articles").orEmpty().mapNotNull { it.str("headline")?.takeIf(String::isNotBlank) }.take(max)

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? = when (val value: JsonElement? = this[key]) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it != "null" }
        else -> null
    }
}
