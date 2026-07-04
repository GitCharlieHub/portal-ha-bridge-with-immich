package com.aeonos.portalha

import android.media.MediaRecorder
import com.pedro.encoder.utils.CodecUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class RtspAudioConfigTest {
    @Test
    fun usesPortalMicSourceAndSoftwareAacForCompatibility() {
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, RtspAudioConfig.audioSource)
        assertEquals(CodecUtil.CodecType.SOFTWARE, RtspAudioConfig.codecType)
    }
}
