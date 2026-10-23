package com.coactive.companion.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

object TtsAudioAttributes {
    /** Suara asisten diputar di stream MEDIA (STREAM_MUSIC) dengan konten SPEECH. */
    val media: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

/**
 * Meminta audio focus selama asisten bicara (musik lain diturunkan/dijeda sementara),
 * lalu melepasnya. Tidak mengubah volume sistem pengguna.
 */
class FocusedTtsEngine(context: Context, private val inner: TtsEngine) : TtsEngine {
    private val am = context.getSystemService(AudioManager::class.java)

    override suspend fun speak(text: String, onLevel: (Float) -> Unit) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(TtsAudioAttributes.media)
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am?.requestAudioFocus(req) }
        try {
            inner.speak(text, onLevel)
        } finally {
            runCatching { am?.abandonAudioFocusRequest(req) }
        }
    }
}
