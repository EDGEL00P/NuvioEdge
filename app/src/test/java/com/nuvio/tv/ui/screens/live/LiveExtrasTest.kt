package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExtrasTest {

    private fun ch(id: String, name: String, epgId: String? = null, url: String = "http://x/$id.ts", number: Int? = null) =
        IptvChannel(id = id, sourceId = "s", name = name, group = "g", streamUrl = url, epgId = epgId, number = number)

    @Test
    fun `backup copies match guide id or normalised name, never itself`() {
        val main = ch("1", "Fox Footy HD", epgId = "foxfooty.au")
        val all = listOf(
            main,
            ch("2", "Fox Footy FHD", epgId = null),           // same name after normalising
            ch("3", "FOX FOOTY", epgId = "FOXFOOTY.AU"),      // same guide id
            ch("4", "Fox League", epgId = "foxleague.au"),    // different channel
            ch("5", "Fox Footy HD", url = main.streamUrl)     // same stream, not a backup
        )
        assertEquals(listOf("2", "3"), backupCopies(main, all).map { it.id })
    }

    @Test
    fun `search finds channel names, then airing now, then upcoming`() {
        val now = 1_000_000L
        val a = ch("a", "NFL Network")
        val b = ch("b", "Fox Sports")
        val c = ch("c", "Kayo")
        val guide = mapOf(
            "b" to listOf(EpgProgramme(now + 3_600_000, now + 7_200_000, "NFL: Bears at Packers")),
            "c" to listOf(
                EpgProgramme(now - 600_000, now + 600_000, "NFL RedZone"),
                EpgProgramme(now - 7_200_000, now - 3_600_000, "NFL Classic (finished)")
            )
        )
        val hits = searchLive("nfl", listOf(a, b, c), guide, now)
        assertEquals(listOf("a", "c", "b"), hits.map { it.channel.id })
        assertTrue(hits[1].airingNow)
        assertEquals("NFL RedZone", hits[1].programme?.title)
    }

    @Test
    fun `search needs two characters`() {
        assertTrue(searchLive("n", listOf(ch("a", "NFL")), emptyMap(), 0).isEmpty())
    }

    @Test
    fun `number entry and last channel move through the session`() {
        val channels = listOf(ch("a", "A", number = 1), ch("b", "B", number = 2), ch("c", "C", number = 103))
        val opened = mutableListOf<String>()
        LiveZapSession.start(channels, channels[0], emptyMap(), channels)
        LiveZapSession.navigator = { opened += it.id }
        try {
            assertTrue(LiveZapSession.jumpToNumber(103))
            assertTrue(LiveZapSession.lastChannel())
            assertEquals(listOf("c", "a"), opened)
            assertEquals(false, LiveZapSession.jumpToNumber(999))
        } finally {
            LiveZapSession.navigator = null
            LiveZapSession.clear()
        }
    }

    @Test
    fun `failover tries each backup once`() {
        val main = ch("1", "Fox Footy HD")
        val all = listOf(main, ch("2", "Fox Footy FHD"), ch("3", "Fox Footy SD"))
        val opened = mutableListOf<String>()
        LiveZapSession.start(all, main, emptyMap(), all)
        LiveZapSession.navigator = { opened += it.id }
        try {
            assertTrue(LiveZapSession.failover())
            assertTrue(LiveZapSession.failover())
            assertEquals(false, LiveZapSession.failover())
            assertEquals(listOf("2", "3"), opened)
        } finally {
            LiveZapSession.navigator = null
            LiveZapSession.clear()
        }
    }

    @Test
    fun `backup index matches the one-off lookup and knows its list`() {
        val all = listOf(
            ch("1", "Fox Footy HD"), ch("2", "FOX FOOTY"), ch("3", "Kayo 1"), ch("4", "fox footy (AU)")
        )
        val index = BackupIndex(all)
        assertEquals(listOf("2", "4"), index.copiesOf(all[0]).map { it.id })
        assertEquals(backupCopies(all[0], all).map { it.id }, index.copiesOf(all[0]).map { it.id })
        assertTrue(index.copiesOf(all[2]).isEmpty())
        assertTrue(index.isFor(all))
        assertFalse(index.isFor(all.toList()))
    }
}
