package com.aeonos.portalha

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.audio.NoAudioSource
import com.pedro.library.util.sources.video.Camera2Source
import com.pedro.rtspserver.RtspServerStream

// Headless camera -> H.264 (+ optional AAC) -> RTSP server. RtspServerStream is
// the source-based (no preview view) variant, so it runs in our background
// service. It owns Camera 0 (and the mic when audio is on) while streaming, so
// it replaces the MJPEG path; motion detection can't run at the same time.
class RtspStreamer(private val context: Context, private val port: Int = 8554) : ConnectChecker {

    companion object { private const val TAG = "PortalHA" }

    private var stream: RtspServerStream? = null
    @Volatile var isStreaming = false
        private set
    // Manual base offset (deg, 0/90/180/270) from the ROTATE button. 0 = camera
    // native landscape. Corrects the base on top of the accelerometer auto value.
    @Volatile var rotationOffset = 0
    // Auto component (deg) from the accelerometer / physical orientation, set by
    // BridgeService's OrientationEventListener — keeps the stream upright as the
    // Portal is physically turned (the OS display rotation is locked).
    @Volatile var autoRotation = 0
    // Called on the ConnectChecker thread when the last client disconnects.
    var onDropped: (() -> Unit)? = null

    // Both Portal+ models ("aloha" 1st-gen, "cipher" 2nd-gen) have a front camera
    // whose usable cam (Camera 0) reports only 1280x720 + 4:3 sizes but whose true
    // FOV is ~SQUARE — so any 16:9 request comes out stretched. Encoding a square
    // surface makes the stream natively display 1:1 in every player with no aspect
    // override (verified on aloha via raw frames; cipher shares the exact camera
    // architecture). 480x480 keeps the native vertical resolution.
    private val squashedFrontCam = android.os.Build.DEVICE.lowercase() in setOf("aloha", "cipher")

    // Capture params from the last start(), reused by restart() on rotation change.
    private var baseWidth = 1280
    private var baseHeight = 720
    private var baseFps = 15
    private var baseBitrate = 2_000_000
    private var baseAudio = true

    fun url() = "rtsp://${BridgeService.localIp() ?: "0.0.0.0"}:$port/"

    private fun currentRotation(): Int = (((rotationOffset + autoRotation) % 360) + 360) % 360

    fun start(width: Int, height: Int, fps: Int, bitrate: Int, withAudio: Boolean): Boolean {
        if (isStreaming) return true
        baseWidth = width; baseHeight = height; baseFps = fps; baseBitrate = bitrate; baseAudio = withAudio
        return runCatching {
            // Portal cameras are all front-facing; RootEncoder defaults to BACK
            // (empty here), so build the source explicitly on FRONT.
            val video = Camera2Source(context)
            if (video.getCameraFacing() != CameraHelper.Facing.FRONT) video.switchCamera()
            // NoAudioSource does NOT open the mic — critical so the RTSP stream
            // doesn't hold the mic and starve/garble Portal calls (and so it doesn't
            // fight the SoundMonitor). prepareAudio() is still called below to satisfy
            // startStream(); that leaves an empty AAC track in the SDP (harmless;
            // HA/WebRTC uses #video=copy). withAudio kept for a future real mic-share.
            val audio = if (withAudio) MicrophoneSource(MediaRecorder.AudioSource.MIC)
                        else NoAudioSource()
            val s = RtspServerStream(context, port, this, video, audio)
            stream = s
            // Pass the LANDSCAPE capture dims + rotation; prepareVideo swaps the
            // ENCODER size itself for 90/270 (don't pre-swap — that double-swaps).
            val rot = currentRotation()
            val corrected = squashedFrontCam && width * 9 == height * 16
            val isCipher = android.os.Build.DEVICE.equals("cipher", true)
            val encW = if (corrected) (if (isCipher) 640 else 480) else width
            val encH = if (corrected) 480 else height
            // Force H.264 Constrained Baseline — WebRTC browser decoders (and most
            // RTSP camera clients) need it. RootEncoder's default is HIGH profile,
            // which WebRTC rejects → "one keyframe then freeze". Fall back to the
            // encoder default if this device can't do Constrained Baseline.
            val profile = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            val level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
            var videoOk = s.prepareVideo(encW, encH, bitrate, fps, 2, rot, profile, level)
            if (!videoOk) {
                Log.w(TAG, "Constrained-Baseline prepare failed; using encoder default profile")
                videoOk = s.prepareVideo(encW, encH, bitrate, fps, 2, rot)
            }
            // Always prepare the audio encoder — startStream() requires it even with
            // NoAudioSource (NoAudioSource just means no mic is opened, no data fed).
            val audioOk = s.prepareAudio(16000, false, 64_000)
            if (videoOk && audioOk) {
                s.startStream()
                isStreaming = true
                Log.i(TAG, "RTSP streaming on ${url()} ${encW}x${encH} rot=$rot squash=$squashedFrontCam (audio=$withAudio)")
                true
            } else {
                Log.w(TAG, "RTSP prepare failed (video=$videoOk audio=$audioOk)")
                runCatching { s.stopStream() }
                stream = null
                false
            }
        }.getOrElse { Log.w(TAG, "RTSP start error: ${it.message}", it); stream = null; false }
    }

    // Apply a new rotation by tearing the stream down and starting fresh with the
    // current rotation in prepareVideo. stopStream() interrupts RtspServer's accept
    // thread (the library leaks an uncaught InterruptedException — BridgeService's
    // crash guard swallows it); the settle delay lets that thread die and port 8554
    // release before the new server binds. Clients reconnect (resolution changes),
    // so only call on an actual orientation change, not continuously.
    fun restart(): Boolean {
        stop()
        runCatching { Thread.sleep(350) }   // let the accept thread die + port release
        return start(baseWidth, baseHeight, baseFps, baseBitrate, baseAudio)
    }

    fun stop() {
        isStreaming = false
        runCatching { stream?.stopStream() }
        stream = null
        Log.i(TAG, "RTSP streaming stopped")
    }

    override fun onConnectionStarted(url: String) { Log.i(TAG, "rtsp client connecting: $url") }
    override fun onConnectionSuccess() { Log.i(TAG, "rtsp client connected") }
    override fun onConnectionFailed(reason: String) { Log.w(TAG, "rtsp failed: $reason") }
    override fun onNewBitrate(bitrate: Long) { }
    override fun onDisconnect() { Log.i(TAG, "rtsp client disconnected"); onDropped?.invoke() }
    override fun onAuthError() { Log.w(TAG, "rtsp auth error") }
    override fun onAuthSuccess() { Log.i(TAG, "rtsp auth success") }
}
