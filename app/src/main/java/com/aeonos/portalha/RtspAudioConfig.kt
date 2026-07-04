package com.aeonos.portalha

import android.media.MediaRecorder
import com.pedro.encoder.utils.CodecUtil

object RtspAudioConfig {
    const val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    val codecType = CodecUtil.CodecType.SOFTWARE
}
