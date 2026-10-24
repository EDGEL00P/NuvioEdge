package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.IptvChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewTilesTest {

    private fun ch(id: String) = IptvChannel(id = id, sourceId = "s", name = id, group = "g", streamUrl = "http://x/$id.ts")
    private val channels = listOf(ch("a"), ch("b"), ch("c"), ch("d"), ch("e"), ch("f"), ch("g"))

    private fun profile(
        ramMb: Long = 4_096,
        lowRam: Boolean = false,
        smallHeap: Boolean = false,
        avc: DecoderCaps? = DecoderCaps(16, 240.0),
        hevc: DecoderCaps? = DecoderCaps(16, 240.0)
    ) = DeviceProfile(
        totalRamMb = ramMb,
        lowRamDevice = lowRam,
        smallHeap = smallHeap,
        decoders = listOfNotNull(avc?.let { "video/avc" to it }, hevc?.let { "video/hevc" to it }).toMap()
    )

    @After
    fun tearDown() = DecoderBudget.resetForTest()

    @Test
    fun `current channel first, then favourites, padded with empty tiles`() {
        val tiles = initialTiles(channels, favorites = setOf("c", "e"), currentId = "b", count = 4)
        assertEquals(listOf("b", "c", "e", null), tiles.map { it?.id })
    }

    @Test
    fun `current channel that is also a favourite appears once`() {
        val tiles = initialTiles(channels, favorites = setOf("b", "c"), currentId = "b", count = 4)
        assertEquals(listOf("b", "c", null, null), tiles.map { it?.id })
    }

    @Test
    fun `never more than six tiles`() {
        val tiles = initialTiles(channels, favorites = channels.map { it.id }.toSet(), currentId = null)
        assertEquals(6, tiles.size)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), tiles.map { it?.id })
    }

    @Test
    fun `nothing to show gives empty tiles`() {
        assertEquals(List(6) { null }, initialTiles(emptyList(), emptySet(), null))
    }

    @Test
    fun `strong box with fast decoders and plenty of RAM gets six`() {
        assertEquals(6, DecoderBudget.tilesFor(profile()))
    }

    @Test
    fun `decoder instance count limits tiles`() {
        assertEquals(3, DecoderBudget.tilesFor(profile(avc = DecoderCaps(3, 240.0))))
        // The scarcer codec wins: any tile may get an HEVC channel.
        assertEquals(2, DecoderBudget.tilesFor(profile(hevc = DecoderCaps(2, 240.0))))
    }

    @Test
    fun `decoder speed limits tiles`() {
        // A decoder that can only do 1080p at 60 fps has room for two 30 fps tiles.
        assertEquals(2, DecoderBudget.tilesFor(profile(avc = DecoderCaps(16, 60.0))))
        // 4K30-class H.264 decoder: 120 fps at 1080p.
        assertEquals(4, DecoderBudget.tilesFor(profile(avc = DecoderCaps(16, 120.0))))
        // No 1080p support at all still allows one tile.
        assertEquals(1, DecoderBudget.tilesFor(profile(avc = DecoderCaps(16, 0.0))))
    }

    @Test
    fun `memory limits tiles on 1 to 2 GB boxes`() {
        assertEquals(2, DecoderBudget.tilesFor(profile(ramMb = 1_024)))
        assertEquals(2, DecoderBudget.tilesFor(profile(ramMb = 4_096, lowRam = true)))
        assertEquals(4, DecoderBudget.tilesFor(profile(ramMb = 2_048)))
        assertEquals(4, DecoderBudget.tilesFor(profile(ramMb = 4_096, smallHeap = true)))
    }

    @Test
    fun `unknown decoders fall back to two`() {
        assertEquals(2, DecoderBudget.tilesFor(profile(avc = null, hevc = null)))
    }

    @Test
    fun `a failure lowers the limit for the session`() {
        assertEquals(4, DecoderBudget.learnFailureAt(6))
        assertEquals(4, DecoderBudget.tilesFor(profile()))
        assertEquals(2, DecoderBudget.learnFailureAt(4))
        assertEquals(1, DecoderBudget.learnFailureAt(2))
        assertEquals(1, DecoderBudget.learnFailureAt(1))
        assertEquals(1, DecoderBudget.tilesFor(profile()))
    }

    @Test
    fun `layout options and grids`() {
        assertEquals(2, DecoderBudget.bestOption(3))
        assertEquals(6, DecoderBudget.bestOption(9))
        assertEquals(1, DecoderBudget.bestOption(0))
        assertEquals(1 to 1, gridFor(1))
        assertEquals(2 to 1, gridFor(2))
        assertEquals(2 to 2, gridFor(4))
        assertEquals(3 to 2, gridFor(6))
        assertEquals(1920 to 1080, maxTileVideoSize(1))
        assertEquals(960 to 540, maxTileVideoSize(6))
    }

    @Test
    fun `tile recovery backs off then fails over`() {
        val r = TileRecovery(failoverAfter = 2)
        assertEquals(TileRecovery.Action.Restart(1_000), r.onProblem(hasBackup = true))
        assertEquals(TileRecovery.Action.Restart(2_000), r.onProblem(hasBackup = true))
        assertEquals(TileRecovery.Action.Failover, r.onProblem(hasBackup = true))
        // Without a backup it keeps retrying, capped at 30 s.
        repeat(10) { r.onProblem(hasBackup = false) }
        assertEquals(TileRecovery.Action.Restart(30_000), r.onProblem(hasBackup = false))
        r.onPlaying()
        assertEquals(TileRecovery.Action.Restart(1_000), r.onProblem(hasBackup = false))
    }

    @Test
    fun `overload needs sustained frame drops`() {
        val m = OverloadMonitor(maxDropShare = 0.25, sustainSeconds = 3)
        assertFalse(m.onSecond(dropped = 10, rendered = 20))
        assertFalse(m.onSecond(dropped = 10, rendered = 20))
        assertFalse(m.onSecond(dropped = 1, rendered = 29)) // a good second resets the count
        assertFalse(m.onSecond(dropped = 10, rendered = 20))
        assertFalse(m.onSecond(dropped = 10, rendered = 20))
        assertTrue(m.onSecond(dropped = 10, rendered = 20))
        assertFalse(OverloadMonitor().onSecond(0, 0))
    }

    @Test
    fun `reconnects are limited per window`() {
        val b = ReconnectBudget(max = 3, windowMs = 300_000)
        assertTrue(b.tryAcquire(0))
        assertTrue(b.tryAcquire(1_000))
        assertTrue(b.tryAcquire(2_000))
        assertFalse(b.tryAcquire(3_000))
        assertTrue(b.tryAcquire(300_000)) // the first one has aged out
        b.reset()
        assertTrue(b.tryAcquire(300_001))
    }
}
