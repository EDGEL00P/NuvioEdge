package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryPolicyTest {
    @Test
    fun `permanent source and security errors do not trigger rebuild retries`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
        ).forEach { code ->
            assertFalse("Error $code must not retry", isRetryablePlaybackError(PlaybackException("error", null, code)))
        }
    }

    @Test
    fun `temporary network failures remain recoverable`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        ).forEach { code ->
            assertTrue("Error $code should retry", isRetryablePlaybackError(PlaybackException("error", null, code)))
        }
    }

    @Test
    fun `permanent HTTP responses do not retry while rate limits and server errors do`() {
        listOf(400, 401, 403, 404, 410).forEach { status ->
            assertFalse("HTTP $status must not retry", isRetryablePlaybackError(httpFailure(status)))
        }
        listOf(429, 500, 502, 503).forEach { status ->
            assertTrue("HTTP $status should retry", isRetryablePlaybackError(httpFailure(status)))
        }
    }

    private fun httpFailure(status: Int): PlaybackException {
        val cause = HttpDataSource.InvalidResponseCodeException(
            status,
            "HTTP $status",
            null,
            emptyMap(),
            mockk(relaxed = true),
            ByteArray(0)
        )
        return PlaybackException("HTTP failure", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }
}