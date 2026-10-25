package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.sports.GameOdds
import com.nuvio.tv.data.sports.GameState
import com.nuvio.tv.data.sports.TeamLine
import com.nuvio.tv.data.sports.TickerGame
import com.nuvio.tv.ui.components.ticker.tickerLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIndexTest {

    private fun channel(id: String, group: String) =
        IptvChannel(id = id, sourceId = "s", name = id, group = group, streamUrl = "http://h/$id.ts")

    private val channels = listOf(channel("a", "News"), channel("b", "Sports"), channel("c", "News"))

    @Test
    fun `groups keep playlist order, with favourites and all first`() {
        val index = ChannelIndex.build(channels, setOf("c"))
        assertEquals(
            listOf(LiveTvViewModel.GROUP_FAVORITES, LiveTvViewModel.GROUP_ALL, "News", "Sports"),
            index.groups.map { it.key }
        )
        assertEquals(listOf(1, 3, 2, 1), index.groups.map { it.count })
    }

    @Test
    fun `channels of a group come from the index`() {
        val index = ChannelIndex.build(channels, setOf("c"))
        assertEquals(listOf("a", "c"), index.channelsOf("News").map { it.id })
        assertEquals(listOf("c"), index.channelsOf(LiveTvViewModel.GROUP_FAVORITES).map { it.id })
        assertEquals(channels, index.channelsOf(LiveTvViewModel.GROUP_ALL))
        assertTrue(index.channelsOf("Missing").isEmpty())
    }

    @Test
    fun `favourites group exists only with favourites`() {
        val index = ChannelIndex.build(channels, emptySet())
        assertFalse(index.has(LiveTvViewModel.GROUP_FAVORITES))
        assertTrue(index.has(LiveTvViewModel.GROUP_ALL))
        assertTrue(index.has("Sports"))
    }

    @Test
    fun `ticker line shows score, live state and odds in one line`() {
        val game = TickerGame(
            id = "1",
            league = "NFL",
            away = TeamLine("PIT", "21"),
            home = TeamLine("CLE", "17"),
            state = GameState.LIVE,
            status = "Q3 8:42",
            startIso = null,
            odds = GameOdds(line = "PIT -3", total = "45.5", moneylineAway = null, moneylineHome = null, provider = null)
        )
        val text = tickerLine(game, "LIVE", "LINE", "O/U", "H2H").text
        assertEquals("PIT 21  @  CLE 17  ● LIVE  Q3 8:42   LINE PIT -3   O/U 45.5", text)
    }
}
