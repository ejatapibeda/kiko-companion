package com.coactive.companion.ui.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import kotlin.math.sqrt

/**
 * Kilau putih (catchlight) di mata. Kilau MENGIKUTI arah pandang: melirik kanan → kilau bergeser ke kanan,
 * menengadah → ke atas, dsb. (seperti pantulan di pupil yang ikut berputar). Posisi selalu di-clamp ke dalam
 * elips mata (dikurangi jari-jari titik kilau) sehingga kilau tidak pernah keluar dari bola mata.
 */
object Catchlight {
    /** Pergeseran kilau per satuan arah pandang (relatif radius mata). */
    const val FOLLOW = 0.3f

    /** Target offset kilau (satuan radius) dari arah pandang; dianimasikan dengan spring di AnimatedFace. */
    fun glintFor(look: Float): Float = if (look.isNaN()) 0f else (look * FOLLOW).coerceIn(-1f, 1f)

    /**
     * Pusat titik kilau pada mata [c] (radius horizontal [r], vertikal [ry]).
     * [anchor] = posisi dasar (satuan r/ry), [glint] = geser (satuan r/ry), [depth] = 1 kilau utama, <1 titik kedua
     * (bergerak sedikit lebih kecil → kesan kedalaman), [dotR] = jari-jari titik.
     */
    fun center(c: Offset, r: Float, ry: Float, anchor: Offset, glint: Offset, dotR: Float, depth: Float = 1f): Offset {
        // Offset(NaN, NaN) == Offset.Unspecified (membaca .x melempar) → anggap tanpa geser
        val g = if (glint.isSpecified) glint else Offset.Zero
        val gx = if (g.x.isNaN()) 0f else g.x
        val gy = if (g.y.isNaN()) 0f else g.y
        val dx = (anchor.x + gx * depth) * r
        val dy = (anchor.y + gy * depth) * ry
        val margin = r * 0.04f
        val ax = r - dotR - margin
        val ay = ry - dotR - margin
        if (ax <= 0f || ay <= 0f || dx.isNaN() || dy.isNaN()) return c
        val k = (dx / ax) * (dx / ax) + (dy / ay) * (dy / ay)
        if (k <= 1f) return c + Offset(dx, dy)
        val s = 1f / sqrt(k)
        return c + Offset(dx * s, dy * s)
    }
}
