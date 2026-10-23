package com.coactive.companion.ui.face

import kotlin.math.abs
import kotlin.random.Random

enum class GazeMode {
    IDLE, LISTENING, THINKING, TALKING, SLEEPING,
    /** Mencari (web search): menyapu kiri↔kanan cepat, sedikit ke bawah — seperti memindai hasil. */
    SCANNING,
    /** Membaca halaman/gambar: maju per "kata" ke kanan lalu loncat balik ke kiri (baris baru). */
    READING,
}

/** Satu langkah gerak mata: target (satuan lookX/lookY), lama gerak, lama tahan, dan apakah sambil berkedip. */
data class GazeStep(val x: Float, val y: Float, val moveMs: Int, val holdMs: Int, val blink: Boolean = false)

/**
 * Perencana lirikan mata (murni, mudah diuji). Pola idle: sesekali melirik ke kiri/kanan/atas/diagonal,
 * menahan sebentar, lalu kembali ke tengah — seperti makhluk hidup yang melihat-lihat.
 */
object GazePlanner {
    private val idleDirections = listOf(
        -1f to 0f, 1f to 0f, -0.8f to -0.6f, 0.8f to -0.6f, 0f to -1f, -0.7f to 0.35f, 0.7f to 0.35f,
    )

    fun next(mode: GazeMode, currentX: Float, currentY: Float, rnd: Random = Random): GazeStep = when (mode) {
        GazeMode.SLEEPING -> GazeStep(0f, 0f, 900, 2000)
        GazeMode.LISTENING -> // menatap pengguna (tengah), sedikit bergeser
            GazeStep(rnd.range(-0.06f, 0.06f), rnd.range(-0.05f, 0.02f), rnd.int(380, 520), rnd.int(700, 1400))
        GazeMode.TALKING ->
            GazeStep(rnd.range(-0.16f, 0.16f), rnd.range(-0.08f, 0.06f), rnd.int(420, 650), rnd.int(700, 1600))
        GazeMode.THINKING -> { // menengadah, berganti sisi perlahan
            val side = if (currentX > -0.05f) -1f else 1f
            GazeStep(side * rnd.range(0.12f, 0.32f) + 0.05f, rnd.range(-0.22f, -0.08f), rnd.int(700, 1000), rnd.int(900, 1900))
        }
        GazeMode.SCANNING -> {
            val side = if (currentX > 0f) -1f else 1f
            GazeStep(side * rnd.range(0.42f, 0.58f), rnd.range(0.06f, 0.14f), rnd.int(300, 400), rnd.int(220, 420))
        }
        GazeMode.READING ->
            if (currentX >= 0.38f) GazeStep(-0.45f, rnd.range(0.08f, 0.16f), rnd.int(260, 340), rnd.int(160, 260)) // baris baru
            else GazeStep((currentX + rnd.range(0.14f, 0.22f)).coerceAtMost(0.5f), rnd.range(0.06f, 0.14f), rnd.int(150, 220), rnd.int(220, 380))
        GazeMode.IDLE -> {
            val away = abs(currentX) > 0.2f || abs(currentY) > 0.15f
            if (away || rnd.nextFloat() < 0.4f) {
                // kembali ke (dekat) tengah dan diam agak lama
                GazeStep(rnd.range(-0.05f, 0.05f), rnd.range(-0.03f, 0.03f), rnd.int(520, 820), rnd.int(1400, 4200))
            } else {
                val (dx, dy) = idleDirections[rnd.nextInt(idleDirections.size)]
                val amp = rnd.range(0.45f, 0.75f)
                GazeStep(dx * amp, dy * amp * 0.6f, rnd.int(380, 600), rnd.int(650, 1800), blink = rnd.nextFloat() < 0.3f)
            }
        }
    }

    /** Kekakuan spring dari lama gerak yang diinginkan (≈ waktu settle untuk damping ~0.85). */
    fun stiffnessFor(moveMs: Int): Float {
        val t = moveMs.coerceAtLeast(100) / 1000f
        return (4.2f / t).let { it * it }.coerceIn(15f, 1500f)
    }

    private fun Random.range(a: Float, b: Float) = a + nextFloat() * (b - a)
    private fun Random.int(a: Int, b: Int) = nextInt(a, b + 1)
}
