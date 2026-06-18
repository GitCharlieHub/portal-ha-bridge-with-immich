package com.aeonos.portalha

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// Synthesized alert tones — no bundled assets. Played on the media stream so
// the HA volume slider and volume mute apply.
object TonePlayer {

    private const val TAG = "PortalHA"
    private const val SAMPLE_RATE = 44100

    fun play(name: String) {
        val pcm = when (name.trim().lowercase()) {
            "doorbell" -> doorbell()
            "alert" -> alert()
            else -> { Log.w(TAG, "unknown tone '$name'"); return }
        }
        // Each play gets its own short-lived thread and track; overlaps just mix.
        Thread {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / SAMPLE_RATE + 200)
                track.release()
            }.onFailure { Log.w(TAG, "tone playback failed: ${it.message}") }
        }.also { it.isDaemon = true }.start()
    }

    // Classic two-tone "ding-dong" chime (E5 then C5)
    private fun doorbell(): ShortArray {
        val ding = tone(659.25, 0.45, decay = 4.0)
        val gap = ShortArray((0.06 * SAMPLE_RATE).toInt())
        val dong = tone(523.25, 0.65, decay = 3.0)
        return ding + gap + dong
    }

    // Three quick attention beeps
    private fun alert(): ShortArray {
        val beep = tone(880.0, 0.16, decay = 1.5)
        val gap = ShortArray((0.12 * SAMPLE_RATE).toInt())
        return beep + gap + beep + gap + beep
    }

    private fun tone(freq: Double, seconds: Double, decay: Double): ShortArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-decay * t / seconds)
            // fundamental plus a touch of 2nd harmonic for a bell-ish timbre
            val s = 0.8 * sin(2 * PI * freq * t) + 0.2 * sin(4 * PI * freq * t)
            out[i] = (s * env * 0.85 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
