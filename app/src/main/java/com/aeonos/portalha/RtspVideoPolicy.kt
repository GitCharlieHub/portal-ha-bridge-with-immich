package com.aeonos.portalha

object RtspVideoPolicy {
    const val TARGET_FPS = 30
    const val KEYFRAME_INTERVAL_SECONDS = 1

    fun coerceFps(requestedFps: Int): Int = TARGET_FPS

    fun frameDurationUs(fps: Int = TARGET_FPS): Long = 1_000_000L / fps
}
