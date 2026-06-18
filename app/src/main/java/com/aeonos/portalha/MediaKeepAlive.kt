package com.aeonos.portalha

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

// Makes the device look like it is actively playing media. Portal's launcher
// (com.facebook.alohaapps.launcher) fires an idle return-to-home / Superframe
// a few minutes after nothing is "active" (no call, no camera, no media).
// Two signals are published, replicating the old keep-a-video-playing trick:
//   1. a silent looping AudioTrack on the media stream
//   2. a MediaSession in STATE_PLAYING
// No audio focus is requested, so real playback is never paused or ducked.
class MediaKeepAlive {

    companion object {
        private const val TAG = "PortalHA"
        private const val SAMPLE_RATE = 8000
    }

    private var track: AudioTrack? = null
    private var session: MediaSession? = null

    fun start(context: Context) {
        if (track == null) runCatching {
            val silence = ShortArray(SAMPLE_RATE) // 1 s of silence, looped forever
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.size * 2)
                .build()
            t.write(silence, 0, silence.size)
            t.setLoopPoints(0, silence.size, -1)
            t.play()
            track = t
            Log.i(TAG, "media keepalive started (silent loop)")
        }.onFailure { Log.w(TAG, "media keepalive audio failed: ${it.message}") }

        if (session == null) runCatching {
            val s = MediaSession(context, "PortalHA")
            s.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Home Assistant Dashboard")
                .build())
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build())
            s.isActive = true
            session = s
            Log.i(TAG, "media keepalive session active (STATE_PLAYING)")
        }.onFailure { Log.w(TAG, "media keepalive session failed: ${it.message}") }
    }

    fun stop() {
        track?.let {
            runCatching { it.stop(); it.release() }
            Log.i(TAG, "media keepalive stopped")
        }
        track = null
        session?.let {
            runCatching { it.isActive = false; it.release() }
        }
        session = null
    }
}
