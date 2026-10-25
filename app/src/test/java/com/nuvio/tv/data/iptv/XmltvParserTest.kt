package com.nuvio.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmltvParserTest {

    // 2026-10-24 10:00:00 UTC
    private val now = 1_792_836_000_000L

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv>
          <channel id="bbc1.uk"><display-name>BBC One HD</display-name></channel>
          <channel id="sky.news"><display-name>Sky News</display-name></channel>
          <channel id="other"><display-name>Other</display-name></channel>
          <programme start="20261024200000 +1000" stop="20261024210000 +1000" channel="bbc1.uk">
            <title lang="en">Evening News</title><desc>Headlines &amp; weather</desc>
          </programme>
          <programme start="20261024090000 +0000" stop="20261024100000 +0000" channel="bbc1.uk">
            <title>Morning Show</title>
          </programme>
          <programme start="20261020090000 +0000" stop="20261020100000 +0000" channel="bbc1.uk">
            <title>Too old</title>
          </programme>
          <programme start="20261024100000" stop="20261024110000" channel="sky.news">
            <title>Sky at Ten</title>
          </programme>
          <programme start="20261024100000" stop="20261024110000" channel="other">
            <title>Not wanted</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `parses time with offsets`() {
        assertEquals(now, XmltvParser.parseXmltvTime("20261024200000 +1000"))
        assertEquals(now, XmltvParser.parseXmltvTime("20261024100000"))
        assertEquals(now, XmltvParser.parseXmltvTime("20261024053000 -0430"))
        assertEquals(now, XmltvParser.parseXmltvTime("202610241000"))
        assertNull(XmltvParser.parseXmltvTime("garbage"))
    }

    @Test
    fun `keeps only wanted channels within the window, sorted`() {
        val result = XmltvParser(now).parse(
            xml.byteInputStream(),
            wantedIds = setOf("bbc1.uk"),
            wantedNames = setOf(XmltvParser.normalizeChannelName("Sky News"))
        )
        val bbc = result.programmes.getValue("bbc1.uk")
        assertEquals(listOf("Morning Show", "Evening News"), bbc.map { it.title })
        assertEquals("Headlines & weather", bbc[1].description)
        assertTrue(result.programmes.containsKey("sky.news"))
        assertFalse(result.programmes.containsKey("other"))
        assertEquals("bbc1.uk", result.channelIdsByName[XmltvParser.normalizeChannelName("BBC One")])
    }

    @Test
    fun `caps programme text length`() {
        val longTitle = "T".repeat(IptvSafety.MAX_TEXT_CHARS * 3)
        val doc = """<tv><programme start="20261024100000" stop="20261024110000" channel="c"><title>$longTitle</title></programme></tv>"""
        val result = XmltvParser(now).parse(doc.byteInputStream())
        assertEquals(IptvSafety.MAX_TEXT_CHARS, result.programmes.getValue("c")[0].title.length)
    }

    @Test
    fun `does not resolve external entities`() {
        val doc = """<?xml version="1.0"?>
            <!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
            <tv><programme start="20261024100000" stop="20261024110000" channel="c"><title>A&xxe;B</title></programme></tv>"""
        val title = runCatching { XmltvParser(now).parse(doc.byteInputStream()).programmes["c"]?.get(0)?.title }.getOrNull()
        // Either the parser refuses the document or the entity expands to nothing.
        assertTrue(title == null || title == "AB")
    }

    @Test
    fun `reads gzip guides`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }
        val result = XmltvParser(now).parse(gz.toByteArray().inputStream())
        assertEquals(3, result.programmes.size)
    }

    @Test
    fun `now and next`() {
        val list = listOf(
            EpgProgramme(now - 1000, now + 1000, "A"),
            EpgProgramme(now + 1000, now + 2000, "B")
        )
        val (current, next) = IptvRepository.nowAndNext(list, now)
        assertEquals("A", current?.title)
        assertEquals("B", next?.title)
        val (c2, n2) = IptvRepository.nowAndNext(list, now - 5000)
        assertNull(c2)
        assertEquals("A", n2?.title)
    }

    @Test
    fun `programmes without stop end at the next start, at most 3 hours`() {
        val guide = """<tv>
          <programme start="20261024090000 +0000" channel="c"><title>A</title></programme>
          <programme start="20261024103000 +0000" channel="c"><title>B</title></programme>
        </tv>"""
        val list = XmltvParser(nowMs = now).parse(guide.byteInputStream()).programmes.getValue("c")
        assertEquals(listOf("A", "B"), list.map { it.title })
        assertEquals(list[1].startMs, list[0].endMs)
        assertEquals(list[1].startMs + 3 * 60 * 60 * 1000L, list[1].endMs)
    }

    @Test
    fun `a broken guide keeps what was read before the error`() {
        val guide = """<tv>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="c"><title>Kept</title></programme>
          <programme start="20261024110000 +0000" stop="20261024120000 +0000" channel="c"><title>Bro&ken"""
        val list = XmltvParser(nowMs = now).parse(guide.byteInputStream()).programmes.getValue("c")
        assertEquals(listOf("Kept"), list.map { it.title })
    }

    @Test
    fun `long descriptions are trimmed`() {
        val guide = """<tv><programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="c">
          <title>T</title><desc>${"x".repeat(5_000)}</desc></programme></tv>"""
        val desc = XmltvParser(nowMs = now).parse(guide.byteInputStream()).programmes.getValue("c").single().description
        assertEquals(280, desc!!.length)
    }
}
