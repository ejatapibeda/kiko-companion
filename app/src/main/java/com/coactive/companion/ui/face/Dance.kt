package com.coactive.companion.ui.face

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign

/**
 * Satu "pose" joget pada waktu [beats] (satuan ketukan). Semua nilai relatif radius mata;
 * amplitudo sudah dikali `amount` (0..1, di-clamp) sehingga 0 = tidak ada efek sama sekali.
 */
data class DanceFrame(
    val bounceY: Float = 0f, val tilt: Float = 0f, val squash: Float = 0f,
    val lookX: Float = 0f, val lookY: Float = 0f,
    /** 0..1: mata "^" senang (ketukan 3–4 tiap birama). */
    val happy: Float = 0f,
    /** 0..1: mulut terbuka di ketukan. */
    val mouthOpen: Float = 0f,
    /** 0..1: denyut cahaya mata di ketukan. */
    val glow: Float = 0f,
    /** Kilau mata ikut terayun kecil (satuan radius, negatif = naik). */
    val glintBob: Float = 0f,
    val amount: Float = 0f,
)

/** Not musik (♪ / ♫) yang melayang naik di samping wajah. [age] 0..1, [alpha] 0..1. */
data class MusicNote(
    val side: Int, val age: Float, val alpha: Float, val scale: Float, val double: Boolean,
    /** 0 = warna aksen (biru), 1 = pink, 2 = lavender — variasi agar tidak monoton. */
    val hue: Int = 0,
    /** 0..1: jalur horizontal (jarak dari mata) agar not tidak menumpuk. */
    val lane: Float = 0.5f,
)

/** Kilau kecil ✦ di sekitar wajah yang berkedip di ketukan. [slot] 0..3 = posisi, [alpha]/[scale] 0..1. */
data class DanceSparkle(val slot: Int, val alpha: Float, val scale: Float)

/**
 * Animasi joget berbasis ketukan (default [DEFAULT_BPM]). Fungsi murni → bisa diuji & dipratinjau per frame.
 *  - lompat kecil tiap ketukan (mendarat tepat di ketukan, sedikit gepeng saat mendarat),
 *  - goyang kiri–kanan 2 ketukan sekali, mata melirik kiri di ketukan 1, kanan di ketukan 2,
 *  - mata "^" senang di ketukan 3–4 tiap birama, mulut senyum terbuka berdenyut di ketukan,
 *  - denyut cahaya mata + not musik kecil sesekali.
 */
object DanceMotion {
    /** Tempo tetap yang enak (Spotify audio-features tidak dipakai; endpoint itu dibatasi untuk aplikasi baru). */
    const val DEFAULT_BPM = 116f
    /** Panjang siklus waktu sebelum di-wrap (kelipatan birama 4 & pola not 8) — mencegah float kehilangan presisi. */
    const val CYCLE_BEATS = 16f

    fun frame(beats: Float, amount: Float): DanceFrame {
        val a = amount.clampUnit()
        val t = if (beats.isNaN() || beats.isInfinite()) 0f else beats
        if (a <= 0f) return DanceFrame()
        val sway = -cos(PI * t).toFloat()                     // -1 di ketukan genap (kiri), +1 di ketukan ganjil (kanan)
        val side = sign(sway) * abs(sway).pow(0.55f)          // tahan sebentar di sisi
        val hop = abs(sin(PI * t)).toFloat()                  // 0 saat mendarat (ketukan), 1 di tengah
        val land = max(0f, cos(2 * PI * t).toFloat()).pow(4)  // denyut tajam tepat di ketukan
        val inBar = ((t / 4f) - floor(t / 4f)) * 4f           // 0..4
        val happy = (smooth(1.75f, 2.25f, inBar) * (1f - smooth(3.7f, 4f, inBar))).clampUnit()
        return DanceFrame(
            bounceY = -0.13f * hop * a,
            tilt = 5f * side * a,
            squash = (0.06f * hop - 0.07f * land) * a,
            lookX = 0.34f * side * a,
            lookY = -0.06f * hop * a,
            happy = happy * a,
            mouthOpen = ((0.5f + 0.5f * cos(2 * PI * t).toFloat()) * a).clampUnit(),
            glow = (land * a).clampUnit(),
            glintBob = -0.05f * hop * a,
            amount = a,
        )
    }

    /** Terapkan pose ke parameter wajah (dipanggil sebelum reaksi sentuhan → reaksi tetap jalan). */
    fun apply(p: FaceParams, f: DanceFrame): FaceParams {
        val a = f.amount.clampUnit()
        if (a <= 0f) return p
        fun l(x: Float, y: Float, k: Float) = x + (y - x) * k.clampUnit()
        val o = f.mouthOpen.clampUnit()
        val groove = MouthShape(1.12f + 0.06f * o, -0.22f * o, 0.6f + 0.2f * o)
        val h = f.happy.clampUnit()
        return p.copy(
            openL = l(p.openL, 0f, h), openR = l(p.openR, 0f, h),
            arcL = l(p.arcL, -1f, h), arcR = l(p.arcR, -1f, h),
            lookX = p.lookX + f.lookX, lookY = p.lookY + f.lookY,
            sparkle = max(p.sparkle, (0.5f * (1f - h) * a).clampUnit()),
            mouth = p.mouth.lerp(groove, a),
            tilt = p.tilt + f.tilt, bounceY = p.bounceY + f.bounceY, squash = p.squash + f.squash,
            glintY = p.glintY + f.glintBob,
            glowPulse = max(p.glowPulse, f.glow.clampUnit()),
        )
    }

    /** Not yang terlihat pada [beats]: satu lahir tiap 2 ketukan (kadang dilewati), hidup 3 ketukan, sisi bergantian. */
    /** Umur satu not dalam ketukan (muncul tiap ketukan, bergantian kiri/kanan → maks. 3 not sekaligus). */
    const val NOTE_LIFE_BEATS = 2.6f
    const val EQ_BARS = 7

    fun notes(beats: Float, amount: Float): List<MusicNote> {
        val a = amount.clampUnit()
        if (a <= 0.01f || beats.isNaN() || beats.isInfinite()) return emptyList()
        val k0 = floor(beats).toInt()
        val span = kotlin.math.ceil(NOTE_LIFE_BEATS).toInt()
        return (k0 - span..k0).mapNotNull { k ->
            val slot = Math.floorMod(k, CYCLE_BEATS.toInt())
            if (slot % 8 == 7) return@mapNotNull null // jeda kecil tiap 2 birama → terasa berirama, tidak ramai
            val age = (beats - k) / NOTE_LIFE_BEATS
            if (age < 0f || age >= 1f) return@mapNotNull null
            // cepat muncul, pelan memudar
            val alpha = (smooth(0f, 0.12f, age) * (1f - smooth(0.55f, 1f, age)) * 0.85f * a).clampUnit()
            MusicNote(
                side = if (slot % 2 == 0) -1 else 1, age = age, alpha = alpha,
                scale = (0.8f + 0.12f * (slot % 3)) * (0.85f + 0.15f * smooth(0f, 0.2f, age)),
                double = slot % 4 == 1, hue = (slot / 2) % 3, lane = ((slot * 5) % 7) / 6f,
            )
        }
    }

    /** Denyut ketukan 0..1 (tinggi tepat di ketukan, meluruh cepat). */
    fun beatPulse(beats: Float, amount: Float): Float {
        val a = amount.clampUnit()
        if (a <= 0.01f || !beats.isFinite()) return 0f
        val f = beats - floor(beats)
        val accent = if (Math.floorMod(floor(beats).toInt(), 4) == 0) 1f else 0.7f // ketukan 1 tiap birama lebih kuat
        return ((1f - f).pow(3) * accent * a).clampUnit()
    }

    /** Tinggi bar equalizer mini 0..1 ([n] bar). Periodik tiap 4 ketukan, ikut denyut ketukan. */
    fun eqLevels(beats: Float, amount: Float, n: Int = EQ_BARS): FloatArray {
        val a = amount.clampUnit()
        val out = FloatArray(n.coerceAtLeast(0))
        if (a <= 0.01f || !beats.isFinite()) return out
        val pulse = beatPulse(beats, 1f)
        for (i in out.indices) {
            val ph = i * 0.37f
            val w = 0.5f + 0.5f * sin(2 * PI * (beats * (if (i % 2 == 0) 0.5f else 0.75f) + ph)).toFloat()
            val center = 1f - abs(i - (n - 1) / 2f) / ((n - 1) / 2f + 1f) * 0.45f // tengah sedikit lebih tinggi
            out[i] = ((0.22f + 0.48f * w + 0.3f * pulse) * center * a).clampUnit()
        }
        return out
    }

    /** Kilau di ketukan: satu posisi baru tiap ketukan, hidup ~1 ketukan. */
    fun sparkles(beats: Float, amount: Float): List<DanceSparkle> {
        val a = amount.clampUnit()
        if (a <= 0.01f || !beats.isFinite()) return emptyList()
        return (0..1).mapNotNull { back ->
            val k = floor(beats).toInt() - back
            val age = (beats - k) / 1.6f
            if (age !in 0f..1f) return@mapNotNull null
            val s = sin(PI * age).toFloat()
            DanceSparkle(Math.floorMod(k * 3, 4), (s * 0.9f * a).clampUnit(), (0.6f + 0.4f * s).clampUnit())
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).clampUnit()
        return t * t * (3 - 2 * t)
    }
}
