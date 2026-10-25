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
}
