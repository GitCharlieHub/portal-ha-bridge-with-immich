package com.aeonos.portalha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

class MotionDetector {
    private var prevPixels: IntArray? = null
    private val W = 80
    private val H = 60

    /**
     * Returns true if inter-frame motion exceeds the sensitivity threshold.
     * sensitivity 1 = very sensitive, 100 = very firm (requires large changes).
     */
    fun detect(jpeg: ByteArray, sensitivity: Int): Boolean {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
        val scaled = Bitmap.createScaledBitmap(bmp, W, H, false)
        bmp.recycle()

        val pixels = IntArray(W * H)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        scaled.recycle()

        val prev = prevPixels
        prevPixels = pixels
        if (prev == null) return false

        var totalDiff = 0L
        for (i in pixels.indices) {
            val lum1 = ((prev[i] shr 16 and 0xFF) + (prev[i] shr 8 and 0xFF) + (prev[i] and 0xFF)) / 3
            val lum2 = ((pixels[i] shr 16 and 0xFF) + (pixels[i] shr 8 and 0xFF) + (pixels[i] and 0xFF)) / 3
            totalDiff += abs(lum1 - lum2)
        }
        // sensitivity 1→threshold 2, sensitivity 100→threshold 50
        val threshold = 2 + (sensitivity.coerceIn(1, 100) - 1) * 48 / 99
        return (totalDiff / pixels.size) >= threshold
    }

    fun reset() { prevPixels = null }
}
