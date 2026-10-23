package com.coactive.companion.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * TTS bawaan Android (offline). Disintesis ke file WAV lalu diputar lewat [AudioPlayback]
 * agar mulut tetap sinkron; jika gagal, memakai speak() biasa dengan animasi mulut perkiraan.
 */
class AndroidSystemTts(
    private val context: Context,
    private val player: AudioPlayback,
    private val locale: () -> Locale,
    private val volume: () -> Float = { 1f },
) : TtsEngine {
    private fun params() = android.os.Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume().coerceIn(0f, 1f))
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
    }

    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val pending = HashMap<String, CompletableDeferred<Boolean>>()

    private fun ensure(): TextToSpeech = tts ?: TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.also { t ->
        tts = t
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { synchronized(pending) { pending.remove(id) }?.complete(true) }
            @Deprecated("deprecated") override fun onError(id: String) { synchronized(pending) { pending.remove(id) }?.complete(false) }
            override fun onError(id: String, code: Int) { synchronized(pending) { pending.remove(id) }?.complete(false) }
        })
    }

    override suspend fun speak(text: String, onLevel: (Float) -> Unit) {
        val t = ensure()
        if (withTimeoutOrNull(5000) { ready.await() } != true) error("Android TTS tidak tersedia")
        t.language = locale()
        val clean = TextChunker.forSpeech(text)
        val file = File.createTempFile("systts", ".wav", context.cacheDir)
        val id = "u" + System.nanoTime()
        val done = CompletableDeferred<Boolean>().also { synchronized(pending) { pending[id] = it } }
        val ok = t.synthesizeToFile(clean, params(), file, id) == TextToSpeech.SUCCESS &&
            withTimeoutOrNull(30_000) { done.await() } == true && file.length() > 44
        try {
            if (ok) {
                player.play(TtsAudio.Encoded(file.readBytes(), "wav"), onLevel)
            } else {
                speakDirect(t, clean, onLevel)
            }
        } finally { file.delete() }
    }

    private suspend fun speakDirect(t: TextToSpeech, text: String, onLevel: (Float) -> Unit) = coroutineScope {
        val id = "d" + System.nanoTime()
        val done = CompletableDeferred<Boolean>().also { synchronized(pending) { pending[id] = it } }
        t.setAudioAttributes(TtsAudioAttributes.media)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params(), id)
        val anim = launch {
            var ph = 0.0
            while (true) { ph += 0.5; onLevel((abs(sin(ph)) * 0.7).toFloat()); delay(60) }
        }
        try { withTimeoutOrNull(60_000) { done.await() } } finally {
            anim.cancel(); t.stop(); onLevel(0f)
        }
    }

    fun stop() { tts?.stop() }
    fun shutdown() { tts?.shutdown(); tts = null; ready = CompletableDeferred() }
}
