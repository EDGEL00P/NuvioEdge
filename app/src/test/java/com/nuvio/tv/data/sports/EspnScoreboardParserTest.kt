package com.nuvio.tv.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnScoreboardParserTest {

    // Trimmed from the real NFL scoreboard response fetched on 2026-10-25.
    private val fixture = """{"leagues":[{"abbreviation":"NFL"}],"events":[{"id":"401873011","date":"2026-10-25T13:30Z","status":{"type":{"state":"pre","shortDetail":"10/25 - 9:30 AM EDT"}},"competitions":[{"competitors":[{"homeAway":"home","score":"0","team":{"abbreviation":"NO"}},{"homeAway":"away","score":"0","team":{"abbreviation":"PIT"}}],"odds":[{"details":"PIT -3","overUnder":44.5,"provider":{"name":"DraftKings"},"moneyline":{"home":{"close":{"odds":"+136"}},"away":{"close":{"odds":"-162"}}}}]}]},{"id":"401873010","date":"2026-10-23T00:15Z","status":{"type":{"state":"post","shortDetail":"Final"}},"competitions":[{"competitors":[{"homeAway":"home","score":"30","team":{"abbreviation":"CHI"}},{"homeAway":"away","score":"24","team":{"abbreviation":"NE"}}]}]}]}"""

    @Test
    fun `parses teams, state, status and odds`() {
        val games = EspnScoreboardParser.parse(fixture, "NFL", zone = java.time.ZoneId.of("America/New_York"))
        assertEquals(2, games.size)
        val pre = games[0]
        assertEquals("NFL", pre.league)
        assertEquals("PIT", pre.away.abbreviation)
        assertEquals("NO", pre.home.abbreviation)
        assertEquals(GameState.PRE, pre.state)
        assertEquals("Sun 9:30 AM", pre.status) // start time shown in the TV's zone
        val odds = pre.odds!!
        assertEquals("PIT -3", odds.line)
        assertEquals("44.5", odds.total)
        assertEquals("-162", odds.moneylineAway)
        assertEquals("+136", odds.moneylineHome)
        assertEquals("DraftKings", odds.provider)

        val final = games[1]
        assertEquals(GameState.FINAL, final.state)
        assertEquals("24", final.away.score)
        assertEquals("30", final.home.score)
        assertNull(final.odds)
    }

    @Test
    fun `bad or empty input never throws`() {
        assertTrue(EspnScoreboardParser.parse("<html>blocked</html>", "NFL").isEmpty())
        assertTrue(EspnScoreboardParser.parse("""{"events":[{"id":"1"}]}""", "NFL").isEmpty())
        assertTrue(EspnScoreboardParser.parse("", "NFL").isEmpty())
    }

    @Test
    fun `live games come first, then upcoming, then finals`() {
        fun game(id: String, state: GameState) =
            TickerGame(id, "NFL", TeamLine("A", "1"), TeamLine("B", "2"), state, "", null, null)
        val ordered = SportsTickerRepository.order(
            listOf(game("f", GameState.FINAL), game("p", GameState.PRE), game("l", GameState.LIVE), game("p2", GameState.PRE))
        )
        assertEquals(listOf("l", "p", "p2", "f"), ordered.map { it.id })
    }
}
