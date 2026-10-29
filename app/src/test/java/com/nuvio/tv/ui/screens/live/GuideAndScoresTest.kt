package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.TeamLine
import com.nuvio.tv.data.sports.TickerGame
import com.nuvio.tv.data.sports.TickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideAndScoresTest {
    private val min = 60_000L
    private val t0 = 1_792_850_400_000L // 2026-10-25 13:30 UTC, on a half hour

    private fun prog(startMin: Int, endMin: Int, title: String = "P$startMin") =
        EpgProgramme(t0 + startMin * min, t0 + endMin * min, title, null)

    @Test
    fun `window starts on the half hour`() {
        assertEquals(t0, windowStartFor(t0 + 17 * min))
        assertEquals(t0, windowStartFor(t0))
    }

    @Test
    fun `cells are clipped to the window and gaps are filled`() {
        val cells = guideCells(listOf(prog(-30, 30), prog(60, 90), prog(170, 300)), t0, t0 + 180 * min)
        assertEquals(
            listOf(0 to 30, 30 to 60, 60 to 90, 90 to 170, 170 to 180),
            cells.map { ((it.startMs - t0) / min).toInt() to ((it.endMs - t0) / min).toInt() }
        )
        assertEquals(listOf("P-30", null, "P60", null, "P170"), cells.map { it.programme?.title })
    }

    @Test
    fun `overlapping and tiny entries never overlap cells`() {
        val cells = guideCells(listOf(prog(0, 60), prog(30, 90), prog(90, 90)), t0, t0 + 180 * min)
        cells.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        assertEquals(t0 + 180 * min, cells.last().endMs)
        assertEquals(listOf(t0 to t0 + 180 * min), guideCells(null, t0, t0 + 180 * min).map { it.startMs to it.endMs })
    }

    private fun ch(id: String) = IptvChannel(id = id, sourceId = "s", name = id, group = "g", streamUrl = "http://x/$id.ts")

    private val steelers = TeamLine("PIT", "21", fullName = "Pittsburgh Steelers", shortName = "Steelers")
    private val saints = TeamLine("NO", "17", fullName = "New Orleans Saints", shortName = "Saints")

    @Test
    fun `team and player names used for guide matching`() {
        assertEquals(listOf("steelers", "pittsburgh"), sideNames(steelers))
        assertEquals(listOf("sinner"), sideNames(TeamLine("J. Sinner", null)))
    }

    @Test
    fun `channels showing the game rank both teams first and ignore partial words`() {
        val game = TickerGame("1", "NFL", steelers, saints, GameState.LIVE, "Q2", null, null)
        val guide = mapOf(
            "fox" to listOf(prog(-10, 170, "NFL: Steelers at Saints")),
            "local" to listOf(prog(-10, 170, "Saints Pregame")),
            "news" to listOf(prog(-10, 170, "Saintsational Recipes")),
            "late" to listOf(prog(240, 400, "Steelers Saints replay"))
        )
        val found = channelsShowing(game, listOf(ch("news"), ch("local"), ch("fox"), ch("late")), guide, nowMs = t0)
        assertEquals(listOf("fox", "local"), found.map { it.channel.id })
        assertEquals(2, found.first().score)
    }

    @Test
    fun `matchup text per kind`() {
        val pre = TickerGame("1", "NFL", steelers, saints, GameState.PRE, "", null, null)
        assertEquals("PIT @ NO", matchupText(pre))
        assertEquals("PIT 21 @ NO 17", matchupText(pre.copy(state = GameState.LIVE)))
        assertEquals("J. Sinner vs C. Alcaraz", matchupText(pre.copy(kind = TickerKind.HEAD_TO_HEAD, away = TeamLine("J. Sinner", null), home = TeamLine("C. Alcaraz", null))))
    }

    @Test
    fun `picker filters by group and name`() {
        val all = listOf(
            IptvChannel(id = "1", sourceId = "s", name = "Fox Sports 1", group = "Sport", streamUrl = "http://x/1.ts"),
            IptvChannel(id = "2", sourceId = "s", name = "BBC One", group = "UK", streamUrl = "http://x/2.ts"),
            IptvChannel(id = "3", sourceId = "s", name = "fox news", group = "News", streamUrl = "http://x/3.ts")
        )
        assertEquals(listOf("1", "3"), filterChannels(all, " FOX ", null).map { it.id })
        assertEquals(listOf("1"), filterChannels(all, "fox", "Sport").map { it.id })
        assertEquals(listOf("2"), filterChannels(all, "", "UK").map { it.id })
    }

    @Test
    fun `fuzzy review ranking includes later aliases but never changes automatic resolution`() {
        val candidate = com.nuvio.tv.data.iptv.EpgGuideCandidate("bbc", listOf("Unrelated", "Other", "BBC One"))
        assertTrue(candidateSimilarity("BBC On", candidate)!! >= 70)
        val matcher = com.nuvio.tv.data.iptv.EpgMatcher(setOf("bbc"), mapOf("bbcone" to setOf("bbc")))
        assertEquals(com.nuvio.tv.data.iptv.EpgMatchKind.UNMATCHED, matcher.resolve(null, listOf("BBC On")).kind)
    }
}
