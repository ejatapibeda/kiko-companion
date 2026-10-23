package com.coactive.companion.ui.face

import kotlin.math.min

/**
 * Ukuran & posisi wajah dari ukuran kanvas. Semua elemen wajah digambar relatif terhadap radius mata [r].
 *
 * Batas wajah (satuan r, sudah termasuk glow, mata membesar, lirikan, loncatan reaksi):
 *  - lebar setengah  = spacing/r + [EYE_HALF_EXTENT]
 *  - atas            = [UP_EXTENT] di atas pusat mata, bawah = [DOWN_EXTENT] (mulut terbuka lebar)
 *
 * Portrait  : dibatasi lebar (wajah ±86% lebar layar), pusat mata di 40% tinggi.
 * Landscape : dibatasi tinggi pita wajah (6%..70% tinggi; 30% bawah untuk caption/mic/input),
 *             mata lebih renggang (3.1r) agar memakai lebar layar, dan tetap dibatasi lebar dengan margin.
 */
data class FaceLayout(val r: Float, val cx: Float, val cy: Float, val spacing: Float) {
    companion object {
        const val EYE_HALF_EXTENT = 1.75f // lebar oval mata (1.1r) ×1.22 + lirikan (~1.61r) + sisa glow
        const val UP_EXTENT = 1.9f
        const val DOWN_EXTENT = 3.3f
        const val PORTRAIT_SPACING = 2.65f // mata sedikit lebih rapat → wajah lebih besar & imut
        const val LANDSCAPE_SPACING = 3.1f
        const val LANDSCAPE_TOP = 0.06f
        const val LANDSCAPE_BOTTOM = 0.70f // di bawah ini area caption + mic/input
        const val WIDTH_FILL_PORTRAIT = 0.86f
        const val WIDTH_FILL_LANDSCAPE = 0.84f

        fun compute(w: Float, h: Float): FaceLayout {
            val portrait = h > w
            return if (portrait) {
                val byWidth = w / 2 * WIDTH_FILL_PORTRAIT / (PORTRAIT_SPACING + EYE_HALF_EXTENT)
                val r = min(byWidth, h * 0.06f)
                FaceLayout(r, w / 2, h * 0.40f, r * PORTRAIT_SPACING)
            } else {
                val band = h * (LANDSCAPE_BOTTOM - LANDSCAPE_TOP)
                val byHeight = band / (UP_EXTENT + DOWN_EXTENT)
                val byWidth = w / 2 * WIDTH_FILL_LANDSCAPE / (LANDSCAPE_SPACING + EYE_HALF_EXTENT)
                val r = min(byHeight, byWidth)
                val slack = band - r * (UP_EXTENT + DOWN_EXTENT)
                val cy = h * LANDSCAPE_TOP + r * UP_EXTENT + slack / 2
                FaceLayout(r, w / 2, cy, r * LANDSCAPE_SPACING)
            }
        }
    }
}
