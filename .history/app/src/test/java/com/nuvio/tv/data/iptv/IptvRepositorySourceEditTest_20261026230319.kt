package com.nuvio.tv.data.iptv

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
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
        coEvery { store.current() } coAnswers { storedSources }
        coEvery { store.update(any()) } coAnswers {
            storedSources = firstArg<(List<IptvSource>) -> List<IptvSource>>().invoke(storedSources)
        }
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
}
