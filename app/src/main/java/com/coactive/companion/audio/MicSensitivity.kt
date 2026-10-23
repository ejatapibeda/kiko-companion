package com.coactive.companion.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Pemetaan slider "Sensitivitas mic" (0..1) ke parameter tangkap suara. Fungsi murni → bisa diuji.
 *
 *  - 0.5 = perilaku lama persis (gain 0 dB, ambang VAD 0.012, timeout 6 s, jeda akhir 1 s, ambang wake word apa adanya).
 *  - > 0.5 = lebih peka: gain input naik s/d +18 dB, ambang suara turun s/d ½, menunggu ucapan lebih lama
 *    (s/d 8 s sebelum "Aku tidak dengar apa-apa"), jeda akhir ucapan sedikit lebih panjang agar kata lirih
 *    di akhir kalimat tidak terpotong, ambang wake word turun sedikit (maks −15%, agar tidak sering salah picu).
 *  - < 0.5 = kurang peka (ruang bising): gain turun s/d −6 dB, ambang naik s/d 1.5×.
 */
object MicSensitivity {
    /** Default sedikit lebih peka dari perilaku lama (0.5). */
    const val DEFAULT = 0.65f
    const val NEUTRAL = 0.5f

    private const val MAX_BOOST_DB = 18f
    private const val MAX_CUT_DB = 6f

    fun sanitize(s: Float): Float = if (s.isNaN()) DEFAULT else s.coerceIn(0f, 1f)

    /** Gain input dalam dB: −6 … 0 (di 0.5) … +18. */
    fun gainDb(s: Float): Float {
        val v = sanitize(s)
        return if (v >= NEUTRAL) (v - NEUTRAL) / NEUTRAL * MAX_BOOST_DB else -(NEUTRAL - v) / NEUTRAL * MAX_CUT_DB
    }

    /** Gain linear (1.0 = tidak diubah). */
    fun gain(s: Float): Float = 10f.pow(gainDb(s) / 20f)

    /** Pengali ambang energi VAD (setelah gain): 1.5 … 1.0 (di 0.5) … 0.5. */
    fun thresholdScale(s: Float): Float = 1.5f - sanitize(s)

    /** Pengali noise floor untuk ambang adaptif: 3.6 … 3.0 (di 0.5) … 2.4 (tetap jauh di atas bising latar). */
    fun noiseFactor(s: Float): Float = 3f - 1.2f * (sanitize(s) - NEUTRAL)

    /** Batas tunggu tanpa ucapan sebelum "tidak dengar apa-apa": 5 s … 6 s … 8 s. */
    fun noSpeechTimeoutMs(s: Float): Int {
        val v = sanitize(s)
        return if (v >= NEUTRAL) (6000 + (v - NEUTRAL) / NEUTRAL * 2000).toInt() else (6000 - (NEUTRAL - v) / NEUTRAL * 1000).toInt()
    }

    /** Hening di akhir ucapan sebelum berhenti merekam: 900 ms … 1000 ms … 1300 ms. */
    fun trailingSilenceMs(s: Float): Int {
        val v = sanitize(s)
        return if (v >= NEUTRAL) (1000 + (v - NEUTRAL) / NEUTRAL * 300).toInt() else (1000 - (NEUTRAL - v) / NEUTRAL * 100).toInt()
    }

    /** Ambang wake word efektif: diturunkan maks 15% (peka) / dinaikkan maks 10% (kurang peka). */
    fun wakeThreshold(base: Float, s: Float): Float {
        val v = sanitize(s)
        val k = if (v >= NEUTRAL) 1f - 0.15f * (v - NEUTRAL) / NEUTRAL else 1f + 0.10f * (NEUTRAL - v) / NEUTRAL
        val b = if (base.isNaN()) 0.5f else base
        return (b * k).coerceIn(0.05f, 0.99f)
    }

    /** VAD dengan ambang/timeout sesuai sensitivitas. */
    fun endpointDetector(s: Float): EndpointDetector = EndpointDetector(
        minThreshold = 0.012f * thresholdScale(s),
        noSpeechTimeoutMs = noSpeechTimeoutMs(s),
        trailingSilenceMs = trailingSilenceMs(s),
        noiseFactor = noiseFactor(s),
        initialNoiseFloor = 0.005f * thresholdScale(s),
    )

    /** Persentase untuk label slider (0..100). */
    fun percent(s: Float): Int = (sanitize(s) * 100f + 0.5f).toInt()
}

/**
 * Gain perangkat lunak pada PCM16 dengan pelindung clipping: linier sampai [KNEE] skala penuh, di atasnya
 * dikompres halus (tanh) sehingga tidak pernah melewati ±32767 dan tidak "pecah" keras.
 */
object MicGain {
    const val KNEE = 0.7f

    /** Mengembalikan [frame] apa adanya bila gain ≈ 1, selain itu array baru (input tidak diubah). */
    fun apply(frame: ShortArray, gain: Float): ShortArray {
        if (gain.isNaN() || abs(gain - 1f) < 1e-3f) return frame
        val g = gain.coerceIn(0f, 16f)
        return ShortArray(frame.size) { i -> sample(frame[i], g) }
    }

    fun sample(x: Short, g: Float): Short {
        val v = x / 32768f * g
        val a = abs(v)
        val y = if (a <= KNEE) a else KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        val out = (if (v < 0) -y else y) * 32767f
        return out.toInt().coerceIn(-32767, 32767).toShort()
    }
}
