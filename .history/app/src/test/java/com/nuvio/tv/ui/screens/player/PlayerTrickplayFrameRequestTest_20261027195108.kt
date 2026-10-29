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
}
