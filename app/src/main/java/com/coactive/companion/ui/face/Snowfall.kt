package com.coactive.companion.ui.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Logika salju (murni, tanpa Compose, bisa dites di JVM): bentuk kepingan salju 6 lengan bercabang + posisi/rotasi/alpha
 * tiap partikel dari waktu (deterministik, tanpa alokasi per frame — hasil ditulis ke [Flake] yang dipakai ulang).
 */
object Snowfall {
    /** Jumlah partikel (sama seperti versi titik lama → beban setara). */
    const val COUNT = 42
    /** Jumlah varian bentuk kristal. */
    const val VARIANTS = 3
    /** Langkah ukuran per tingkat (dibulatkan agar Path bisa di-cache per ukuran). */
    const val SIZE_STEPS = 4

    enum class Tier { DOT, SMALL, MEDIUM, LARGE }

    class Flake {
        var x = 0f; var y = 0f
        /** Jari-jari kepingan (px). */
        var radius = 0f
        /** Derajat. */
        var rotation = 0f
        var alpha = 0f
        var tier = Tier.DOT
        var variant = 0
        /** Indeks ukuran 0 until [SIZE_STEPS] (kunci cache bersama tier & varian). */
        var sizeStep = 0
    }

    fun hash(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777215f
    }

    private fun frac(x: Float) = x - floor(x)
    private fun smooth(e: Float): Float { val c = e.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private val TAU = (2 * PI).toFloat()

    fun tierOf(i: Int): Tier {
        val v = hash(i, 3)
        return when {
            v < 0.26f -> Tier.DOT
            v < 0.64f -> Tier.SMALL
            v < 0.90f -> Tier.MEDIUM
            else -> Tier.LARGE
        }
    }

    /** Jari-jari (dalam satuan r wajah) untuk tier + langkah ukuran. */
    fun radiusFactor(tier: Tier, step: Int): Float {
        val s = step.toFloat() / (SIZE_STEPS - 1)
        return when (tier) {
            Tier.DOT -> 0.045f + 0.035f * s
            Tier.SMALL -> 0.15f + 0.05f * s
            Tier.MEDIUM -> 0.23f + 0.07f * s
            Tier.LARGE -> 0.33f + 0.07f * s
        }
    }

    /**
     * Isi [out] untuk partikel [i] pada waktu [t] (detik). [topClear] px: di atas garis ini (bar atas + Dynamic Island)
     * kepingan tidak tampak, lalu muncul memudar halus di bawahnya; memudar lagi dekat dasar layar.
     */
    fun flake(i: Int, t: Float, w: Float, h: Float, r: Float, topClear: Float, out: Flake): Flake {
        val tier = tierOf(i)
        val depth = when (tier) { Tier.DOT -> 0f; Tier.SMALL -> 0.4f; Tier.MEDIUM -> 0.75f; Tier.LARGE -> 1f }
        val step = (hash(i, 10) * SIZE_STEPS).toInt().coerceAtMost(SIZE_STEPS - 1)
        val speed = 0.035f + 0.055f * depth + 0.02f * hash(i, 2) // tinggi layar per detik (pelan, yang jauh lebih pelan)
        val y = (frac(hash(i, 4) + t * speed) * 1.15f - 0.075f) * h
        val sway = sin(t * (0.45f + 0.45f * hash(i, 6)) + hash(i, 7) * TAU) * r * (0.3f + 0.4f * depth) +
            sin(t * 0.21f + hash(i, 11) * TAU) * r * 0.25f
        out.x = hash(i, 1) * w + sway
        out.y = y
        out.tier = tier
        out.sizeStep = step
        out.radius = r * radiusFactor(tier, step)
        out.variant = (hash(i, 12) * VARIANTS).toInt().coerceAtMost(VARIANTS - 1)
        val dir = if (hash(i, 13) > 0.5f) 1f else -1f
        out.rotation = hash(i, 8) * 60f + dir * t * (6f + 12f * hash(i, 9)) // ≤ 18°/dtk
        val base = if (tier == Tier.DOT) 0.3f + 0.25f * hash(i, 5) else 0.62f + 0.33f * hash(i, 5)
        val fadeTop = smooth((y - topClear) / (r * 0.9f))
        val fadeBottom = 1f - smooth((y - 0.86f * h) / (0.14f * h))
        val twinkle = 0.88f + 0.12f * sin(t * (0.8f + 0.6f * hash(i, 14)) + hash(i, 15) * TAU)
        out.alpha = (base * fadeTop * fadeBottom * twinkle).coerceIn(0f, 1f)
        return out
    }

    /**
     * Garis-garis (x0,y0,x1,y1 berurutan) satu kepingan berjari-jari [radius] berpusat di (0,0), lengan pertama ke atas.
     * 6 lengan, masing-masing dengan cabang samping ±60°; varian membedakan posisi/panjang cabang.
     */
    fun segments(radius: Float, variant: Int): FloatArray {
        val branches: List<Pair<Float, Float>> = when (variant) { // (posisi di lengan, panjang cabang) dalam satuan radius
            0 -> listOf(0.42f to 0.30f, 0.70f to 0.20f)
            1 -> listOf(0.55f to 0.38f)
            else -> listOf(0.35f to 0.18f, 0.60f to 0.26f, 0.82f to 0.12f)
        }
        val plate = variant == 2 // varian 2: segi enam kecil di tengah
        val n = 6 * (1 + branches.size * 2) + if (plate) 6 else 0
        val out = FloatArray(n * 4)
        var k = 0
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float) { out[k++] = x0; out[k++] = y0; out[k++] = x1; out[k++] = y1 }
        for (arm in 0 until 6) {
            val ang = -PI.toFloat() / 2 + arm * TAU / 6
            val dx = cos(ang); val dy = sin(ang)
            seg(0f, 0f, dx * radius, dy * radius)
            for ((pos, len) in branches) {
                val bx = dx * radius * pos; val by = dy * radius * pos
                for (side in intArrayOf(-1, 1)) {
                    val ba = ang + side * (PI.toFloat() / 3) // ±60° dari lengan
                    val ex = bx + cos(ba) * radius * len; val ey = by + sin(ba) * radius * len
                    // cabang condong ke ujung: rata-rata arah lengan & ±60°
                    val mx = bx + (cos(ba) + dx) * 0.5f * radius * len * 1.15f
                    val my = by + (sin(ba) + dy) * 0.5f * radius * len * 1.15f
                    if (variant == 1) seg(bx, by, ex, ey) else seg(bx, by, mx, my)
                }
            }
        }
        if (plate) {
            val pr = radius * 0.22f
            for (j in 0 until 6) {
                val a0 = -PI.toFloat() / 2 + TAU / 12 + j * TAU / 6; val a1 = a0 + TAU / 6
                seg(cos(a0) * pr, sin(a0) * pr, cos(a1) * pr, sin(a1) * pr)
            }
        }
        check(k == out.size)
        return out
    }
}
