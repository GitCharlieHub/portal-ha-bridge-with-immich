package com.aeonos.portalha

object RtspVideoPolicy {
    const val TARGET_FPS = 30
    const val KEYFRAME_INTERVAL_SECONDS = 1

    fun coerceFps(requestedFps: Int): Int = TARGET_FPS

    fun frameDurationUs(fps: Int = TARGET_FPS): Long = 1_000_000L / fps

    fun streamGeometry(
        requestedWidth: Int,
        requestedHeight: Int,
        rotation: Int,
        isSquashedFrontCam: Boolean,
        isCipher: Boolean,
    ): StreamGeometry {
        val corrected = isSquashedFrontCam && requestedWidth * 9 == requestedHeight * 16
        val sourceWidth = when {
            !corrected -> requestedWidth
            isCipher -> 480
            else -> 480
        }
        val sourceHeight = when {
            !corrected -> requestedHeight
            isCipher -> 640
            else -> 480
        }
        val normalizedRotation = (((rotation % 360) + 360) % 360)
        val swapsCodedSize = normalizedRotation == 90 || normalizedRotation == 270
        return StreamGeometry(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            codedWidth = if (swapsCodedSize) sourceHeight else sourceWidth,
            codedHeight = if (swapsCodedSize) sourceWidth else sourceHeight,
        )
    }
}

data class StreamGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val codedWidth: Int,
    val codedHeight: Int,
)
