package com.nuvio.tv.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Fixtures are trimmed copies of ESPN's live feed shapes (2026-10-25). */
class AllSportsParserTest {

    private fun status(state: String, short: String, detail: String = short) =
        """{"type":{"state":"$state","shortDetail":"$short","detail":"$detail"}}"""

    private fun athlete(order: Int, short: String, extra: String = "") =
        """{"order":$order,"athlete":{"shortName":"$short","displayName":"$short"}$extra}"""

    @Test
    fun `tennis singles matches become head-to-head items with set scores`() {
        val body = """{"events":[{"id":"t1","shortName":"European Open","status":${status("post", "Final")},
            "groupings":[
              {"grouping":{"displayName":"Men's Singles"},"competitions":[
                {"id":"m1","date":"2026-10-24T10:00Z","status":${status("post", "Final")},"round":{"displayName":"Qualifying 1st Round"},
                 "competitors":[
                   {"order":2,"homeAway":"away","winner":true,"athlete":{"shortName":"F. Cina"},"linescores":[{"value":6.0},{"value":7.0}]},
                   {"order":1,"homeAway":"home","winner":false,"athlete":{"shortName":"J. Schwaerzler"},"linescores":[{"value":3.0},{"value":6.0}]}]}]},
              {"grouping":{"displayName":"Men's Doubles"},"competitions":[
                {"id":"d1","status":${status("post", "Final")},"competitors":[{"order":1,"roster":{}},{"order":2,"roster":{}}]}]}]}]}"""
        val games = EspnScoreboardParser.parse(body, "ATP", FeedFormat.TENNIS)
        assertEquals(1, games.size)
        val match = games.single()
        assertEquals(TickerKind.HEAD_TO_HEAD, match.kind)
        assertEquals("J. Schwaerzler", match.away.abbreviation) // order 1 first
        assertEquals("3 6", match.away.score)
        assertEquals("F. Cina", match.home.abbreviation)
        assertEquals("6 7", match.home.score)
        assertTrue(match.home.winner)
        assertEquals(GameState.FINAL, match.state)
        assertEquals("Qualifying 1st Round · Final", match.status)
        assertEquals("European Open", match.event)
    }

    @Test
    fun `golf shows the top three with ties`() {
        val body = """{"events":[{"id":"g1","shortName":"Bermuda Championship","date":"2026-10-22T11:00Z","status":${status("in", "")},
            "competitions":[{"status":{"type":{"state":"in","detail":"Round 3 - Play Complete"}},"competitors":[
              ${athlete(2, "S. Power", ",\"score\":\"-16\"")},
              ${athlete(1, "A. Smotherman", ",\"score\":\"-16\"")},
              ${athlete(3, "T. Moore", ",\"score\":\"-14\"")},
              ${athlete(4, "J. Doe", ",\"score\":\"-12\"")}]}]}]}"""
        val event = EspnScoreboardParser.parse(body, "PGA", FeedFormat.GOLF).single()
        assertEquals(TickerKind.LEADERBOARD, event.kind)
        assertEquals(GameState.LIVE, event.state)
        assertEquals("Round 3 - Play Complete", event.status)
        assertEquals(
            listOf(LeaderLine("T1", "A. Smotherman", "-16"), LeaderLine("T1", "S. Power", "-16"), LeaderLine("3", "T. Moore", "-14")),
            event.leaders
        )
    }

    @Test
    fun `golf event with an empty field is skipped`() {
        val body = """{"events":[{"id":"g1","status":${status("pre", "Thu")},"competitions":[{"competitors":[]}]}]}"""
        assertTrue(EspnScoreboardParser.parse(body, "LIV", FeedFormat.GOLF).isEmpty())
    }

    @Test
    fun `racing shows the last finished session and flags the next one`() {
        val body = """{"events":[{"id":"r1","shortName":"United States GP","status":${status("post", "Final")},"competitions":[
              {"type":{"abbreviation":"FP1"},"date":"2026-10-23T17:30Z","status":${status("post", "Final")},"competitors":[${athlete(1, "L. Norris")}]},
              {"type":{"abbreviation":"Qual"},"date":"2026-10-24T21:00Z","status":${status("post", "Final")},"competitors":[
                 ${athlete(3, "I. Hadjar")},${athlete(1, "K. Antonelli")},${athlete(2, "M. Verstappen")},${athlete(4, "C. Leclerc")}]},
              {"type":{"abbreviation":"Race"},"date":"2026-10-25T20:00Z","status":${status("pre", "10/25 - 4:00 PM EDT")},"competitors":[
                 {"athlete":{"shortName":"K. Antonelli"}}]}]}]}"""
        val gp = EspnScoreboardParser.parse(body, "F1", FeedFormat.RACING, java.time.ZoneId.of("America/New_York")).single()
        assertEquals(GameState.PRE, gp.state)
        assertEquals("Qual Final · Next: Race Sun 4:00 PM", gp.status)
        assertEquals(listOf("K. Antonelli", "M. Verstappen", "I. Hadjar"), gp.leaders.map { it.name })
        assertEquals(listOf("1", "2", "3"), gp.leaders.map { it.position })
        assertEquals("2026-10-25T20:00Z", gp.startIso)
        assertEquals("United States GP", gp.event)
    }

    @Test
    fun `racing session in progress is live`() {
        val body = """{"events":[{"id":"r1","competitions":[
              {"type":{"abbreviation":"Race"},"status":${status("in", "Lap 30/56")},"competitors":[${athlete(1, "M. Verstappen")}]}]}]}"""
        val gp = EspnScoreboardParser.parse(body, "F1", FeedFormat.RACING).single()
        assertEquals(GameState.LIVE, gp.state)
        assertEquals("Race Lap 30/56", gp.status)
    }

    @Test
    fun `fight card shows live bouts, then main and co-main event`() {
        fun fight(id: String, state: String, a: String, b: String) =
            """{"id":"$id","type":{"abbreviation":"Lightweight"},"status":${status(state, if (state == "post") "Final" else "R2")},
                "competitors":[${athlete(1, a, ",\"winner\":${state == "post"}")},${athlete(2, b)}]}"""
        val body = """{"events":[{"id":"u1","shortName":"UFC 333","competitions":[
              ${fight("f1", "post", "A", "B")},${fight("f2", "in", "C", "D")},${fight("f3", "pre", "E", "F")},${fight("f4", "pre", "Volkanovski", "Evloev")}]}]}"""
        val fights = EspnScoreboardParser.parse(body, "UFC", FeedFormat.MMA)
        assertEquals(listOf("f2", "f4", "f3"), fights.map { it.id })
        assertEquals(GameState.LIVE, fights[0].state)
        assertEquals("Lightweight · R2", fights[0].status)
        assertEquals("UFC 333", fights[1].event)
        assertEquals(TickerKind.HEAD_TO_HEAD, fights[1].kind)
    }

    @Test
    fun `a malformed event never breaks the rest of the feed`() {
        val body = """{"events":[{"id":"x","competitions":"oops"},{"id":"g1","status":${status("in", "")},
            "competitions":[{"competitors":[${athlete(1, "A. Player", ",\"score\":\"E\"")}]}]}]}"""
        assertEquals(1, EspnScoreboardParser.parse(body, "PGA", FeedFormat.GOLF).size)
        assertTrue(EspnScoreboardParser.parse("not json", "ATP", FeedFormat.TENNIS).isEmpty())
    }

    @Test
    fun `upcoming start times are shown in the TV's time zone, other words kept`() {
        fun event(id: String, short: String) =
            """{"id":"$id","date":"2026-10-25T13:30Z","status":${status("pre", short)},"competitions":[{"competitors":[
                {"homeAway":"home","team":{"abbreviation":"NO"}},{"homeAway":"away","team":{"abbreviation":"PIT"}}]}]}"""
        val body = """{"events":[${event("1", "10/25 - 9:30 AM EDT")},${event("2", "Postponed")}]}"""
        val games = EspnScoreboardParser.parse(body, "NFL", zone = java.time.ZoneId.of("Australia/Brisbane"))
        // 13:30 UTC is 11:30 PM in Brisbane (UTC+10).
        assertTrue(games[0].status, games[0].status.contains("11:30"))
        assertFalse(games[0].status.contains("EDT"))
        assertEquals("Postponed", games[1].status)
    }

    // --- selection & polling ---

    private val now = OffsetDateTime.parse("2026-10-25T12:00Z").toInstant().toEpochMilli()

    private fun game(id: String, league: String, state: GameState, start: String?, kind: TickerKind = TickerKind.TEAMS) =
        TickerGame(id, league, TeamLine("A", "1"), TeamLine("B", "2"), state, "", start, null, kind = kind)

    @Test
    fun `old finals and far-off games are dropped`() {
        assertTrue(SportsTickerRepository.isRelevant(game("l", "NFL", GameState.LIVE, "2026-10-20T00:00Z"), now))
        assertTrue(SportsTickerRepository.isRelevant(game("p", "NFL", GameState.PRE, "2026-10-26T20:00Z"), now))
        assertFalse(SportsTickerRepository.isRelevant(game("p", "NFL", GameState.PRE, "2026-10-28T20:00Z"), now))
        assertTrue(SportsTickerRepository.isRelevant(game("f", "NFL", GameState.FINAL, "2026-10-25T00:00Z"), now))
        assertFalse(SportsTickerRepository.isRelevant(game("f", "NFL", GameState.FINAL, "2026-10-24T12:00Z"), now))
        // A golf tournament is dated by its first round, so it stays for a few days after.
        assertTrue(SportsTickerRepository.isRelevant(game("g", "PGA", GameState.FINAL, "2026-10-22T11:00Z", TickerKind.LEADERBOARD), now))
        assertTrue(SportsTickerRepository.isRelevant(game("x", "NFL", GameState.FINAL, null), now))
    }

    @Test
    fun `one league can't flood the ticker and live games lead`() {
        val volleyball = (1..20).map { game("v$it", "NCAAVB", GameState.FINAL, "2026-10-25T0${it % 10}:00Z") }
        val selected = SportsTickerRepository.select(volleyball + game("nfl", "NFL", GameState.LIVE, null), now)
        assertEquals("nfl", selected.first().id)
        assertEquals(SportsTickerRepository.PER_LEAGUE, selected.count { it.league == "NCAAVB" })
        // Newest finals kept.
        assertTrue(selected.filter { it.league == "NCAAVB" }.all { it.startIso!!.startsWith("2026-10-25T09") || it.startIso!!.startsWith("2026-10-25T08") })
    }

    @Test
    fun `polling speeds up only when something is live or about to start`() {
        assertEquals(60_000L, SportsTickerRepository.nextPollMs(listOf(game("l", "NFL", GameState.LIVE, null)), now))
        assertEquals(300_000L, SportsTickerRepository.nextPollMs(listOf(game("p", "NFL", GameState.PRE, "2026-10-25T13:30Z")), now))
        assertEquals(1_800_000L, SportsTickerRepository.nextPollMs(listOf(game("p", "NFL", GameState.PRE, "2026-10-26T13:30Z")), now))
        assertEquals(1_800_000L, SportsTickerRepository.nextPollMs(emptyList(), now))
    }
}
