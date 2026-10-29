package com.nuvio.tv.data.iptv

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class IptvRepositorySourceEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val existing = IptvSource(
        id = "stable-source",
        name = "Old name",
        type = IptvSourceType.M3U,
        url = "https://old.example/list.m3u"
    )
    private var storedSources = listOf(existing)
    private val store = mockk<IptvSourcesStore>()

    init {
        every { store.sources } returns flowOf(storedSources)
        every { store.favorites } returns flowOf(emptySet())
        coEvery { store.current() } coAnswers { storedSources }
        coEvery { store.update(any()) } coAnswers {
            storedSources = firstArg<(List<IptvSource>) -> List<IptvSource>>().invoke(storedSources)
        }
        coEvery { store.removeFavoritesOf(any()) } coAnswers { Unit }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repository(): IptvRepository {
        val context = mockk<Context> {
            every { cacheDir } returns tmp.newFolder("cache")
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = IptvVault(SoftwareAesGcmKey(key), tmp.newFolder("keys"))
        return IptvRepository(context, store, vault, OkHttpClient())
    }

    @Test
    fun `failed edit leaves existing source unchanged`() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val repo = repository()

        val failure = runCatching {
            repo.updateSource(
                id = existing.id,
                name = "Changed name",
                type = existing.type,
                url = server.url("/changed.m3u").toString(),
                username = "",
                password = "",
                epgUrl = ""
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(listOf(existing), storedSources)
        coVerify(exactly = 0) { store.update(any()) }
    }

    @Test
    fun `successful edit retains stable source id`() = kotlinx.coroutines.test.runTest {
        server.enqueue(
            MockResponse().setBody(
                "#EXTM3U\n#EXTINF:-1,Updated channel\nhttps://stream.example/live.m3u8\n"
            )
        )
        val repo = repository()

        val updated = repo.updateSource(
            id = existing.id,
            name = "Updated name",
            type = existing.type,
            url = server.url("/changed.m3u").toString(),
            username = "",
            password = "",
            epgUrl = ""
        )

        assertEquals(existing.id, updated.id)
        assertEquals("Updated name", storedSources.single().name)
        assertEquals(existing.id, storedSources.single().id)
        assertTrue(storedSources.single().url.endsWith("/changed.m3u"))
        coVerify(exactly = 1) { store.update(any()) }
    }

    @Test
    fun `manual EPG mapping accepts a declared candidate and rejects an arbitrary id`() = kotlinx.coroutines.test.runTest {
        val source = existing.copy(
            url = server.url("/playlist.m3u").toString(),
            epgUrl = server.url("/guide.xml").toString()
        )
        storedSources = listOf(source)
        server.enqueue(
            MockResponse().setBody(
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"unrelated\" tvg-name=\"Provider Name\",Channel Name\nhttps://stream.example/live.ts\n"
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """<tv>
                                    <channel id="guide.alpha"><display-name>BBC One UK</display-name></channel>
                                    <channel id="guide.beta"><display-name>BBC One Hong Kong</display-name></channel>
                </tv>"""
            )
        )
        val repo = repository()
        val channelId = M3uParser(source.id).parse(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"unrelated\" tvg-name=\"Provider Name\",Channel Name\nhttps://stream.example/live.ts\n".reader()
        ).channels.single().id

        val rejected = runCatching {
            repo.setManualEpgMapping(source.id, channelId, "not-in-guide")
        }.exceptionOrNull()
        assertNotNull(rejected)
        assertTrue(storedSources.single().manualEpgMappings.isEmpty())
        coVerify(exactly = 0) { store.update(any()) }

        repo.setManualEpgMapping(source.id, channelId, "guide.alpha")

        assertEquals("guide.alpha", storedSources.single().manualEpgMappings[channelId])
        coVerify(exactly = 1) { store.update(any()) }
    }

    @Test
    fun `removing a source deletes its manual guide mappings and preserves other sources`() = kotlinx.coroutines.test.runTest {
        val removed = existing.copy(manualEpgMappings = mapOf("${existing.id}::1" to "removed.guide.id"))
        val retained = existing.copy(
            id = "retained-source",
            name = "Retained",
            manualEpgMappings = mapOf("retained-source::1" to "retained.guide.id")
        )
        storedSources = listOf(removed, retained)

        repository().removeSource(removed.id)

        assertEquals(listOf(retained), storedSources)
        coVerify(exactly = 1) { store.removeFavoritesOf(removed.id) }
    }

    @Test
    fun `cached guide parse is not reused for a different channel set`() = kotlinx.coroutines.test.runTest {
        val source = existing.copy(epgUrl = server.url("/guide.xml").toString())
        storedSources = listOf(source)
        val now = System.currentTimeMillis()
        fun xmltvTime(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))
        server.enqueue(
            MockResponse().setBody(
                """<tv>
                  <channel id="alpha.id"><display-name>Alpha</display-name></channel>
                  <channel id="beta.id"><display-name>Beta</display-name></channel>
                  <programme start="${xmltvTime(now - 600_000)}" stop="${xmltvTime(now + 3_000_000)}" channel="alpha.id"><title>A</title></programme>
                  <programme start="${xmltvTime(now - 600_000)}" stop="${xmltvTime(now + 3_000_000)}" channel="beta.id"><title>B</title></programme>
                </tv>"""
            )
        )
        val repo = repository()
        val alpha = IptvChannel(id = "${source.id}::1", sourceId = source.id, name = "Alpha", group = "", streamUrl = "https://s/1", epgId = "alpha.id")
        val beta = IptvChannel(id = "${source.id}::2", sourceId = source.id, name = "Beta", group = "", streamUrl = "https://s/2", epgId = "beta.id")

        val first = repo.loadGuide(listOf(source), listOf(alpha), force = false)
        val second = repo.loadGuide(listOf(source), listOf(beta), force = false)

        assertEquals(listOf("A"), first.getValue(alpha.id).map { it.title })
        assertEquals(listOf("B"), second.getValue(beta.id).map { it.title })
        // Only the filtered parse changes between calls; the file itself is still fetched once.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `stale manual mapping is reported for review and never falls back to a name guess`() = kotlinx.coroutines.test.runTest {
        val channelId = "${existing.id}::7"
        val source = existing.copy(
            epgUrl = server.url("/guide.xml").toString(),
            manualEpgMappings = mapOf(channelId to "removed.id")
        )
        storedSources = listOf(source)
        val now = System.currentTimeMillis()
        fun xmltvTime(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))
        server.enqueue(
            MockResponse().setBody(
                """<tv>
                  <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
                  <programme start="${xmltvTime(now - 600_000)}" stop="${xmltvTime(now + 3_000_000)}" channel="bbc1.uk"><title>Guessable</title></programme>
                </tv>"""
            )
        )
        val channel = IptvChannel(id = channelId, sourceId = source.id, name = "BBC One", group = "", streamUrl = "https://s/7")

        val result = repository().loadGuideWithReport(listOf(source), listOf(channel), force = false)

        assertTrue(result.programmesByChannel.isEmpty())
        val report = result.sources.single()
        assertEquals(1, report.staleManual)
        assertEquals(1, report.unmatched)
        assertEquals(0, report.nameMatches)
        val review = report.mappingReviews.single()
        assertTrue(review.stale)
        assertEquals("removed.id", review.currentGuideId)
        assertTrue(review.candidates.any { it.guideId == "bbc1.uk" })
    }

    @Test
    fun `fuzzy review suggestions neither assign programmes nor persist mappings`() = kotlinx.coroutines.test.runTest {
        val source = existing.copy(epgUrl = server.url("/guide.xml").toString())
        storedSources = listOf(source)
        server.enqueue(MockResponse().setBody("""<tv>
            <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
            <programme start="20261024100000" stop="20261024110000" channel="bbc1.uk"><title>Not assigned</title></programme>
        </tv>"""))
        val channel = IptvChannel("${source.id}::1", source.id, "BBC On", "", "https://stream.example/1")
        val report = repository().loadGuideWithReport(listOf(source), listOf(channel), force = false)
        val candidate = report.sources.single().guideCandidates.single()

        assertTrue(com.nuvio.tv.ui.screens.live.candidateSimilarity(channel.name, candidate)!! >= 70)
        assertEquals(1, report.sources.single().unmatched)
        assertEquals(0, report.sources.single().matched)
        assertTrue(report.programmesByChannel.isEmpty())
        assertTrue(storedSources.single().manualEpgMappings.isEmpty())
        coVerify(exactly = 0) { store.update(any()) }
    }

    @Test
    fun `guide and catch-up shifts remain separate across cached guide and source edits`() = kotlinx.coroutines.test.runTest {
        val now = System.currentTimeMillis() / 1000 * 1000
        val rawStart = now - 7_200_000L
        val rawEnd = rawStart + 1_800_000L
        val source = existing.copy(epgUrl = server.url("/guide.xml").toString(), guideShiftMinutes = 60, catchupShiftMinutes = -30)
        storedSources = listOf(source)
        fun stamp(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z"))
        server.enqueue(MockResponse().setBody("""<tv>
            <channel id="archive"><display-name>Archive</display-name></channel>
            <programme start="${stamp(rawStart)}" stop="${stamp(rawEnd)}" channel="archive"><title>Earlier</title></programme>
        </tv>"""))
        val channel = IptvChannel(
            "${source.id}::1", source.id, "Archive", "", "https://stream.example/1", epgId = "archive",
            catchupDays = 3, catchupTemplate = "https://archive.example/?start={utc}&end={utcend}&duration={duration}",
            catchupShiftMinutes = 720
        )
        val repo = repository()
        val displayed = repo.loadGuide(listOf(source), listOf(channel), false).getValue(channel.id).single()
        assertEquals(rawStart + 3_600_000L, displayed.startMs)
        assertEquals(rawEnd + 3_600_000L, displayed.endMs)
        assertEquals(
            "https://archive.example/?start=${(displayed.startMs - 1_800_000L) / 1000}&end=${(displayed.endMs - 1_800_000L) / 1000}&duration=1800",
            repo.catchupUrl(channel, displayed, now)
        )
        val changedCatchup = source.copy(catchupShiftMinutes = 30)
        storedSources = listOf(changedCatchup)
        assertEquals(listOf(displayed), repo.loadGuide(listOf(changedCatchup), listOf(channel), false).getValue(channel.id))
        assertEquals(
            "https://archive.example/?start=${(displayed.startMs + 1_800_000L) / 1000}&end=${(displayed.endMs + 1_800_000L) / 1000}&duration=1800",
            repo.catchupUrl(channel, displayed, now)
        )
        val changedGuide = changedCatchup.copy(guideShiftMinutes = 30)
        storedSources = listOf(changedGuide)
        val redisplayed = repo.loadGuide(listOf(changedGuide), listOf(channel), false).getValue(channel.id).single()
        assertEquals(rawStart + 1_800_000L, redisplayed.startMs)
        assertEquals(
            "https://archive.example/?start=${(rawStart + 3_600_000L) / 1000}&end=${(rawEnd + 3_600_000L) / 1000}&duration=1800",
            repo.catchupUrl(channel, redisplayed, now)
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `guide shift is clamped to half-hour steps within twelve hours`() {
        assertEquals(0, IptvSource.clampGuideShift(0))
        assertEquals(60, IptvSource.clampGuideShift(55))
        assertEquals(330, IptvSource.clampGuideShift(330))
        assertEquals(-90, IptvSource.clampGuideShift(-80))
        assertEquals(720, IptvSource.clampGuideShift(10_000))
        assertEquals(-720, IptvSource.clampGuideShift(-10_000))
        assertEquals(0, IptvSource.clampCatchupShift(0))
        assertEquals(60, IptvSource.clampCatchupShift(55))
        assertEquals(-90, IptvSource.clampCatchupShift(-80))
        assertEquals(720, IptvSource.clampCatchupShift(10_000))
        assertEquals(-720, IptvSource.clampCatchupShift(-10_000))
    }

    @Test
    fun `guide shift moves programmes and keeps rows the unshifted window would drop`() = kotlinx.coroutines.test.runTest {
        val shiftMinutes = 8 * 60
        val source = existing.copy(epgUrl = server.url("/guide.xml").toString(), guideShiftMinutes = shiftMinutes)
        storedSources = listOf(source)
        val now = System.currentTimeMillis()
        fun xmltvTime(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))
        // Provider clock runs 8 h behind: "now" in its guide is 8 h ago, beyond the 2 h past window.
        val guideStart = now - shiftMinutes * 60_000L - 600_000L
        val guideStop = now - shiftMinutes * 60_000L + 3_000_000L
        server.enqueue(
            MockResponse().setBody(
                """<tv>
                  <channel id="late.id"><display-name>Late</display-name></channel>
                  <programme start="${xmltvTime(guideStart)}" stop="${xmltvTime(guideStop)}" channel="late.id"><title>On now</title></programme>
                </tv>"""
            )
        )
        val channel = IptvChannel(id = "${source.id}::1", sourceId = source.id, name = "Late", group = "", streamUrl = "https://s/1", epgId = "late.id")

        val repo = repository()
        val shifted = repo.loadGuide(listOf(source), listOf(channel), force = false).getValue(channel.id).single()

        assertEquals("On now", shifted.title)
        assertEquals(guideStart / 1000 * 1000 + shiftMinutes * 60_000L, shifted.startMs)
        val (current, _) = IptvRepository.nowAndNext(listOf(shifted), now)
        assertEquals("On now", current?.title)

        // Changing only the shift re-parses the cached file without another download.
        val unshifted = repo.loadGuide(listOf(source.copy(guideShiftMinutes = 0)), listOf(channel), force = false)
        assertTrue(unshifted[channel.id].isNullOrEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `edit without a shift keeps the saved guide shift`() = kotlinx.coroutines.test.runTest {
        storedSources = listOf(existing.copy(guideShiftMinutes = -60, catchupShiftMinutes = 90))
        val updated = repository().updateSource(
            id = existing.id,
            name = "Renamed",
            type = existing.type,
            url = existing.url,
            username = "",
            password = "",
            epgUrl = ""
        )
        assertEquals(-60, updated.guideShiftMinutes)
        assertEquals(-60, storedSources.single().guideShiftMinutes)
        assertEquals(90, updated.catchupShiftMinutes)
        assertEquals(90, storedSources.single().catchupShiftMinutes)
    }

    @Test
    fun `catch-up url only for Xtream channels with archive and in-window programmes`() = kotlinx.coroutines.test.runTest {
        val xtream = IptvSource(
            id = "xc",
            name = "XC",
            type = IptvSourceType.XTREAM,
            url = server.url("/").toString().trimEnd('/'),
            username = "u",
            password = "p",
            catchupShiftMinutes = 30
        )
        storedSources = listOf(existing, xtream)
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1},"server_info":{"timezone":"Asia/Hong_Kong"}}"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""[{"stream_id":42,"name":"Archive","tv_archive":1,"tv_archive_duration":3}]"""))
        val repo = repository()
        val channel = repo.loadChannels(listOf(xtream), force = true).channels.single()
        assertEquals(3, channel.catchupDays)

        val now = System.currentTimeMillis()
        val start = now - 3_600_000L
        val programme = EpgProgramme(start, start + 1_800_000L, "Earlier")
        val url = repo.catchupUrl(channel, programme, now)
        assertNotNull(url)
        val expectedStart = java.time.Instant.ofEpochMilli(start + 30 * 60_000L).atZone(java.time.ZoneId.of("Asia/Hong_Kong"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
        assertTrue(url!!.endsWith("/timeshift/u/p/30/$expectedStart/42.ts"))

        val airingStart = now - 2 * 60 * 60_000L
        val airingProgramme = EpgProgramme(airingStart, now + 15 * 60_000L, "Still on")
        val restartUrl = repo.catchupUrl(channel, airingProgramme, now)
        val expectedRestartStart = java.time.Instant.ofEpochMilli(airingStart + 30 * 60_000L)
            .atZone(java.time.ZoneId.of("Asia/Hong_Kong"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
        assertTrue(restartUrl!!.endsWith("/timeshift/u/p/135/$expectedRestartStart/42.ts"))

        org.junit.Assert.assertNull(repo.catchupUrl(channel, EpgProgramme(now + 60_000L, now + 120_000L, "Later"), now))
        val m3uChannel = IptvChannel(id = "${existing.id}::1", sourceId = existing.id, name = "M", group = "", streamUrl = "u", catchupDays = 7)
        org.junit.Assert.assertNull(repo.catchupUrl(m3uChannel, programme, now))
    }

    @Test
    fun `M3U archive expands numeric tokens and rejects unsupported URLs`() = kotlinx.coroutines.test.runTest {
        val now = 1_792_836_000_000L
        val start = now - 3_600_000L
        val end = start + 300_000L
        val programme = EpgProgramme(start, end, "Earlier")
        storedSources = listOf(existing.copy(catchupShiftMinutes = 30))
        val channel = IptvChannel(
            id = "${existing.id}::1",
            sourceId = existing.id,
            name = "Archive",
            group = "",
            streamUrl = "https://stream.example/live",
            catchupDays = 3,
            catchupTemplate = "https://archive.example/watch?start={utc}&end={utcend}&duration={duration}"
        )
        val repo = repository()
        assertEquals(
            "https://archive.example/watch?start=${(start + 30 * 60_000L) / 1000}&end=${(end + 30 * 60_000L) / 1000}&duration=300",
            repo.catchupUrl(channel, programme, now)
        )
        val airingStart = now - 2 * 60 * 60_000L
        val airingEnd = now + 15 * 60_000L
        val restartProgramme = EpgProgramme(airingStart, airingEnd, "Still on")
        assertEquals(
            "https://archive.example/watch?start=${(airingStart + 30 * 60_000L) / 1000}&end=${(airingEnd + 30 * 60_000L) / 1000}&duration=8100",
            repo.catchupUrl(channel, restartProgramme, now)
        )
        for (template in listOf("https://archive.example/{unknown}", "file:///private/{utc}", "not a URL")) {
            org.junit.Assert.assertNull(repo.catchupUrl(channel.copy(catchupTemplate = template), programme, now))
        }
        org.junit.Assert.assertNull(repo.catchupUrl(channel.copy(catchupDays = 0), programme, now))
    }
}
