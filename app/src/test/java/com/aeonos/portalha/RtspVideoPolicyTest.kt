package com.aeonos.portalha

import org.junit.Assert.assertEquals
import org.junit.Test

class RtspVideoPolicyTest {

    @Test
    fun `rtsp video defaults are frigate friendly`() {
        assertEquals(30, RtspVideoPolicy.TARGET_FPS)
        assertEquals(1, RtspVideoPolicy.KEYFRAME_INTERVAL_SECONDS)
        assertEquals(33_333L, RtspVideoPolicy.frameDurationUs())
    }

    @Test
    fun `requested fps is pinned to the target constant frame rate`() {
        assertEquals(30, RtspVideoPolicy.coerceFps(0))
        assertEquals(30, RtspVideoPolicy.coerceFps(30))
        assertEquals(30, RtspVideoPolicy.coerceFps(15))
    }

    @Test
    fun `portal plus corrected geometry stays square`() {
        val geometry = RtspVideoPolicy.correctedGeometry(
            requestedWidth = 1280,
            requestedHeight = 720,
            rotation = 0,
            isSquashedFrontCam = true,
        )

        assertEquals(480, geometry.sourceWidth)
        assertEquals(480, geometry.sourceHeight)
        assertEquals(480, geometry.codedWidth)
        assertEquals(480, geometry.codedHeight)
    }
}
