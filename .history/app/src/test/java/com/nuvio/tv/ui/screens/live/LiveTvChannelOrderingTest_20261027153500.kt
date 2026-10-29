package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.local.LiveTvChannelSort
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvChannelOrderingTest {
    private fun channel(id: String, name: String, number: Int?) = IptvChannel(
        id = id,
        sourceId = "source",
        name = name,
        group = "group",
        streamUrl = "https://example.invalid/$id",
        number = number
    )

    @Test
    fun `number sort puts absent numbers last and keeps duplicate numbers in provider order`() {
        val providerOrder = listOf(
            channel("a", "Alpha", null),
            channel("b", "Beta", 5),
            channel("c", "Gamma", 5),
            channel("d", "Delta", 2)
        )

        assertEquals(
            listOf("d", "b", "c", "a"),
            sortLiveChannels(providerOrder, LiveTvChannelSort.NUMBER).map { it.id }
        )
    }

    @Test
    fun `name sort ignores case while preserving provider order for equal names`() {
        val providerOrder = listOf(
            channel("a", "zulu", 1),
            channel("b", "ALPHA", 2),
            channel("c", "alpha", 3)
        )

        assertEquals(
            listOf("b", "c", "a"),
            sortLiveChannels(providerOrder, LiveTvChannelSort.NAME).map { it.id }
        )
        assertEquals(providerOrder, sortLiveChannels(providerOrder, LiveTvChannelSort.PROVIDER_ORDER))
    }

    @Test
    fun `visible channel selection preserves order for groups and favorites`() {
        val ordered = listOf(
            channel("a", "Alpha", 1).copy(group = "News"),
            channel("b", "Beta", 2).copy(group = "Sports"),
            channel("c", "Gamma", 3).copy(group = "News")
        )

        assertEquals(ordered, visibleLiveChannels(ordered, setOf("c"), LiveTvViewModel.GROUP_ALL))
        assertEquals(listOf("c"), visibleLiveChannels(ordered, setOf("c"), LiveTvViewModel.GROUP_FAVORITES).map { it.id })
        assertEquals(listOf("a", "c"), visibleLiveChannels(ordered, emptySet(), "News").map { it.id })
    }
}