package com.nuvio.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val playlist = """
        #EXTM3U url-tvg="https://guide.example/epg.xml.gz,https://backup.example/epg.xml"
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="https://logo/bbc1.png" tvg-chno="101" group-title="UK, Entertainment",BBC One, London
        #EXTVLCOPT:http-user-agent=VLC/3.0
        #EXTVLCOPT:http-referrer=https://ref.example/
        http://provider.example/live/u/p/1.ts
        #EXTINF:-1,No Attributes
        #EXTGRP:Sports
        http://provider.example/2.ts
        #EXTINF:-1 group-title="News",Dup
        http://provider.example/3.ts
        #EXTINF:-1 group-title="News",Dup
        http://provider.example/4.ts
        http://orphan.example/no-extinf.ts
    """.trimIndent()

    private fun parse() = M3uParser("src").parse(playlist.reader().buffered())

    @Test
    fun `parses attributes, comma-containing names and groups`() {
        val result = parse()
        assertEquals("https://guide.example/epg.xml.gz", result.headerEpgUrl)
        assertEquals(4, result.channels.size)
        val bbc = result.channels[0]
        assertEquals("BBC One, London", bbc.name)
        assertEquals("UK, Entertainment", bbc.group)
        assertEquals("bbc1.uk", bbc.epgId)
        assertEquals("BBC One", bbc.epgName)
        assertEquals(101, bbc.number)
        assertEquals("https://logo/bbc1.png", bbc.logoUrl)
        assertEquals(mapOf("User-Agent" to "VLC/3.0", "Referer" to "https://ref.example/"), bbc.headers)
    }

    @Test
    fun `falls back to EXTGRP and Uncategorized`() {
        val second = parse().channels[1]
        assertEquals("No Attributes", second.name)
        assertEquals("Sports", second.group)
        assertNull(second.epgId)
        assertEquals(emptyMap<String, String>(), second.headers)
    }

    @Test
    fun `ids are stable across parses and unique for duplicates`() {
        val a = parse().channels
        val b = parse().channels
        assertEquals(a.map { it.id }, b.map { it.id })
        assertNotEquals(a[2].id, a[3].id)
        assertEquals(a.size, a.map { it.id }.toSet().size)
    }

    @Test
    fun `extractName ignores commas inside quotes`() {
        assertEquals("Name", M3uParser.extractName("""#EXTINF:-1 group-title="a,b",Name"""))
        assertEquals("", M3uParser.extractName("#EXTINF:-1"))
    }
}
