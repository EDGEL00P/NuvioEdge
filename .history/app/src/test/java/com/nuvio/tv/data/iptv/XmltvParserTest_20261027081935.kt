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
            wantedNames = setOf(
                XmltvParser.normalizeChannelName("Sky News"),
                XmltvParser.normalizeChannelName("BBC One")
            )
        )
        val bbc = result.programmes.getValue("bbc1.uk")
        assertEquals(listOf("Morning Show", "Evening News"), bbc.map { it.title })
        assertEquals("Headlines & weather", bbc[1].description)
        assertTrue(result.programmes.containsKey("sky.news"))
        assertFalse(result.programmes.containsKey("other"))
        assertEquals(setOf("bbc1.uk"), result.channelIdsByName[XmltvParser.normalizeChannelName("BBC One")])
    }

    @Test
    fun `exact guide id wins and unmatched id falls back to one name candidate`() {
        val guide = """<tv>
          <channel id="correct"><display-name>BBC One</display-name></channel>
          <channel id="other"><display-name>Other</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="correct"><title>BBC</title></programme>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="other"><title>Other</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(
            guide.byteInputStream(),
            wantedIds = setOf("wrong-id", "correct"),
            wantedNames = setOf("bbcone")
        )

        assertEquals("correct", result.resolveGuideId("correct", listOf("Other")))
        assertEquals("correct", result.resolveGuideId("wrong-id", listOf("BBC One")))
        assertEquals(EpgMatchKind.EXACT_ID, result.resolveGuide("correct", listOf("Other")).kind)
        assertEquals(EpgMatchKind.NAME, result.resolveGuide("wrong-id", listOf("BBC One")).kind)
    }

    @Test
    fun `unrelated numeric provider id falls back to a unique channel name`() {
        val guide = """<tv>
          <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="bbc1.uk"><title>BBC</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(
            guide.byteInputStream(),
            wantedIds = setOf("987654"),
            wantedNames = setOf("bbcone")
        )

        assertEquals("bbc1.uk", result.resolveGuideId("987654", listOf("BBC One")))
    }

      @Test
      fun `manual guide override is explicit and invalid override does not auto match`() {
        val guide = """<tv>
          <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
          <channel id="bbc2.uk"><display-name>BBC One</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="bbc1.uk"><title>BBC One</title></programme>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="bbc2.uk"><title>BBC Two</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(
          guide.byteInputStream(),
          wantedIds = setOf("bbc2.uk"),
          wantedNames = setOf("bbcone")
        )

        val selected = result.resolveGuide("bbc2.uk", listOf("BBC One"), manualOverride = true)
        assertEquals("bbc2.uk", selected.guideId)
        assertEquals(EpgMatchKind.MANUAL, selected.kind)
        assertNull(result.resolveGuide("missing-manual-id", listOf("BBC One"), manualOverride = true).guideId)
      }

    @Test
    fun `ambiguous normalized names are not auto matched`() {
        val guide = """<tv>
          <channel id="news.uk"><display-name>News</display-name></channel>
          <channel id="news.hk"><display-name>News</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="news.uk"><title>UK</title></programme>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="news.hk"><title>HK</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(guide.byteInputStream(), wantedNames = setOf("news"))

        assertEquals(setOf("news.uk", "news.hk"), result.channelIdsByName["news"])
        assertNull(result.resolveGuideId(null, listOf("News")))
        assertEquals(EpgMatchKind.AMBIGUOUS, result.resolveGuide(null, listOf("News")).kind)
    }

      @Test
      fun `name collision stays ambiguous when only one channel has an in-window programme`() {
        val guide = """<tv>
          <channel id="news.uk"><display-name>News</display-name></channel>
          <channel id="news.hk"><display-name>News</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="news.uk"><title>UK</title></programme>
          <programme start="20261020090000 +0000" stop="20261020110000 +0000" channel="news.hk"><title>HK</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(guide.byteInputStream(), wantedNames = setOf("news"))

        assertTrue(result.programmes.containsKey("news.uk"))
        assertFalse(result.programmes.containsKey("news.hk"))
        assertEquals(setOf("news.uk", "news.hk"), result.channelIdsByName["news"])
        assertNull(result.resolveGuideId(null, listOf("News")))
      }

    @Test
    fun `declared exact id without an in-window programme does not fall through to another channel`() {
        val guide = """<tv>
          <channel id="exact"><display-name>BBC One</display-name></channel>
          <channel id="other"><display-name>BBC One</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="other"><title>Other</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(guide.byteInputStream(), wantedIds = setOf("exact"), wantedNames = setOf("bbcone"))

        assertNull(result.resolveGuideId("exact", listOf("BBC One")))
    }

    @Test
    fun `ambiguous stronger name does not fall back to weaker channel name`() {
        val guide = """<tv>
          <channel id="news.uk"><display-name>Provider News</display-name></channel>
          <channel id="news.hk"><display-name>Provider News</display-name></channel>
          <channel id="news.us"><display-name>Channel News</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="news.uk"><title>UK</title></programme>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="news.hk"><title>HK</title></programme>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="news.us"><title>US</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(guide.byteInputStream(), wantedNames = setOf("providernews", "channelnews"))

        assertNull(result.resolveGuideId(null, listOf("Provider News", "Channel News")))
    }

    @Test
    fun `unmatched tvg name falls back to channel display name`() {
        val guide = """<tv>
          <channel id="bbc"><display-name>BBC One</display-name></channel>
          <programme start="20261024090000 +0000" stop="20261024110000 +0000" channel="bbc"><title>BBC</title></programme>
        </tv>"""
        val result = XmltvParser(now).parse(guide.byteInputStream(), wantedNames = setOf("providername", "bbcone"))

        assertEquals("bbc", result.resolveGuideId(null, listOf("Provider Name", "BBC One")))
    }

    @Test
    fun `name normalization removes quality tags but preserves region labels`() {
        assertEquals(XmltvParser.normalizeChannelName("BBC One"), XmltvParser.normalizeChannelName("BBC One HD"))
        assertEquals(XmltvParser.normalizeChannelName("BBC One"), XmltvParser.normalizeChannelName("BBC One [HD]"))
        assertTrue(XmltvParser.normalizeChannelName("BBC One UK") != XmltvParser.normalizeChannelName("BBC One US"))
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
