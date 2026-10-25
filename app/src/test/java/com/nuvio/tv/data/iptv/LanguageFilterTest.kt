package com.nuvio.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageFilterTest {

    private val english = LanguageFilter(listOf("en"))

    @Test
    fun `no languages keeps everything`() {
        assertTrue(LanguageFilter.NONE.keep("AR: MBC 1", "ARABIC"))
        assertFalse(LanguageFilter.NONE.isActive)
    }

    @Test
    fun `group code before a separator decides`() {
        assertTrue(english.keep("Sky Sports Main Event", "UK | SPORTS"))
        assertTrue(english.keep("ESPN", "US: Sports"))
        assertFalse(english.keep("beIN Sports 1", "AR | SPORTS"))
        assertFalse(english.keep("TF1", "|FR| GENERAL"))
        assertFalse(english.keep("Das Erste", "DE - Allgemein"))
    }

    @Test
    fun `a code after a prefix tag in the group is found`() {
        assertTrue(english.keep("Sky Sports F1", "VIP | UK SPORTS"))
        assertFalse(english.keep("Rai 1", "4K | IT CHANNELS"))
    }

    @Test
    fun `country and language words in the group count`() {
        assertFalse(english.keep("MBC 1", "ARABIC NEWS"))
        assertFalse(english.keep("Canal+", "France Cinema"))
        assertTrue(english.keep("BBC One", "British TV"))
    }

    @Test
    fun `channel name tag is used when the group has none`() {
        assertTrue(english.keep("UK: Sky Sports News", "SPORTS"))
        assertFalse(english.keep("AR: Al Jazeera", "NEWS"))
        assertFalse(english.keep("Rai Uno [IT]", "Entertainment"))
        assertTrue(english.keep("BBC Two (UK)", "Entertainment"))
    }

    @Test
    fun `untagged channels stay`() {
        assertTrue(english.keep("Discovery", "Documentary"))
        assertTrue(english.keep("Channel 9", M3uParser.UNCATEGORIZED))
    }

    @Test
    fun `lower case words and codes inside names are not tags`() {
        // "It" and "No" are words here, not Italy and Norway.
        assertTrue(english.keep("It Takes Two", "Kids"))
        assertTrue(english.keep("No Signal TV", "Music"))
        assertTrue(english.keep("ESPN", "SPORTS"))
    }

    @Test
    fun `tvg attributes are the last resort`() {
        assertFalse(english.keep("Channel A", "General", tvgLanguage = "French"))
        assertTrue(english.keep("Channel B", "General", tvgLanguage = "English"))
        assertFalse(english.keep("Channel C", "General", tvgCountry = "ES"))
    }

    @Test
    fun `several languages can be kept`() {
        val filter = LanguageFilter(listOf("en", "ar"))
        assertTrue(filter.keep("MBC 1", "AR | GENERAL"))
        assertTrue(filter.keep("BBC One", "UK | GENERAL"))
        assertFalse(filter.keep("TF1", "FR | GENERAL"))
    }

    @Test
    fun `m3u parser skips filtered channels and they don't count towards the limit`() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(3) { append("#EXTINF:-1 group-title=\"AR | NEWS\",Arabic $it\nhttp://host/a$it.ts\n") }
            repeat(2) { append("#EXTINF:-1 group-title=\"UK | NEWS\",English $it\nhttp://host/e$it.ts\n") }
        }
        val playlist = M3uParser("src", maxChannels = 2, filter = english).parse(text.reader())
        assertEquals(listOf("English 0", "English 1"), playlist.channels.map { it.name })
        assertFalse(playlist.truncated)
    }

    @Test
    fun `filtering keeps channel ids stable`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1\" group-title=\"UK | GENERAL\",BBC One\nhttp://host/1.ts\n"
        val all = M3uParser("src").parse(text.reader()).channels.single()
        val filtered = M3uParser("src", filter = english).parse(text.reader()).channels.single()
        assertEquals(all.id, filtered.id)
    }

    @Test
    fun `xtream parser applies the filter`() {
        val source = IptvSource(id = "x", name = "X", type = IptvSourceType.XTREAM, url = "http://h:80", username = "u", password = "p")
        val body = """[{"stream_id":1,"name":"MBC","category_id":"1"},{"stream_id":2,"name":"BBC One","category_id":"2"}]"""
        val channels = XtreamClient.parseLiveStreams(
            body.byteInputStream(), source, mapOf("1" to "AR | GENERAL", "2" to "UK | GENERAL"), filter = english
        )
        assertEquals(listOf("BBC One"), channels.map { it.name })
    }

    @Test
    fun `filter key changes with the languages but content key does not`() {
        val a = IptvSource(id = "x", name = "X", type = IptvSourceType.M3U, url = "http://h/p.m3u")
        val b = a.copy(languages = listOf("en"))
        assertEquals(a.contentKey, b.contentKey)
        assertTrue(a.filterKey != b.filterKey)
    }
}
