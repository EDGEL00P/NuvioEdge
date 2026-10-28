package com.nuvio.tv.data.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a sport's ESPN feed is shaped. */
enum class FeedFormat {
    /** Two teams, home and away (football, basketball, soccer, rugby, hockey...). */
    TEAMS,
    /** Tournaments holding many player-vs-player matches. */
    TENNIS,
    /** One event with a full-field leaderboard. */
    GOLF,
    /** One event made of sessions (practice, qualifying, race), each with a running order. */
    RACING,
    /** A fight card: many fighter-vs-fighter bouts. */
    MMA
}

/** A league on ESPN's public scoreboard feed. Paths checked against the live feed on 2026-10-25. */
data class SportsLeague(val label: String, val path: String, val format: FeedFormat = FeedFormat.TEAMS)

/**
 * Every sport ESPN's free scoreboard feed serves in a usable form. Not included: cricket (the feed
 * returns events without match data) and boxing (no scoreboard endpoint).
 */
val ALL_LEAGUES = listOf(
    // American & Canadian football
    SportsLeague("NFL", "football/nfl"),
    SportsLeague("NCAAF", "football/college-football"),
    SportsLeague("CFL", "football/cfl"),
    SportsLeague("UFL", "football/ufl"),
    // Basketball
    SportsLeague("NBA", "basketball/nba"),
    SportsLeague("WNBA", "basketball/wnba"),
    SportsLeague("NBL", "basketball/nbl"),
    SportsLeague("NCAAM", "basketball/mens-college-basketball"),
    SportsLeague("NCAAW", "basketball/womens-college-basketball"),
    // Baseball & hockey
    SportsLeague("MLB", "baseball/mlb"),
    SportsLeague("NCAABB", "baseball/college-baseball"),
    SportsLeague("NHL", "hockey/nhl"),
    SportsLeague("NCAAH", "hockey/mens-college-hockey"),
    // Football (soccer)
    SportsLeague("EPL", "soccer/eng.1"),
    SportsLeague("LALIGA", "soccer/esp.1"),
    SportsLeague("SERIEA", "soccer/ita.1"),
    SportsLeague("BUNDES", "soccer/ger.1"),
    SportsLeague("LIGUE1", "soccer/fra.1"),
    SportsLeague("UCL", "soccer/uefa.champions"),
    SportsLeague("UEL", "soccer/uefa.europa"),
    SportsLeague("MLS", "soccer/usa.1"),
    SportsLeague("ALM", "soccer/aus.1"),
    SportsLeague("WC", "soccer/fifa.world"),
    // Australian rules & rugby
    SportsLeague("AFL", "australian-football/afl"),
    SportsLeague("NRL", "rugby-league/3"),
    SportsLeague("SUPER", "rugby/242041"),
    SportsLeague("6N", "rugby/180659"),
    SportsLeague("RWC", "rugby/164205"),
    SportsLeague("PREM R", "rugby/267979"),
    SportsLeague("URC", "rugby/270557"),
    // Individual sports
    SportsLeague("ATP", "tennis/atp", FeedFormat.TENNIS),
    SportsLeague("WTA", "tennis/wta", FeedFormat.TENNIS),
    SportsLeague("PGA", "golf/pga", FeedFormat.GOLF),
    SportsLeague("LPGA", "golf/lpga", FeedFormat.GOLF),
    SportsLeague("DPWT", "golf/eur", FeedFormat.GOLF),
    SportsLeague("LIV", "golf/liv", FeedFormat.GOLF),
    SportsLeague("F1", "racing/f1", FeedFormat.RACING),
    SportsLeague("INDY", "racing/irl", FeedFormat.RACING),
    SportsLeague("NASCAR", "racing/nascar-premier", FeedFormat.RACING),
    SportsLeague("UFC", "mma/ufc", FeedFormat.MMA),
    SportsLeague("PFL", "mma/pfl", FeedFormat.MMA),
    // Others
    SportsLeague("PLL", "lacrosse/pll"),
    SportsLeague("NCAAVB", "volleyball/womens-college-volleyball")
)

enum class GameState { PRE, LIVE, FINAL }

/** How a ticker item is drawn. */
enum class TickerKind { TEAMS, HEAD_TO_HEAD, LEADERBOARD }

data class TeamLine(
    val abbreviation: String,
    val score: String?,
    val winner: Boolean = false,
    /** "Pittsburgh Steelers"; null for players. */
    val fullName: String? = null,
    /** "Steelers"; null for players. */
    val shortName: String? = null,
    val providerId: String? = null
)

/** One row of a leaderboard: position ("1", "T2"), name, score ("-16", "E"), if any. */
data class LeaderLine(val position: String, val name: String, val score: String?)

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
    val odds: GameOdds?,
    val kind: TickerKind = TickerKind.TEAMS,
    /** Tournament, Grand Prix or fight card name, for individual sports. */
    val event: String? = null,
    /** Top of the leaderboard, for golf and racing. */
    val leaders: List<LeaderLine> = emptyList()
)

internal fun TickerGame.withoutScores(): TickerGame = copy(
    away = away.copy(score = null, winner = false),
    home = home.copy(score = null, winner = false),
    status = "",
    odds = null,
    leaders = emptyList()
)

/** Parses ESPN's site API scoreboard JSON. Unknown or missing fields are skipped, never fatal. */
object EspnScoreboardParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val LEADERS = 3
    private val NO_SIDE = TeamLine("", null)

    /**
     * @param zone time zone for start times. ESPN writes them in US Eastern ("4:00 PM EDT"); the
     *   ticker shows them in the TV's own zone instead.
     */
    fun parse(
        body: String,
        league: String,
        format: FeedFormat = FeedFormat.TEAMS,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<TickerGame> = withZone(zone) {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return@withZone emptyList()
        val events = root["events"] as? JsonArray ?: return@withZone emptyList()
        events.mapNotNull { it as? JsonObject }.flatMap { event ->
            runCatching {
                when (format) {
                    FeedFormat.TEAMS -> listOfNotNull(parseEvent(event, league))
                    FeedFormat.TENNIS -> parseTennis(event, league)
                    FeedFormat.GOLF -> listOfNotNull(parseGolf(event, league))
                    FeedFormat.RACING -> listOfNotNull(parseRacing(event, league))
                    FeedFormat.MMA -> parseFights(event, league)
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun parseEvent(event: JsonObject, league: String): TickerGame? {
        val id = event.str("id") ?: return null
        val competition = event.arr("competitions")?.firstOrNull() as? JsonObject ?: return null
        val competitors = competition.objects("competitors")
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: return null
        val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: return null
        val statusType = event.obj("status")?.obj("type")
        return TickerGame(
            id = id,
            league = league,
            away = teamLine(away),
            home = teamLine(home),
            state = state(statusType),
            status = detail(statusType, event.str("date")),
            startIso = event.str("date"),
            odds = (competition["odds"] as? JsonArray)?.firstOrNull()?.let { parseOdds(it as? JsonObject) }
        )
    }

    /** Singles matches only: doubles pairs have no short player names in this feed. */
    private fun parseTennis(event: JsonObject, league: String): List<TickerGame> {
        val tournament = event.str("shortName") ?: event.str("name")
        return event.objects("groupings")
            .filter { it.obj("grouping")?.str("displayName")?.contains("Singles", ignoreCase = true) == true }
            .flatMap { it.objects("competitions") }
            .mapNotNull { match ->
                val players = match.objects("competitors").sortedBy { it.int("order") ?: Int.MAX_VALUE }
                if (players.size != 2) return@mapNotNull null
                val statusType = match.obj("status")?.obj("type")
                val round = match.obj("round")?.str("displayName")
                TickerGame(
                    id = match.str("id") ?: return@mapNotNull null,
                    league = league,
                    away = athleteLine(players[0], score = sets(players[0])),
                    home = athleteLine(players[1], score = sets(players[1])),
                    state = state(statusType),
                    status = listOfNotNull(round, detail(statusType, match.str("date")).ifBlank { null }).joinToString(" · "),
                    startIso = match.str("date"),
                    odds = null,
                    kind = TickerKind.HEAD_TO_HEAD,
                    event = tournament
                )
            }
    }

    /** Fights that are on now, then the main event and co-main event (ESPN lists the card in running order). */
    private fun parseFights(event: JsonObject, league: String): List<TickerGame> {
        val card = event.str("shortName") ?: event.str("name")
        val fights = event.objects("competitions")
        val live = fights.filter { it.obj("status")?.obj("type")?.str("state") == "in" }
        val picked = (live + fights.takeLast(2).reversed()).distinctBy { it.str("id") }
        return picked.mapNotNull { fight ->
            val fighters = fight.objects("competitors").sortedBy { it.int("order") ?: Int.MAX_VALUE }
            if (fighters.size != 2) return@mapNotNull null
            val statusType = fight.obj("status")?.obj("type")
            val weight = fight.obj("type")?.str("abbreviation")
            TickerGame(
                id = fight.str("id") ?: return@mapNotNull null,
                league = league,
                away = athleteLine(fighters[0], score = null),
                home = athleteLine(fighters[1], score = null),
                state = state(statusType),
                status = listOfNotNull(weight, detail(statusType, fight.str("date")).ifBlank { null }).joinToString(" · "),
                startIso = fight.str("date"),
                odds = null,
                kind = TickerKind.HEAD_TO_HEAD,
                event = card
            )
        }
    }

    private fun parseGolf(event: JsonObject, league: String): TickerGame? {
        val id = event.str("id") ?: return null
        val round = event.arr("competitions")?.firstOrNull() as? JsonObject ?: return null
        val field = round.objects("competitors").sortedBy { it.int("order") ?: Int.MAX_VALUE }
        if (field.isEmpty()) return null
        val eventStatus = event.obj("status")?.obj("type")
        val roundStatus = round.obj("status")?.obj("type")
        return TickerGame(
            id = id,
            league = league,
            away = NO_SIDE,
            home = NO_SIDE,
            state = state(eventStatus),
            status = roundStatus?.str("detail") ?: statusText(eventStatus),
            startIso = event.str("date"),
            odds = null,
            kind = TickerKind.LEADERBOARD,
            event = event.str("shortName") ?: event.str("name"),
            leaders = golfLeaders(field.map { name(it) to it.str("score") })
        )
    }

    /** Positions with ties marked "T": two players on -16 are both "T1". */
    internal fun golfLeaders(field: List<Pair<String, String?>>, take: Int = LEADERS): List<LeaderLine> =
        field.take(take).map { (player, score) ->
            val firstWithScore = field.indexOfFirst { it.second == score } + 1
            val tied = score != null && field.count { it.second == score } > 1
            LeaderLine(position = (if (tied) "T" else "") + firstWithScore, name = player, score = score)
        }

    /** The session on now, else the last one finished (with the next session as a heads-up), else the first. */
    private fun parseRacing(event: JsonObject, league: String): TickerGame? {
        val id = event.str("id") ?: return null
        val sessions = event.objects("competitions")
        if (sessions.isEmpty()) return null
        fun stateOf(s: JsonObject) = s.obj("status")?.obj("type")?.str("state")
        val chosenIndex = sessions.indexOfFirst { stateOf(it) == "in" }.takeIf { it >= 0 }
            ?: sessions.indexOfLast { stateOf(it) == "post" }.takeIf { it >= 0 }
            ?: 0
        val chosen = sessions[chosenIndex]
        val next = sessions.drop(chosenIndex + 1).firstOrNull { stateOf(it) == "pre" }
            ?.takeIf { stateOf(chosen) == "post" }
        fun label(s: JsonObject) = s.obj("type")?.str("abbreviation")
        fun sessionDetail(s: JsonObject) = detail(s.obj("status")?.obj("type"), s.str("date"))
        val state = when {
            stateOf(chosen) == "in" -> GameState.LIVE
            next != null || stateOf(chosen) == "pre" -> GameState.PRE
            else -> GameState.FINAL
        }
        val status = buildList {
            add(listOfNotNull(label(chosen), sessionDetail(chosen).ifBlank { null }).joinToString(" "))
            next?.let { add("Next: " + listOfNotNull(label(it), sessionDetail(it).ifBlank { null }).joinToString(" ")) }
        }.joinToString(" · ")
        val order = chosen.objects("competitors")
            .filter { it.int("order") != null }
            .sortedBy { it.int("order") }
            .take(LEADERS)
            .map { LeaderLine(position = it.int("order").toString(), name = name(it), score = null) }
        return TickerGame(
            id = id,
            league = league,
            away = NO_SIDE,
            home = NO_SIDE,
            state = state,
            status = status,
            startIso = (next ?: chosen).str("date"),
            odds = null,
            kind = TickerKind.LEADERBOARD,
            event = event.str("shortName") ?: event.str("name"),
            leaders = order
        )
    }

    private val zoneHolder = ThreadLocal<ZoneId>()
    private val clockTime = Regex("""\b\d{1,2}:\d{2}\s?(AM|PM)\b""", RegexOption.IGNORE_CASE)
    private val START_TIME_FORMAT: DateTimeFormatter = java.time.format.DateTimeFormatterBuilder()
        .appendPattern("EEE h:mm ")
        .appendText(java.time.temporal.ChronoField.AMPM_OF_DAY, mapOf(0L to "AM", 1L to "PM"))
        .toFormatter(Locale.ENGLISH)

    private inline fun <T> withZone(zone: ZoneId, block: () -> T): T {
        zoneHolder.set(zone)
        try {
            return block()
        } finally {
            zoneHolder.remove()
        }
    }

    /**
     * ESPN's status text, except that an upcoming start time ("10/25 - 4:00 PM EDT") is rewritten
     * in the TV's time zone ("Mon 7:00 AM"). Words like "Postponed" or "TBD" are kept as they are.
     */
    private fun detail(statusType: JsonObject?, startIso: String?): String {
        val text = statusText(statusType)
        if (statusType?.str("state") != "pre" || startIso == null || !clockTime.containsMatchIn(text)) return text
        return runCatching {
            // Pin the AM/PM marker: newer CLDR data (JDK 21+/Android 14+) renders "a" as "am"/"pm" by default.
            OffsetDateTime.parse(startIso).atZoneSameInstant(zoneHolder.get() ?: ZoneId.systemDefault())
                .format(START_TIME_FORMAT)
        }.getOrDefault(text)
    }

    private fun state(statusType: JsonObject?) = when (statusType?.str("state")) {
        "in" -> GameState.LIVE
        "post" -> GameState.FINAL
        else -> GameState.PRE
    }

    private fun statusText(statusType: JsonObject?) = statusType?.str("shortDetail") ?: statusType?.str("detail") ?: ""

    private fun teamLine(competitor: JsonObject) = TeamLine(
        abbreviation = competitor.obj("team")?.let { it.str("abbreviation") ?: it.str("shortDisplayName") } ?: "?",
        score = competitor.str("score"),
        winner = competitor.bool("winner"),
        fullName = competitor.obj("team")?.str("displayName"),
        shortName = competitor.obj("team")?.let { it.str("shortDisplayName") ?: it.str("name") },
        providerId = competitor.obj("team")?.str("id")
    )

    private fun athleteLine(competitor: JsonObject, score: String?) =
        TeamLine(abbreviation = name(competitor), score = score, winner = competitor.bool("winner"))

    private fun name(competitor: JsonObject): String =
        competitor.obj("athlete")?.let { it.str("shortName") ?: it.str("displayName") } ?: "?"

    /** Tennis set scores, e.g. "6 7". */
    private fun sets(competitor: JsonObject): String? =
        competitor.objects("linescores")
            .mapNotNull { (it["value"] as? JsonPrimitive)?.doubleOrNull?.toInt()?.toString() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")

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

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.objects(key: String): List<JsonObject> = arr(key)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"

    private fun JsonObject.str(key: String): String? = when (val value: JsonElement? = this[key]) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it != "null" }
        else -> null
    }
}
