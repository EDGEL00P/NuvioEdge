package com.nuvio.tv.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamClientTest {

    private val source = IptvSource(
        id = "xc",
        name = "XC",
        type = IptvSourceType.XTREAM,
        url = "provider.example:8080/",
        username = "user name",
        password = "p@ss/word"
    )

    @Test
    fun `normalizes pasted server addresses`() {
        assertEquals("http://provider.example:8080", XtreamClient.normalizeServer("provider.example:8080/"))
        assertEquals(
            "https://p.example",
            XtreamClient.normalizeServer("https://p.example/player_api.php?username=a&password=b")
        )
        assertEquals("http://p.example:80", XtreamClient.normalizeServer("http://p.example:80/get.php?x=1"))
    }

    @Test
    fun `builds encoded api and stream urls`() {
        assertEquals(
            "http://provider.example:8080/player_api.php?username=user%20name&password=p%40ss%2Fword&action=get_live_streams",
            XtreamClient.apiUrl(source, "get_live_streams").toString()
        )
        assertEquals(
            "http://provider.example:8080/live/user%20name/p@ss%2Fword/42.ts",
            XtreamClient.liveStreamUrl(source, "42")
        )
    }

    @Test
    fun `parses account and rejects bad logins`() {
        val account = XtreamClient.parseAccount(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000","max_connections":"2"}}"""
        )
        assertEquals("Active", account.status)
        assertEquals(1_800_000_000_000L, account.expiresAtMs)
        assertEquals(2, account.maxConnections)
        assertThrows(IptvException::class.java) { XtreamClient.parseAccount("""{"user_info":{"auth":0}}""") }
        assertThrows(IptvException::class.java) { XtreamClient.parseAccount("<html>nope</html>") }
    }

    @Test
    fun `parses streams with mixed field types`() {
        val categories = XtreamClient.parseCategories("""[{"category_id":"5","category_name":"Sports"}]""")
        val channels = XtreamClient.parseLiveStreams(
            """[
              {"num":1,"name":"ESPN","stream_id":101,"stream_icon":"http://i/espn.png","epg_channel_id":"espn.us","category_id":"5"},
              {"num":"2","name":"No Cat","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":null},
              {"name":"Missing id"}
            ]""",
            source,
            categories
        )
        assertEquals(2, channels.size)
        assertEquals("xc::101", channels[0].id)
        assertEquals("Sports", channels[0].group)
        assertEquals("espn.us", channels[0].epgId)
        assertEquals(1, channels[0].number)
        assertEquals(M3uParser.UNCATEGORIZED, channels[1].group)
        assertEquals(null, channels[1].logoUrl)
        assertEquals(null, channels[1].epgId)
    }

    @Test
    fun `streams are read one by one, capped, and a cut-off list keeps what arrived`() {
        val full = (1..5).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Ch $it"}""" }
        assertEquals(3, XtreamClient.parseLiveStreams(full.byteInputStream(), source, emptyMap(), maxChannels = 3).size)
        val cutOff = """[{"stream_id":1,"name":"A"},{"stream_id":2,"name":"B"},{"stream_id":3,"na"""
        assertEquals(listOf("A", "B"), XtreamClient.parseLiveStreams(cutOff.byteInputStream(), source, emptyMap()).map { it.name })
        org.junit.Assert.assertThrows(IptvException::class.java) {
            XtreamClient.parseLiveStreams("""{"user_info":{"auth":0}}""".byteInputStream(), source, emptyMap())
        }
    }

    @Test
    fun `HLS-only accounts get m3u8 stream urls`() {
        val hlsOnly = XtreamClient.parseAccount("""{"user_info":{"auth":1,"allowed_output_formats":["m3u8"]}}""")
        assertEquals("m3u8", hlsOnly.liveExtension)
        assertEquals("ts", XtreamClient.parseAccount("""{"user_info":{"auth":1,"allowed_output_formats":["m3u8","ts"]}}""").liveExtension)
        assertEquals("ts", XtreamClient.parseAccount("""{"user_info":{"auth":1}}""").liveExtension)
        assertTrue(XtreamClient.liveStreamUrl(source, "5", "m3u8").endsWith("/5.m3u8"))
    }

    @Test
    fun `reads archive days and panel timezone`() {
        val channels = XtreamClient.parseLiveStreams(
            """[
              {"stream_id":1,"name":"A","tv_archive":1,"tv_archive_duration":"7"},
              {"stream_id":2,"name":"B","tv_archive":"1","tv_archive_duration":3},
              {"stream_id":3,"name":"C","tv_archive":0,"tv_archive_duration":7},
              {"stream_id":4,"name":"D"},
              {"stream_id":5,"name":"E","tv_archive":1,"tv_archive_duration":"999"}
            ]""",
            source,
            emptyMap()
        )
        assertEquals(listOf(7, 3, 0, 0, 31), channels.map { it.catchupDays })

        val account = XtreamClient.parseAccount(
            """{"user_info":{"auth":1},"server_info":{"timezone":"Europe/London"}}"""
        )
        assertEquals(java.time.ZoneId.of("Europe/London"), account.serverZone)
        assertEquals(null, XtreamClient.parseAccount("""{"user_info":{"auth":1},"server_info":{"timezone":"Not/AZone"}}""").serverZone)
        assertEquals(null, XtreamClient.parseAccount("""{"user_info":{"auth":1}}""").serverZone)
    }

    @Test
    fun `catch-up url uses the panel's local start time and encoded login`() {
        // 2026-07-01 18:30 UTC is 19:30 in London (BST).
        val startMs = java.time.Instant.parse("2026-07-01T18:30:00Z").toEpochMilli()
        assertEquals(
            "http://provider.example:8080/timeshift/user%20name/p@ss%2Fword/60/2026-07-01:19-30/42.ts",
            XtreamClient.catchupUrl(source, "42", startMs, 60, java.time.ZoneId.of("Europe/London"))
        )
        assertTrue(
            XtreamClient.catchupUrl(source, "42", startMs, 0, java.time.ZoneOffset.UTC, "m3u8")
                .endsWith("/1/2026-07-01:18-30/42.m3u8")
        )
        assertEquals(
            "http://provider.example:8080/timeshift/redacted/redacted/60/2026-07-01:19-30/42.ts",
            IptvSafety.redactLogin(XtreamClient.catchupUrl(source, "42", startMs, 60, java.time.ZoneId.of("Europe/London")))
        )
    }

    @Test
    fun `catch-up is offered only inside the archive window`() {
        val now = 1_792_836_000_000L
        val hour = 3_600_000L
        val channel = IptvChannel(id = "xc::42", sourceId = "xc", name = "A", group = "", streamUrl = "u", catchupDays = 2)
        fun prog(start: Long, end: Long) = EpgProgramme(start, end, "P")

        assertTrue(IptvRepository.isCatchupAvailable(channel, prog(now - hour, now + hour), now))
        assertTrue(IptvRepository.isCatchupAvailable(channel, prog(now - 48 * hour, now - 47 * hour), now))
        org.junit.Assert.assertFalse(IptvRepository.isCatchupAvailable(channel, prog(now - 48 * hour - 1, now - 47 * hour), now))
        org.junit.Assert.assertFalse(IptvRepository.isCatchupAvailable(channel, prog(now + 1, now + hour), now))
        org.junit.Assert.assertFalse(IptvRepository.isCatchupAvailable(channel.copy(catchupDays = 0), prog(now - hour, now), now))
        org.junit.Assert.assertFalse(IptvRepository.isCatchupAvailable(channel, prog(now - hour, now - hour), now))
    }
}
