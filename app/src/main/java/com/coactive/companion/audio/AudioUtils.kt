package com.coactive.companion.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

object Wav {
    /** Membungkus PCM16 mono little-endian menjadi file WAV. */
    fun fromPcm16(samples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }

    /** Membaca PCM16 mono dari WAV sederhana (mencari chunk "data"). */
    fun readPcm16(wav: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(len, wav.size - pos - 8) / 2
                return ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + len + (len and 1)
        }
        error("Chunk data WAV tidak ditemukan")
    }
}

object Level {
    fun rms(samples: ShortArray, count: Int = samples.size): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i] / 32768.0; sum += v * v }
        return sqrt(sum / count).toFloat()
    }

    fun rmsPcm16Bytes(bytes: ByteArray, len: Int = bytes.size): Float {
        val n = len / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort()
            val v = s / 32768.0; sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    /** Memetakan RMS ke 0..1 untuk bukaan mulut (skala dB, -50 dB = tertutup, -12 dB = terbuka penuh). */
    fun mouthOpenness(rms: Float): Float {
        if (rms <= 1e-6f) return 0f
        val db = 20 * log10(rms)
        return ((db + 50f) / 38f).coerceIn(0f, 1f)
    }
}

/** Penghalus sederhana: naik cepat, turun lebih lambat — agar mulut terlihat natural. */
class Smoother(private val attack: Float = 0.6f, private val release: Float = 0.25f) {
    var value = 0f; private set
    fun next(target: Float): Float {
        val k = if (target > value) attack else release
        value += (target - value) * k
        return value
    }
}

/**
 * Deteksi akhir ucapan berbasis energi (VAD sederhana).
 * Diberi frame RMS satu per satu; mengembalikan true saat rekaman harus berhenti.
 */
class EndpointDetector(
    frameMs: Int = 80,
    private val minThreshold: Float = 0.012f,
    noSpeechTimeoutMs: Int = 6000,
    trailingSilenceMs: Int = 1000,
    maxUtteranceMs: Int = 15000,
    /** Ambang = noiseFloor × [noiseFactor] (bila lebih besar dari [minThreshold]). */
    private val noiseFactor: Float = 3f,
    initialNoiseFloor: Float = 0.005f,
) {
    private val noSpeechFrames = noSpeechTimeoutMs / frameMs
    private val silenceFrames = trailingSilenceMs / frameMs
    private val maxFrames = maxUtteranceMs / frameMs
    private var frames = 0
    private var speechRun = 0
    private var silenceRun = 0
    private var noiseFloor = initialNoiseFloor
    var speechDetected = false; private set

    fun threshold() = maxOf(minThreshold, noiseFloor * noiseFactor)

    fun feed(rms: Float): Boolean {
        frames++
        val loud = rms > threshold()
        if (!speechDetected) {
            if (!loud) noiseFloor = noiseFloor * 0.9f + rms * 0.1f
            speechRun = if (loud) speechRun + 1 else 0
            if (speechRun >= 2) speechDetected = true
            return frames >= noSpeechFrames
        }
        silenceRun = if (loud) 0 else silenceRun + 1
        return silenceRun >= silenceFrames || frames >= maxFrames
    }
}
