package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.IptvChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveZapSessionTest {
    private fun ch(id: String, number: Int? = null) =
        IptvChannel(id = id, sourceId = "s", name = "Channel $id", group = "g", streamUrl = "http://x/$id.ts", number = number)

    private val opened = mutableListOf<String>()

    @After
    fun tearDown() {
        LiveZapSession.navigator = null
        LiveZapSession.switcher = null
        LiveZapSession.clear()
    }

    @Test
    fun `number entry counts channels in order when the playlist has no numbers`() {
        val all = listOf(ch("a"), ch("b"), ch("c"))
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.start(all, all[0], emptyMap(), all)
        assertTrue(LiveZapSession.jumpToNumber(2))
        assertEquals(listOf("b"), opened)
        assertFalse(LiveZapSession.jumpToNumber(9))
        assertEquals(9, LiveZapSession.notFound.value)
    }

    @Test
    fun `playlist numbers win when there are any`() {
        val all = listOf(ch("a", 101), ch("b", 7))
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.start(all, all[0], emptyMap(), all)
        assertTrue(LiveZapSession.jumpToNumber(7))
        assertEquals(listOf("b"), opened)
        assertFalse(LiveZapSession.jumpToNumber(1))
    }

    @Test
    fun `a channel from outside the group zaps through all channels`() {
        val all = listOf(ch("a"), ch("b"), ch("c"))
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.start(listOf(all[0]), all[2], emptyMap(), all) // played from search
        assertTrue(LiveZapSession.zap(forward = true))
        assertEquals(listOf("a"), opened) // wraps from c to a
    }

    @Test
    fun `the in-player switcher is preferred over opening a new screen`() {
        val all = listOf(ch("a"), ch("b"))
        val switched = mutableListOf<String>()
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.switcher = { switched += it.id }
        LiveZapSession.start(all, all[0], emptyMap(), all)
        assertTrue(LiveZapSession.zap(forward = true))
        assertEquals(listOf("b"), switched)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `snapshot reports backup failover and clears it after a normal tune`() {
        val primary = ch("primary").copy(name = "News", epgId = "news")
        val backup = ch("backup").copy(name = "News", epgId = "news")
        val all = listOf(primary, backup)
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.start(all, primary, emptyMap(), all)
        LiveZapSession.awaitBackupIndex()

        assertFalse(LiveZapSession.snapshot.value!!.failoverActive)
        assertTrue(LiveZapSession.failover())
        assertTrue(LiveZapSession.snapshot.value!!.failoverActive)
        assertTrue(LiveZapSession.tune(primary))
        assertFalse(LiveZapSession.snapshot.value!!.failoverActive)
    }

    @Test
    fun `non-wrapping zap returns false at list boundaries`() {
        val all = listOf(ch("a"), ch("b"))
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.start(all, all.last(), emptyMap(), all, wrapAtEnds = false)

        assertFalse(LiveZapSession.zap(forward = true))
        assertTrue(opened.isEmpty())
        assertTrue(LiveZapSession.zap(forward = false))
        assertEquals(listOf("a"), opened)
        assertFalse(LiveZapSession.zap(forward = false))
    }

    @Test
    fun `single channel list reports no channel to zap to`() {
        val only = ch("only")
        LiveZapSession.navigator = { opened += it.id }
        LiveZapSession.start(listOf(only), only, emptyMap(), listOf(only))

        assertFalse(LiveZapSession.zap(forward = true))
        assertFalse(LiveZapSession.zap(forward = false))
        assertTrue(opened.isEmpty())
    }
}
