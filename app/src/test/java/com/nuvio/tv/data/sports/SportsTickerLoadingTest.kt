package com.nuvio.tv.data.sports

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SportsTickerLoadingTest {
    @Test
    fun `available scores publish before a slow league finishes`() = runBlocking {
        val releaseSlow = CountDownLatch(1)
        val firstGames = CompletableDeferred<List<TickerGame>>()
        val fixture = """{"events":[{"id":"1","status":{"type":{"state":"in","shortDetail":"Live"}},"competitions":[{"competitors":[{"homeAway":"home","score":"7","team":{"abbreviation":"H"}},{"homeAway":"away","score":"3","team":{"abbreviation":"A"}}]}]}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val slow = chain.request().url.encodedPath.contains("slow")
            if (slow) check(releaseSlow.await(10, TimeUnit.SECONDS))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body((if (slow) """{"events":[]}""" else fixture).toResponseBody())
                .build()
        }.build()
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val repository = SportsTickerRepository(client, mockk<ProfileDataStoreFactory>(), profileManager)
        val pending = async {
            repository.fetchGames(
                leagues = listOf(SportsLeague("FAST", "test/fast"), SportsLeague("SLOW", "test/slow")),
                onFirstGames = { firstGames.complete(it) }
            )
        }

        try {
            val early = withTimeout(5_000) { firstGames.await() }
            assertEquals(listOf("FAST"), early.map { it.league })
            assertFalse(pending.isCompleted)
        } finally {
            releaseSlow.countDown()
        }
        assertEquals(listOf("1"), pending.await().map { it.id })
    }
}