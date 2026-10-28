package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPositionFromMediaRequestTest {

    @Test
    fun `pending engine-switch resume waits for a selected video track`() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val view = mockk<NuvioMpvSurfaceView>(relaxed = true)
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.MVP_PLAYER
        every { controller.delayMpvResumeSeekUntilVideoTrack } returns true
        every { view.hasVideoTrackSelectedNow() } returns false

        controller.applyPendingMpvSeekIfNeeded(view, currentPositionMs = 0L, durationMs = 120_000L)

        verify(exactly = 0) { view.seekToMs(any()) }
        verify(exactly = 0) { controller.delayMpvResumeSeekUntilVideoTrack = false }
    }

    @Test
    fun `pending engine-switch resume seeks when video track is ready`() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val view = mockk<NuvioMpvSurfaceView>(relaxed = true)
        val state = MutableStateFlow(PlayerUiState(pendingSeekPosition = 60_000L))
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.MVP_PLAYER
        every { controller.delayMpvResumeSeekUntilVideoTrack } returns true
        every { controller._uiState } returns state
        every { controller.pendingResumeProgress } returns null
        every { view.hasVideoTrackSelectedNow() } returns true

        controller.applyPendingMpvSeekIfNeeded(view, currentPositionMs = 0L, durationMs = 120_000L)

        verify(exactly = 1) { controller.delayMpvResumeSeekUntilVideoTrack = false }
        verify(exactly = 1) { view.seekToMs(60_000L) }
        assertEquals(60_000L, state.value.pendingSeekPosition)
    }

    @Test
    fun `pending mpv resume does not seek when another engine is active`() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val view = mockk<NuvioMpvSurfaceView>(relaxed = true)
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER

        controller.applyPendingMpvSeekIfNeeded(view, currentPositionMs = 0L, durationMs = 120_000L)

        verify(exactly = 0) { view.seekToMs(any()) }
        verify(exactly = 0) { view.hasVideoTrackSelectedNow() }
    }

    @Test
    fun `previous file is not from the request after an episode switch`() {
        assertFalse(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", "https://h/e14.mkv"))
    }

    @Test
    fun `nothing loaded yet is not from the request`() {
        assertFalse(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", null))
    }

    @Test
    fun `requested url counts`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", "https://h/e15.mkv"))
        assertTrue(isMpvPositionFromMediaRequest("https://h/e15.mkv", null, "https://h/e15.mkv"))
    }

    @Test
    fun `reload of the same url counts`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e15.mkv", "https://h/e15.mkv"))
    }

    @Test
    fun `playlist entry path counts once it differs from the old path`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/list.m3u", "https://h/e14.mkv", "https://h/e15.mkv"))
    }

    @Test
    fun `no request counts`() {
        assertTrue(isMpvPositionFromMediaRequest(null, null, null))
    }

    @Test
    fun `empty path is not from the request`() {
        assertFalse(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", ""))
    }

    @Test
    fun `reload of a loaded playlist counts once a path is reported`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/list.m3u", null, "https://h/e14.mkv"))
    }

    @Test
    fun `new request records the current path`() {
        assertEquals("https://h/e14.mkv", mpvPathBaselineForRequest("https://h/e15.mkv", "https://h/e14.mkv", null, "https://h/e14.mkv"))
        assertNull(mpvPathBaselineForRequest("https://h/e15.mkv", null, null, ""))
    }

    @Test
    fun `repeat of a pending request keeps its baseline`() {
        assertEquals("https://h/e14.mkv", mpvPathBaselineForRequest("https://h/e15.mkv", "https://h/e15.mkv", "https://h/e14.mkv", "https://h/e14.mkv"))
    }

    @Test
    fun `repeat of a loaded request drops its baseline`() {
        assertNull(mpvPathBaselineForRequest("https://h/list.m3u", "https://h/list.m3u", "https://h/e13.mkv", "https://h/e14.mkv"))
    }
}
