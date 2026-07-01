package com.aeonos.portalha

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * On-device wake-word listener. Runs a Vosk recognizer with a grammar limited to the
 * wake phrase (keyword = the last word), fed the SAME warm 16 kHz mono mic that
 * [SoundMonitor] already holds in the foreground — so it works on Android 10 Portals,
 * where a headless background mic gets silenced. On a match it calls [onWake]; the
 * service then fires portal-wake's public handoff broadcast to the assistant.
 *
 * The wake word is a config string ([phrase]) — changing it needs no new model, the
 * whole point of using Vosk. The ~40 MB small model is downloaded to filesDir on first
 * start (not bundled) so the APK stays small; it's loaded from there on subsequent runs.
 *
 * Vosk inference runs on a dedicated thread draining [queue]; [feed] (called on
 * SoundMonitor's capture thread) only copies + enqueues, so heavy decoding never stalls
 * the mic loop.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWake: () -> Unit,
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val SAMPLE_RATE = 16000.0f
        private const val FRAME_SAMPLES = 640                 // 40 ms @ 16 kHz
        private const val FIRE_COOLDOWN_MS = 3_000L
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model"            // unpacked model under filesDir
        private const val QUEUE_FRAMES = 50                   // ~2 s of 40 ms frames

        // Accuracy gates, ported from rudysev/portal-wake's WakeMatcher (its on-device-tuned
        // policy). A genuine close-mic "hey jarvis" decodes as a bare "hey jarvis" — no "[unk]",
        // both words ~1.0 confidence. Background audio (TV, a nearby phone call) that assembles a
        // wake shows up contaminated ("[unk] hey jarvis") or with a weak lead — these gates reject it.
        private const val UNK_TOKEN = "[unk]"                 // grammar escape + contamination signal
        private const val KEYWORD_MIN_CONF = 0.60             // "jarvis" must clear this
        private const val LEAD_MIN_CONF = 0.80                // "hey" must clear this
        private const val CLEAN_PHRASE_MAX_WORDS = 3          // a real wake is a short, clean phrase
        private const val WARMUP_SILENCE_FRAMES = 25          // ~1 s of silence settles the decoder
        private val LEAD_ALIASES = mapOf("hey" to setOf("hey", "hay"))   // Vosk mishears "hey" as "hay"
    }

    /** One recognized word + its per-word confidence (−1 when the model gave no score). */
    private data class RecWord(val word: String, val conf: Double)

    @Volatile var phrase: String = "hey jarvis"
    // The recognizer is constrained to — and a match requires — the WHOLE phrase
    // ("hey jarvis"). Matching only the last word ("jarvis") let ordinary speech
    // false-trigger: a one-word grammar maps almost any utterance onto that single
    // keyword, so the assistant's own spoken reply kept re-firing the wake.
    private val target get() = phrase.trim().lowercase().ifEmpty { "hey jarvis" }
    // keyword = the salient last word ("jarvis"); lead = the word before it ("hey"). Precision
    // comes from requiring the lead in front of the keyword, not from the keyword alone.
    private val words get() = target.split(' ').filter { it.isNotEmpty() }
    private val keyword get() = words.lastOrNull() ?: "jarvis"
    private val lead get() = if (words.size >= 2) words[words.size - 2] else null

    private val running = AtomicBoolean(false)
    @Volatile private var ready = false
    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)
    private var worker: Thread? = null
    private var lastFireMs = 0L

    // Matches are ignored until this time. The service sets it briefly after a handoff
    // so the assistant's reply (echoed back through the mic) can't immediately re-fire.
    @Volatile private var ignoreUntilMs = 0L
    fun pauseMatching(ms: Long) { ignoreUntilMs = System.currentTimeMillis() + ms }

    fun isRunning() = running.get()

    /** Start (idempotent). Loads/downloads the model off-thread, then decodes from [queue]. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        worker = Thread({ run() }, "portal-ha-wake").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        ready = false
        queue.clear()
        worker = null   // the loop sees running=false and exits, closing Vosk
    }

    /** Called on SoundMonitor's capture thread — copy + enqueue only, never blocks. */
    fun feed(buf: ShortArray, n: Int) {
        if (!running.get() || n <= 0) return
        val frame = buf.copyOf(n)
        if (!queue.offer(frame)) { queue.poll(); queue.offer(frame) }   // drop oldest if behind
    }

    private fun run() {
        val model = loadModel() ?: run {
            Log.w(TAG, "wake: model unavailable — wake word disabled")
            running.set(false); return
        }
        // Grammar biases the decoder toward the wake phrase/keyword/lead; "[unk]" absorbs
        // non-matching speech (so look-alikes aren't forced onto the wake) AND is the
        // contamination signal the gate below rejects. setWords gives per-word confidence.
        val entries = LinkedHashSet<String>().apply {
            add(target); add(keyword); lead?.let { add(it) }; add(UNK_TOKEN)
        }
        val grammar = JSONArray(entries.toList()).toString()
        val recognizer = runCatching { Recognizer(model, SAMPLE_RATE, grammar) }
            .getOrElse { runCatching { Recognizer(model, SAMPLE_RATE) }.getOrNull() }
        if (recognizer == null) {
            Log.w(TAG, "wake: could not create recognizer")
            runCatching { model.close() }; running.set(false); return
        }
        runCatching { recognizer.setWords(true) }
        warmUp(recognizer)          // settle the decoder so the first "hey" isn't dropped
        ready = true
        Log.i(TAG, "wake: recognizer ready (phrase='$target', keyword='$keyword', lead='$lead')")
        try {
            while (running.get()) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                // Only evaluate FINALIZED decodes — partial results are unstable and were a
                // major false-positive source. acceptWaveForm returns true at end-of-utterance.
                if (!recognizer.acceptWaveForm(frame, frame.size)) continue
                val rec = parseResult(recognizer.result)
                if (rec.isEmpty()) continue
                if (isWake(rec)) {
                    val now = System.currentTimeMillis()
                    if (now >= ignoreUntilMs && now - lastFireMs >= FIRE_COOLDOWN_MS) {
                        lastFireMs = now
                        Log.i(TAG, "wake: matched [${render(rec)}] -> firing")
                        recognizer.reset()
                        warmUp(recognizer)
                        queue.clear()
                        runCatching { onWake() }
                    }
                } else if (rec.any { it.word == keyword }) {
                    // Keyword decoded but a gate rejected it — log the near-miss for tuning.
                    Log.i(TAG, "wake: near-miss [${render(rec)}] (rejected)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake: loop error: ${e.message}")
        } finally {
            ready = false
            runCatching { recognizer.close() }
            runCatching { model.close() }
            Log.i(TAG, "wake: stopped")
        }
    }

    // Parse a Vosk final-result JSON into (word, confidence). Prefers the per-word "result"
    // array (from setWords); falls back to the plain transcript with unknown confidence (-1).
    private fun parseResult(json: String): List<RecWord> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("result")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RecWord(o.optString("word").lowercase(), o.optDouble("conf", -1.0))
            }
        }
        val text = obj.optString("text").trim()
        if (text.isEmpty()) return emptyList()
        return text.lowercase().split(Regex("\\s+")).map { RecWord(it, -1.0) }
    }

    // The wake decision (single-phrase port of portal-wake's strict route): the keyword must be
    // present, preceded by a confident lead, in a short phrase with NO "[unk]" contamination.
    private fun isWake(rec: List<RecWord>): Boolean {
        val kw = keyword
        val i = rec.indexOfLast { it.word == kw }
        if (i < 0) return false
        if (rec.any { it.word == UNK_TOKEN }) return false                     // contamination → reject
        if (rec.size > CLEAN_PHRASE_MAX_WORDS) return false                    // must be a clean phrase
        val leads = lead?.let { LEAD_ALIASES[it] ?: setOf(it) }
        if (leads != null) {                                                   // confident lead in front
            val before = rec.subList(0, i)
            if (before.none { it.word in leads && (it.conf < 0 || it.conf >= LEAD_MIN_CONF) }) return false
        }
        if (rec[i].conf >= 0.0 && rec[i].conf < KEYWORD_MIN_CONF) return false  // keyword over its floor
        return true
    }

    /** Render a decode as `word(conf%)` tokens for the fire / near-miss logs. */
    private fun render(rec: List<RecWord>): String =
        rec.joinToString(" ") { if (it.conf < 0) it.word else "${it.word}(${(it.conf * 100).toInt()})" }

    /** Feed ~1 s of silence so Kaldi's online decoder settles — otherwise the first "hey" after a
     *  (re)start or a reset is dropped, causing "no 'hey'" near-misses. Does NOT reset afterward
     *  (that would undo the settling); the trailing silence is harmless in the next utterance. */
    private fun warmUp(rec: Recognizer) {
        runCatching {
            val silence = ShortArray(FRAME_SAMPLES)
            repeat(WARMUP_SILENCE_FRAMES) { rec.acceptWaveForm(silence, silence.size) }
        }
    }

    private fun loadModel(): Model? {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!File(dir, "am").exists()) {
            Log.i(TAG, "wake: model not present — downloading (~40 MB, one time)")
            if (!downloadAndUnzip(dir)) return null
        }
        return runCatching { Model(dir.absolutePath) }
            .onFailure { Log.w(TAG, "wake: model load failed: ${it.message}") }
            .getOrNull()
    }

    // Download the model zip and unpack it into [targetDir], stripping the archive's
    // top-level folder. Unpacks to a .tmp dir first, then renames, so a half-finished
    // download is never treated as a valid model.
    private fun downloadAndUnzip(targetDir: File): Boolean {
        val tmp = File(targetDir.parentFile, "$MODEL_DIR.tmp")
        runCatching { tmp.deleteRecursively() }
        tmp.mkdirs()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "wake: model download HTTP ${conn.responseCode}"); return false
            }
            ZipInputStream(conn.inputStream.buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val rel = entry.name.substringAfter('/', "")   // strip top-level folder
                    if (rel.isNotEmpty()) {
                        val out = File(tmp, rel)
                        if (entry.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                    }
                    zin.closeEntry(); entry = zin.nextEntry
                }
            }
            if (!File(tmp, "am").exists()) { Log.w(TAG, "wake: unpacked model missing 'am/'"); return false }
            runCatching { targetDir.deleteRecursively() }
            val ok = tmp.renameTo(targetDir)
            Log.i(TAG, "wake: model ready ($ok) at ${targetDir.absolutePath}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "wake: model download failed: ${e.message}"); false
        } finally {
            conn?.disconnect()
            runCatching { if (tmp.exists()) tmp.deleteRecursively() }
        }
    }
}
