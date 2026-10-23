package com.coactive.companion

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coactive.companion.audio.Wav
import com.coactive.companion.settings.SettingsRepository
import com.coactive.companion.settings.TtsProvider
import com.coactive.companion.tts.AudioPlayback
import com.coactive.companion.tts.TtsAudio
import com.coactive.companion.wakeword.OnnxWakeModels
import com.coactive.companion.wakeword.WakeWordDetector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun maxScore(asset: String): Pair<Float, Int> {
        val pcm = Wav.readPcm16(testAssets.open(asset).readBytes())
        val models = OnnxWakeModels.load(ctx, "hey_jarvis_v0.1.onnx")
        val det = WakeWordDetector(models, threshold = 0.5f)
        var best = 0f; var triggers = 0
        val t0 = System.nanoTime()
        var i = 0
        while (i + 1280 <= pcm.size) {
            if (det.process(pcm.copyOfRange(i, i + 1280))) triggers++
            best = maxOf(best, det.lastScore); i += 1280
        }
        Log.i("CompanionTest", "$asset best=$best triggers=$triggers chunks=${pcm.size / 1280} ms=${(System.nanoTime() - t0) / 1e6}")
        models.close()
        return best to triggers
    }

    /** Pipeline openWakeWord ONNX (mel -> embedding -> hey_jarvis) mendeteksi klip "Hey Jarvis". */
    @Test fun wakeWordDetectsHeyJarvis() {
        val (best, triggers) = maxScore("hey_jarvis_sample.wav")
        assertTrue("score=$best", best > 0.5f)
        assertEquals(1, triggers)
    }

    @Test fun wakeWordIgnoresOtherSpeech() {
        val (best, triggers) = maxScore("negative_sample.wav")
        assertTrue("score=$best", best < 0.5f)
        assertEquals(0, triggers)
    }

    /** MP3 dari TikTok TTS didekode (MediaCodec) dan diputar; level mulut ikut bergerak. */
    @Test fun playsMp3WithMouthLevels() = runBlocking {
        val mp3 = testAssets.open("tiktok_sample.mp3").readBytes()
        val levels = mutableListOf<Float>()
        withTimeout(20_000) { AudioPlayback(ctx.cacheDir).play(TtsAudio.Encoded(mp3, "mp3")) { levels += it } }
        Log.i("CompanionTest", "levels=${levels.size} max=${levels.maxOrNull()}")
        assertTrue("levels=${levels.size}", levels.size > 20)
        assertTrue("max=${levels.maxOrNull()}", (levels.maxOrNull() ?: 0f) > 0.3f)
    }

    /** Pengaturan tersimpan terenkripsi dan terbaca ulang. */
    @Test fun settingsPersistEncrypted() {
        val repo = SettingsRepository(ctx)
        val before = repo.settings.value
        repo.update { it.copy(llmModel = "test-model", ttsProvider = TtsProvider.ELEVENLABS, elevenApiKey = "secret-123") }
        val again = SettingsRepository(ctx).settings.value
        assertEquals("test-model", again.llmModel); assertEquals(TtsProvider.ELEVENLABS, again.ttsProvider); assertEquals("secret-123", again.elevenApiKey)
        val raw = ctx.getSharedPreferences("companion_secure_prefs", 0).all.toString()
        assertTrue("plain text found", !raw.contains("secret-123") && !raw.contains("test-model"))
        repo.update { before }
    }
}
