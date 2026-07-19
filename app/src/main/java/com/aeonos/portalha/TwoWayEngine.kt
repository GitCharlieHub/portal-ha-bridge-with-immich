package com.aeonos.portalha

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

// Experimental hands-free 2-way. Opens a dedicated VOICE_COMMUNICATION capture with
// AcousticEchoCanceler + NoiseSuppressor so the mic does NOT hear our own speaker, runs
// VOX (voice activity), and transmits through the intercom's first-come floor lock -- only
// one Portal ever holds the floor, so streams never mix/garble. On devices without AEC,
// VOX is unsafe (the mic would hear the incoming audio and echo it back), so those use
// tap-to-talk instead and never run this engine.
class TwoWayEngine(
    private val context: Context,
    private val intercom: () -> Intercom?,
    private val onTalking: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val SR = 16000
        private const val FRAME = 640           // 40 ms @ 16 kHz
        private const val VOX_ON = 22           // level (0-100) that starts a turn
        private const val VOX_OFF = 14          // stay above this to hold the turn
        private const val ONSET_FRAMES = 3      // ~120 ms sustained speech to grab the floor
        private const val HANG_MS = 900L        // silence before releasing the floor
    }

    val aecAvailable: Boolean get() = AcousticEchoCanceler.isAvailable()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile var talking = false
        private set

    fun isRunning() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "portal-ha-2way").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread = null   // the loop sees running=false, releases the floor + mic, exits
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }; running.set(false); Log.w(TAG, "2way: mic unavailable"); return
        }
        val aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        val ns = runCatching { NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        Log.i(TAG, "2way: capture started (aecAvail=${aecAvailable} aecOn=${aec?.enabled} nsOn=${ns?.enabled})")
        runCatching { rec.startRecording() }
        val buf = ShortArray(FRAME)
        var onset = 0
        var silenceSince = 0L
        try {
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val level = level(buf, n)
                val ic = intercom() ?: continue
                if (!talking) {
                    onset = if (level >= VOX_ON) onset + 1 else 0
                    if (onset >= ONSET_FRAMES && ic.floorAcquire()) {
                        talking = true; onset = 0; silenceSince = 0L
                        Log.i(TAG, "2way: got floor -> talking (level=$level)")
                        onTalking(true)
                    }
                } else {
                    ic.floorSend(buf, n)
                    if (level < VOX_OFF) {
                        if (silenceSince == 0L) silenceSince = System.currentTimeMillis()
                        else if (System.currentTimeMillis() - silenceSince >= HANG_MS) {
                            ic.floorRelease(); talking = false
                            Log.i(TAG, "2way: released floor (silence)")
                            onTalking(false)
                        }
                    } else silenceSince = 0L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "2way: loop error ${e.message}")
        } finally {
            if (talking) { runCatching { intercom()?.floorRelease() }; talking = false; onTalking(false) }
            runCatching { aec?.release() }; runCatching { ns?.release() }
            runCatching { rec.stop(); rec.release() }
            Log.i(TAG, "2way: capture stopped")
        }
    }

    private fun level(buf: ShortArray, n: Int): Int {
        var s = 0.0
        for (i in 0 until n) s += buf[i].toLong() * buf[i]
        val rms = sqrt(s / n)
        val db = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -90.0
        return ((db + 60.0) / 60.0 * 100.0).coerceIn(0.0, 100.0).toInt()
    }
}