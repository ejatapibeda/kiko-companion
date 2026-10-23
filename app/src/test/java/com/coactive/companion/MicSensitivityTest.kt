package com.coactive.companion

import com.coactive.companion.audio.MicGain
import com.coactive.companion.audio.MicSensitivity
import com.coactive.companion.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MicSensitivityTest {
    @Test fun neutralMatchesLegacyBehaviour() {
        val s = MicSensitivity.NEUTRAL
        assertEquals(1f, MicSensitivity.gain(s), 1e-4f)
        assertEquals(1f, MicSensitivity.thresholdScale(s), 1e-6f)
        assertEquals(6000, MicSensitivity.noSpeechTimeoutMs(s))
        assertEquals(1000, MicSensitivity.trailingSilenceMs(s))
        assertEquals(0.5f, MicSensitivity.wakeThreshold(0.5f, s), 1e-6f)
        assertEquals(3f, MicSensitivity.noiseFactor(s), 1e-6f)
    }

    @Test fun defaultIsSlightlyMoreSensitive() {
        val d = AppSettings().micSensitivity
        assertEquals(MicSensitivity.DEFAULT, d)
        assertTrue(d > MicSensitivity.NEUTRAL && d < 0.8f)
        assertTrue(MicSensitivity.gain(d) > 1.3f && MicSensitivity.gain(d) < 2.5f)
        assertTrue(MicSensitivity.thresholdScale(d) < 1f)
        assertTrue(MicSensitivity.noSpeechTimeoutMs(d) > 6000)
        assertTrue(MicSensitivity.wakeThreshold(0.5f, d) < 0.5f && MicSensitivity.wakeThreshold(0.5f, d) > 0.44f)
    }

    @Test fun mappingIsMonotonicAndBounded() {
        var prevGain = 0f; var prevThr = 10f; var prevTimeout = 0; var prevWake = 2f
        var s = 0f
        while (s <= 1.0001f) {
            val g = MicSensitivity.gain(s); val t = MicSensitivity.thresholdScale(s)
            val to = MicSensitivity.noSpeechTimeoutMs(s); val w = MicSensitivity.wakeThreshold(0.5f, s)
            assertTrue(g >= prevGain && t <= prevThr && to >= prevTimeout && w <= prevWake)
            assertTrue(g in 0.49f..8f && t in 0.5f..1.5f && to in 5000..8000 && w in 0.42f..0.56f)
            assertTrue(MicSensitivity.trailingSilenceMs(s) in 900..1300)
            prevGain = g; prevThr = t; prevTimeout = to; prevWake = w
            s += 0.05f
        }
        assertEquals(18f, MicSensitivity.gainDb(1f), 1e-3f)
        assertEquals(-6f, MicSensitivity.gainDb(0f), 1e-3f)
        // wake word: penurunan modest (maks −15%), tidak di bawah batas aman
        assertEquals(0.425f, MicSensitivity.wakeThreshold(0.5f, 1f), 1e-4f)
        assertEquals(0.05f, MicSensitivity.wakeThreshold(0.03f, 1f), 1e-6f)
        assertEquals(0.99f, MicSensitivity.wakeThreshold(0.99f, 0f), 1e-6f)
    }

    @Test fun invalidValuesAreSanitized() {
        assertEquals(MicSensitivity.DEFAULT, MicSensitivity.sanitize(Float.NaN))
        assertEquals(1f, MicSensitivity.sanitize(7f)); assertEquals(0f, MicSensitivity.sanitize(-3f))
        assertTrue(MicSensitivity.gain(Float.NaN).isFinite())
        assertEquals(0, MicSensitivity.percent(0f)); assertEquals(65, MicSensitivity.percent(0.65f)); assertEquals(100, MicSensitivity.percent(1f))
    }

    @Test fun endpointDetectorUsesScaledThreshold() {
        val loud = MicSensitivity.endpointDetector(1f); val neutral = MicSensitivity.endpointDetector(0.5f)
        assertEquals(0.015f, neutral.threshold(), 1e-6f) // perilaku lama: max(0.012, 0.005×3)
        assertEquals(0.006f, loud.threshold(), 1e-6f)
        // suara lirih (rms 0.008): tidak terdeteksi di netral, terdeteksi di sensitivitas maks
        val quiet = 0.008f
        repeat(3) { neutral.feed(quiet); loud.feed(quiet) }
        assertTrue(loud.speechDetected); assertTrue(!neutral.speechDetected)
    }

    @Test fun gainUnityReturnsSameArray() {
        val f = ShortArray(10) { (it * 100).toShort() }
        assertSame(f, MicGain.apply(f, 1f))
    }

    @Test fun gainAmplifiesLinearlyBelowKnee() {
        val f = shortArrayOf(1000, -1000, 3000, 0)
        val out = MicGain.apply(f, 2f)
        assertEquals(2000.0, out[0].toDouble(), 2.0); assertEquals(-2000.0, out[1].toDouble(), 2.0); assertEquals(6000.0, out[2].toDouble(), 2.0); assertEquals(0, out[3].toInt())
        assertEquals(1000.toShort(), f[0]) // input tidak diubah
    }

    @Test fun gainNeverClipsHard() {
        val f = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 20000, -20000, 30000)
        val out = MicGain.apply(f, 8f)
        for (v in out) assertTrue(abs(v.toInt()) <= 32767)
        // kompresi halus: monotonik, sampel lebih besar tetap ≥ lebih kecil
        assertTrue(out[0] >= out[4] && out[4] >= out[2])
        var prev = -1
        for (x in 0..32767 step 97) { val y = MicGain.sample(x.toShort(), 4f).toInt(); assertTrue(y >= prev); prev = y }
    }
}
