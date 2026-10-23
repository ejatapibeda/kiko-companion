package com.coactive.companion.ui.face

import androidx.compose.ui.graphics.Color

/**
 * Animasi spring bisa sedikit melewati target (mis. 1.0000131 atau -0.0001), sedangkan Compose
 * melempar IllegalArgumentException bila kanal warna di luar [0,1]. Semua alpha/kanal warna yang
 * berasal dari nilai animasi WAJIB lewat helper ini. NaN → 0 (coerceIn tidak menangani NaN).
 */
fun Float.clampUnit(): Float = if (this.isNaN()) 0f else coerceIn(0f, 1f)

/** Ukuran/radius dari animasi: tidak boleh negatif / NaN. */
fun Float.clampNonNegative(): Float = if (this.isNaN()) 0f else coerceAtLeast(0f)

/** Salin warna dengan alpha yang selalu valid. */
fun Color.withAlpha(alpha: Float): Color = copy(alpha = alpha.clampUnit())

/** Alpha warna dikalikan faktor animasi (hasil kali bisa > 1 atau < 0) → tetap valid. */
fun Color.scaleAlpha(factor: Float): Color = withAlpha(this.alpha * factor)
