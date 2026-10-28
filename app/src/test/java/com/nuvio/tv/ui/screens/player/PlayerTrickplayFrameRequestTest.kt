package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTrickplayFrameRequestTest {
    @Test
    fun `frame timestamps are bucketed and never negative`() {
        assertEquals(0L, PlayerTrickplayFrameRequest.bucketMs(-1L))
        assertEquals(0L, PlayerTrickplayFrameRequest.bucketMs(4_999L))
        assertEquals(5_000L, PlayerTrickplayFrameRequest.bucketMs(5_000L))
        assertEquals(10_000L, PlayerTrickplayFrameRequest.bucketMs(14_999L))
    }

    @Test
    fun `scaled extraction is selected only when the platform supports it`() {
        assertEquals(false, PlayerTrickplayFrameRequest.usesScaledFrameApi(24))
        assertEquals(false, PlayerTrickplayFrameRequest.usesScaledFrameApi(26))
        assertEquals(true, PlayerTrickplayFrameRequest.usesScaledFrameApi(27))
        assertEquals(true, PlayerTrickplayFrameRequest.usesScaledFrameApi(35))
    }

    @Test
    fun `preview dimensions preserve aspect ratio and stay within bounds`() {
        assertEquals(
            PreviewFrameDimensions(480, 270),
            PlayerTrickplayFrameRequest.previewDimensions(3840, 2160, 480, 270)
        )
        assertEquals(
            PreviewFrameDimensions(152, 270),
            PlayerTrickplayFrameRequest.previewDimensions(1080, 1920, 480, 270)
        )
        assertEquals(
            PreviewFrameDimensions(320, 180),
            PlayerTrickplayFrameRequest.previewDimensions(320, 180, 480, 270)
        )
    }

    @Test
    fun `invalid frame dimensions are rejected`() {
        assertEquals(null, PlayerTrickplayFrameRequest.previewDimensions(0, 1080, 480, 270))
        assertEquals(null, PlayerTrickplayFrameRequest.previewDimensions(1920, 1080, 0, 270))
    }
}
