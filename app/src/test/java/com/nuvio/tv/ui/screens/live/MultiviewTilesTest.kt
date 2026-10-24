package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewTilesTest {

    private fun ch(id: String) = IptvChannel(id = id, sourceId = "s", name = id, group = "g", streamUrl = "http://x/$id.ts")
    private val channels = listOf(ch("a"), ch("b"), ch("c"), ch("d"), ch("e"), ch("f"))

    @Test
    fun `current channel first, then favourites, padded with empty tiles`() {
        val tiles = initialTiles(channels, favorites = setOf("c", "e"), currentId = "b")
        assertEquals(listOf("b", "c", "e", null), tiles.map { it?.id })
    }

    @Test
    fun `current channel that is also a favourite appears once`() {
        val tiles = initialTiles(channels, favorites = setOf("b", "c"), currentId = "b")
        assertEquals(listOf("b", "c", null, null), tiles.map { it?.id })
    }

    @Test
    fun `never more than four tiles`() {
        val tiles = initialTiles(channels, favorites = channels.map { it.id }.toSet(), currentId = null)
        assertEquals(4, tiles.size)
        assertEquals(listOf("a", "b", "c", "d"), tiles.map { it?.id })
    }

    @Test
    fun `nothing to show gives four empty tiles`() {
        assertEquals(List(4) { null }, initialTiles(emptyList(), emptySet(), null))
    }
}
