package com.coactive.companion

import com.coactive.companion.wakeword.WakeModels
import com.coactive.companion.wakeword.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {
    private class FakeModels(val scoreFn: (Int) -> Float) : WakeModels {
        var melCalls = 0; var lastMelInput = 0; var embedCalls = 0; var scoreCalls = 0; var lastEmbedFrames = 0
        override val featureWindow = 16
        override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
            melCalls++; lastMelInput = samples.size
            return Array(8) { FloatArray(32) { samples.last() } }
        }
        override fun embed(mel: Array<FloatArray>): FloatArray { embedCalls++; lastEmbedFrames = mel.size; return FloatArray(96) }
        override fun score(features: Array<FloatArray>): Float { scoreCalls++; assertEquals(16, features.size); return scoreFn(scoreCalls) }
        override fun close() {}
    }

    private val chunk = ShortArray(1280) { 100 }

    @Test fun pipelineBuffersLikeOpenWakeWord() {
        val m = FakeModels { 0.1f }
        val d = WakeWordDetector(m)
        assertFalse(d.process(chunk))          // 1280 < 1760 sampel: belum ada mel
        assertEquals(0, m.melCalls)
        repeat(15) { assertFalse(d.process(chunk)) }
        assertEquals(15, m.melCalls); assertEquals(1760, m.lastMelInput)
        assertEquals(76, m.lastEmbedFrames)
        assertEquals(0, m.scoreCalls)          // baru 15 embedding
        d.process(chunk)
        assertEquals(1, m.scoreCalls)
    }

    @Test fun triggersThenCoolsDown() {
        val m = FakeModels { n -> if (n == 3) 0.9f else 0.1f }
        val d = WakeWordDetector(m, threshold = 0.5f, cooldownChunks = 5)
        val results = (0 until 60).map { d.process(chunk) }
        assertEquals(1, results.count { it })
        assertTrue(d.lastScore < 0.5f)
    }
}
