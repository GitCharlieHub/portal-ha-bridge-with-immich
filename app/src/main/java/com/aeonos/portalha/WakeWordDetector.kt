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
        private const val FIRE_COOLDOWN_MS = 3_000L
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model"            // unpacked model under filesDir
        private const val QUEUE_FRAMES = 50                   // ~2 s of 40 ms frames
    }

    @Volatile var phrase: String = "hey jarvis"
    private val keyword get() = phrase.trim().lowercase().substringAfterLast(' ').ifEmpty { "jarvis" }

    private val running = AtomicBoolean(false)
    @Volatile private var ready = false
    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)
    private var worker: Thread? = null
    private var lastFireMs = 0L

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
        val grammar = JSONArray(listOf(keyword, "[unk]")).toString()
        val recognizer = runCatching { Recognizer(model, SAMPLE_RATE, grammar) }.getOrNull()
        if (recognizer == null) {
            Log.w(TAG, "wake: could not create recognizer")
            runCatching { model.close() }; running.set(false); return
        }
        ready = true
        Log.i(TAG, "wake: recognizer ready (keyword='$keyword')")
        try {
            while (running.get()) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val end = recognizer.acceptWaveForm(frame, frame.size)
                val text = runCatching {
                    if (end) JSONObject(recognizer.result).optString("text")
                    else JSONObject(recognizer.partialResult).optString("partial")
                }.getOrDefault("")
                if (text.lowercase().contains(keyword)) {
                    val now = System.currentTimeMillis()
                    if (now - lastFireMs >= FIRE_COOLDOWN_MS) {
                        lastFireMs = now
                        recognizer.reset()
                        queue.clear()
                        Log.i(TAG, "wake: matched '$text' -> firing")
                        runCatching { onWake() }
                    }
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
