package com.aeonos.portalha

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Hands-free intercom announce: "<wake phrase> announce" → armed chirp → speak → your live
 * voice broadcasts to every Portal → silence ends it (end tone). No assistant, no TTS —
 * pure existing intercom transport off the shared warm mic.
 *
 * False-positive containment ("armed, not live"): on trigger we do NOT open the intercom.
 * We chirp locally and watch the mic for actual voice onset. Only real speech within
 * [ARMED_WINDOW_MS] opens the broadcast — a false trigger in a quiet room is a private
 * chirp and nothing ever transmits. A short pre-roll buffer is replayed on onset so the
 * first word isn't clipped.
 *
 * All frame callbacks run on SoundMonitor's capture thread (one frame at a time), so the
 * state machine is single-threaded; [active] is volatile only for cheap outside reads.
 */
class VoiceAnnounce(
    private val soundMonitor: () -> SoundMonitor?,
    private val intercom: () -> Intercom?,
    // Called once per announce when it finishes (any outcome) — lets the service re-arm
    // wake matching with a short echo guard.
    private val onDone: () -> Unit,
    // The glowing "I'm listening" orb; pulses while armed, throbs with voice while live.
    private val orb: AnnounceOrbOverlay? = null,
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val ARMED_WINDOW_MS = 3_500L    // must start speaking within this
        // Ignore the mic while our own armed chirp is sounding — verified on-device that the
        // chirp registers as "voice onset" (~200 ms after arming, level just over the floor)
        // and self-opens the broadcast, defeating the armed-not-live protection.
        private const val CHIRP_GUARD_MS = 450L
        private const val SILENCE_END_MS = 2_000L     // this much quiet ends the broadcast
        private const val MAX_LIVE_MS = 30_000L       // hard cap per announce
        private const val ONSET_LEVEL = 18            // 0-100 level that counts as voice onset
        private const val SILENCE_LEVEL = 12          // below this counts toward the silence end
        private const val PREROLL_FRAMES = 8          // ~320 ms replayed so the first word is whole
    }

    private enum class State { IDLE, ARMED, LIVE }

    @Volatile private var state = State.IDLE
    private var armedDeadline = 0L
    private var onsetAfter = 0L      // chirp guard: no onset/pre-roll until this passes
    private var liveDeadline = 0L
    private var silenceSince = 0L
    private var peakLevel = 0
    private val preroll = ArrayDeque<ShortArray>()
    private var intercomSink: ((ShortArray, Int) -> Unit)? = null

    fun isActive() = state != State.IDLE

    /** Trigger from a matched "<phrase> announce". Returns false if it can't start. */
    fun start(): Boolean {
        // Self-heal: frames drive every transition, so if the mic stopped feeding us
        // mid-announce we could be stuck ARMED/LIVE forever. Well past any deadline
        // with the state unchanged = stale — clean up and allow the new trigger.
        if (state != State.IDLE) {
            val stuckPast = if (state == State.ARMED) armedDeadline else liveDeadline
            if (System.currentTimeMillis() > stuckPast + 5_000L) finish("stale state", ToneGenerator.TONE_PROP_NACK)
        }
        if (state != State.IDLE) return false
        val sm = soundMonitor()
        val ic = intercom()
        if (sm == null || !sm.isRunning() || ic == null) {
            Log.i(TAG, "announce: mic not held — can't voice-announce"); return false
        }
        if (!ic.canTransmit()) { Log.i(TAG, "announce: receive-only Portal"); return false }
        // Busy = another Portal is already announcing (e.g. two Portals heard the same
        // "announce" and the other won the lock). Stay SILENT — its broadcast is about to
        // play on this device anyway; a buzz on top would just be confusing.
        if (ic.isTalking() || ic.busySpeakerName() != null) {
            Log.i(TAG, "announce: intercom busy"); return false
        }
        preroll.clear()
        peakLevel = 0
        val now = System.currentTimeMillis()
        armedDeadline = now + ARMED_WINDOW_MS
        onsetAfter = now + CHIRP_GUARD_MS
        state = State.ARMED
        sm.frameSink = { buf, n -> onFrame(buf, n) }
        orb?.show()
        orb?.onTap = { finish("tapped", ToneGenerator.TONE_PROP_BEEP) }
        tone(ToneGenerator.TONE_PROP_BEEP2, 200)      // distinctive double-chirp = "speak now"
        Log.i(TAG, "announce: armed — waiting for voice")
        return true
    }

    // Every 40 ms mic chunk while armed or live, on the capture thread.
    private fun onFrame(buf: ShortArray, n: Int) {
        val level = level(buf, n)
        if (level > peakLevel) peakLevel = level
        orb?.setLevel(level)
        val now = System.currentTimeMillis()
        when (state) {
            State.ARMED -> {
                if (now < onsetAfter) return   // our own chirp is sounding — don't hear ourselves
                preroll.addLast(buf.copyOf(n))
                while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                if (level >= ONSET_LEVEL) goLive(now)
                else if (now > armedDeadline) finish("no speech — cancelled", ToneGenerator.TONE_PROP_NACK)
            }
            State.LIVE -> {
                intercomSink?.invoke(buf, n)
                if (level < SILENCE_LEVEL) {
                    if (silenceSince == 0L) silenceSince = now
                    else if (now - silenceSince >= SILENCE_END_MS) finish("silence", ToneGenerator.TONE_PROP_BEEP)
                } else silenceSince = 0L
                if (state == State.LIVE && now > liveDeadline) finish("max length", ToneGenerator.TONE_PROP_BEEP)
            }
            State.IDLE -> {}   // stale frame after finish — ignore
        }
    }

    private fun goLive(now: Long) {
        val ic = intercom() ?: run { finish("intercom gone", ToneGenerator.TONE_PROP_NACK); return }
        // startTalk installs the intercom's own frameSink; capture it, then put ours back on
        // top so we keep watching levels while forwarding. silent=true — the armed chirp
        // already told the user to talk, no second beep mid-sentence.
        if (!ic.startTalk("all", silent = true)) {
            finish("intercom refused", null); return   // silent: usually lost the race to another Portal
        }
        val sm = soundMonitor()
        intercomSink = sm?.frameSink
        sm?.frameSink = { buf, n -> onFrame(buf, n) }
        // Replay the pre-roll so the word that triggered onset arrives whole.
        preroll.forEach { f -> intercomSink?.invoke(f, f.size) }
        preroll.clear()
        silenceSince = 0L
        liveDeadline = now + MAX_LIVE_MS
        state = State.LIVE
        orb?.setLive(true)
        Log.i(TAG, "announce: LIVE — broadcasting to all")
    }

    private fun finish(reason: String, endTone: Int?) {
        val wasLive = state == State.LIVE
        state = State.IDLE
        if (wasLive) intercom()?.stopTalk()            // also clears frameSink
        else soundMonitor()?.frameSink = null          // armed-only: detach our sink
        intercomSink = null
        preroll.clear()
        orb?.hide()
        endTone?.let { tone(it, 200) }
        Log.i(TAG, "announce: ended ($reason, peak=$peakLevel)")
        runCatching { onDone() }
    }

    /** Same RMS→dBFS→0-100 mapping as SoundMonitor, per 40 ms frame. */
    private fun level(buf: ShortArray, n: Int): Int {
        var sumSq = 0.0
        for (i in 0 until n) sumSq += buf[i].toLong() * buf[i]
        val rms = sqrt(sumSq / n)
        val dbfs = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -90.0
        return ((dbfs + 60.0) / 60.0 * 100.0).coerceIn(0.0, 100.0).toInt()
    }

    private fun tone(which: Int, ms: Int) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)   // loud — it's the "speak now" cue
            tg.startTone(which, ms)
            Thread({ Thread.sleep(ms + 150L); runCatching { tg.release() } }, "portal-ha-announce-tone")
                .also { it.isDaemon = true }.start()
        }
    }
}
