package com.nuvio.tv.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture trimmed from ESPN's NFL summary feed (2026-10-25). */
class EspnSummaryParserTest {
    private val fixture = """{"boxscore": {"teams": [{"homeAway": "away", "team": {"abbreviation": "NE", "displayName": "New England Patriots"}, "statistics": [{"name": "firstDowns", "label": "1st Downs", "displayValue": "20"}, {"name": "firstDownsPassing", "label": "Passing 1st downs", "displayValue": "15"}, {"name": "firstDownsRushing", "label": "Rushing 1st downs", "displayValue": "3"}]}, {"homeAway": "home", "team": {"abbreviation": "CHI", "displayName": "Chicago Bears"}, "statistics": [{"name": "firstDowns", "label": "1st Downs", "displayValue": "21"}, {"name": "firstDownsPassing", "label": "Passing 1st downs", "displayValue": "13"}, {"name": "firstDownsRushing", "label": "Rushing 1st downs", "displayValue": "5"}]}]}, "standings": {"groups": [{"header": "2026 AFC East Standings", "standings": {"entries": [{"team": "Buffalo", "stats": [{"name": "losses", "abbreviation": "L", "displayValue": "1"}, {"name": "pointsAgainst", "abbreviation": "PA", "displayValue": "171"}, {"name": "pointsFor", "abbreviation": "PF", "displayValue": "197"}, {"name": "ties", "abbreviation": "T", "displayValue": "0"}, {"name": "winPercent", "abbreviation": "PCT", "displayValue": ".833"}, {"name": "wins", "abbreviation": "W", "displayValue": "5"}, {"name": "overall", "abbreviation": "Any", "displayValue": "5-1"}]}, {"team": "New England", "stats": [{"name": "losses", "abbreviation": "L", "displayValue": "3"}, {"name": "pointsAgainst", "abbreviation": "PA", "displayValue": "143"}, {"name": "pointsFor", "abbreviation": "PF", "displayValue": "160"}, {"name": "ties", "abbreviation": "T", "displayValue": "0"}, {"name": "winPercent", "abbreviation": "PCT", "displayValue": ".571"}, {"name": "wins", "abbreviation": "W", "displayValue": "4"}, {"name": "overall", "abbreviation": "Any", "displayValue": "4-3"}]}]}}]}, "injuries": [{"team": {"abbreviation": "CHI"}, "injuries": [{"status": "Injured Reserve", "athlete": {"displayName": "Anthony Johnson Jr."}, "details": {"type": "Undisclosed"}}]}], "news": {"articles": [{"headline": "NFL Week 7 betting guide: the value bets to make on lines, props and anytime TDs"}, {"headline": "Larry Fitzgerald set to enter Cardinals Ring of Honor"}]}}"""

    @Test
    fun `parses box score, standings, injuries and headlines`() {
        val detail = EspnSummaryParser.parse(fixture)
        assertEquals(3, detail.stats.size)
        assertEquals("1st Downs", detail.stats[0].label)
        assertEquals("20", detail.stats[0].away)
        val table = detail.standings.single()
        assertEquals("2026 AFC East Standings", table.title)
        assertEquals(listOf("W", "T", "L", "PCT", "PF", "PA"), table.columns)
        assertEquals("Buffalo", table.rows[0].team)
        assertEquals("Anthony Johnson Jr.", detail.injuries.single().player)
        assertEquals("NFL Week 7 betting guide: the value bets to make on lines, props and anytime TDs", detail.headlines.first())
    }

    @Test
    fun `garbage gives an empty detail`() {
        assertTrue(EspnSummaryParser.parse("<html>").isEmpty)
        assertTrue(EspnSummaryParser.parse("""{"boxscore":"x","standings":[]}""").isEmpty)
    }
}
