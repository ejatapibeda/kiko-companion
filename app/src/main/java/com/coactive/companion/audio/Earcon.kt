package com.coactive.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Bunyi "ting" singkat saat mulai/selesai mendengarkan (mirip chime asisten).
 * Disintesis langsung (tanpa file aset → tanpa isu lisensi).
 */
object Earcon {
    const val SAMPLE_RATE = 24000

    data class Tone(val freqHz: Double, val ms: Int)

    /** Mulai mendengarkan: dua nada naik (E6 → A6), total ~200 ms. */
    val START = listOf(Tone(1318.5, 85), Tone(1760.0, 115))
    /** Selesai mendengarkan: dua nada turun, lebih lembut. */
    val END = listOf(Tone(1568.0, 70), Tone(1174.7, 100))

    /** Membuat PCM16 mono: sinus + sedikit harmonik, envelope attack 6 ms & decay eksponensial agar tidak "klik". */
    fun synthesize(tones: List<Tone>, gain: Float = 0.6f, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = tones.sumOf { it.ms } * sampleRate / 1000
        val out = ShortArray(total)
        var pos = 0
        for (t in tones) {
            val n = t.ms * sampleRate / 1000
            val attack = sampleRate * 6 / 1000
            for (i in 0 until n) {
                val time = i.toDouble() / sampleRate
                val env = min(1.0, i.toDouble() / attack) * Math.exp(-3.5 * i / n)
                val v = (sin(2 * PI * t.freqHz * time) + 0.2 * sin(4 * PI * t.freqHz * time)) / 1.2
                out[pos + i] = (v * env * gain * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n
        }
        return out
    }

    fun durationMs(tones: List<Tone>) = tones.sumOf { it.ms }

    /** Memutar earcon tanpa memblokir (AudioTrack static). */
    fun play(scope: CoroutineScope, tones: List<Tone>, gain: Float = 0.6f) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val pcm = synthesize(tones, gain)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.setVolume(1f)
                track.play()
                delay(durationMs(tones) + 80L)
                track.release()
            }
        }
    }
}
