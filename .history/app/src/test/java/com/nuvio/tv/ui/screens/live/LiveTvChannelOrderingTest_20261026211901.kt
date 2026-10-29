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
}