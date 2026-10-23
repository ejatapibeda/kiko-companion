package com.coactive.companion

import android.media.MediaRecorder
import com.coactive.companion.audio.MicSourcePolicy
import com.coactive.companion.audio.SilenceWatch
import com.coactive.companion.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicPreprocessTest {
    @Test fun noiseSuppressionDefaultsOn() = assertTrue(AppSettings().noiseSuppression)

    @Test fun sourceSelection() {
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicSourcePolicy.pick(enhance = true, voiceCommBroken = false))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicSourcePolicy.pick(enhance = true, voiceCommBroken = true))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicSourcePolicy.pick(enhance = false, voiceCommBroken = false))
    }

    @Test fun allZeroStartTriggersFallback() {
        val w = SilenceWatch(limit = 3)
        assertFalse(w.feed(ShortArray(4))); assertFalse(w.feed(ShortArray(4))); assertTrue(w.feed(ShortArray(4)))
    }

    @Test fun realAudioNeverTriggersFallback() {
        val w = SilenceWatch(limit = 3)
        assertFalse(w.feed(shortArrayOf(0, 3, -2, 0)))
        repeat(10) { assertFalse(w.feed(ShortArray(4))) } // sunyi setelah ada sinyal bukan kerusakan
    }
}
