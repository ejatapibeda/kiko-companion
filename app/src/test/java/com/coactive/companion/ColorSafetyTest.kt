package com.coactive.companion

import androidx.compose.ui.graphics.Color
import com.coactive.companion.ui.face.FaceColors
import com.coactive.companion.ui.face.FaceParams
import com.coactive.companion.ui.face.MouthShape
import com.coactive.companion.ui.face.clampNonNegative
import com.coactive.companion.ui.face.clampUnit
import com.coactive.companion.ui.face.scaleAlpha
import com.coactive.companion.ui.face.withAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresi crash Galaxy S22: alpha = 1.0000131 (overshoot spring) → IllegalArgumentException di Color.copy. */
class ColorSafetyTest {
    private val mouth = FaceColors.Light.mouth // r=0.0431, g=0.0588, b=0.1176 seperti di log crash

    @Test fun reproducesTheOriginalCrashWithoutClamp() {
        assertThrows(IllegalArgumentException::class.java) { mouth.copy(alpha = 1.0000131f) }
        // jalur lama drawMouth: baseColor.alpha * (1f - wave) dengan wave sedikit negatif
        assertThrows(IllegalArgumentException::class.java) { mouth.copy(alpha = mouth.alpha * (1f - (-0.0000131f))) }
    }

    @Test fun helpersClampOvershootAndUndershoot() {
        assertEquals(1f, mouth.withAlpha(1.0000131f).alpha, 0f)
        assertEquals(0f, mouth.withAlpha(-0.0001f).alpha, 0f)
        assertEquals(0f, mouth.withAlpha(Float.NaN).alpha, 0f)
        assertEquals(1f, mouth.scaleAlpha(1f - (-0.0000131f)).alpha, 0f) // persis kasus drawMouth
        assertEquals(0f, mouth.scaleAlpha(-0.0001f).alpha, 0f)
        assertEquals(0.5f, mouth.withAlpha(0.5f).alpha, 0.01f)
        // kanal RGB tidak berubah
        assertEquals(mouth.red, mouth.withAlpha(2f).red, 0f)
        // hasil kali beberapa nilai animasi (mis. (1-p)*0.35*amount, amount=1.08)
        assertTrue(Color.White.withAlpha(1.08f * 0.95f * 1.2f).alpha <= 1f)
        assertEquals(1f, 1.0000131f.clampUnit(), 0f); assertEquals(0f, (-0.0001f).clampUnit(), 0f)
        assertEquals(0f, (-3f).clampNonNegative(), 0f); assertEquals(0f, Float.NaN.clampNonNegative(), 0f)
    }

    @Test fun sanitizedParamsAreAlwaysDrawable() {
        val wild = FaceParams(openL = 1.00002f, openR = -0.0001f, scaleL = -0.01f, sparkle = 1.00001f, zzz = -0.00001f,
            blush = 1.0000131f, mouth = MouthShape(-0.02f, -0.3f, 0.6f, wave = -0.0000131f))
        val s = wild.sanitized()
        listOf(s.openL, s.openR, s.sparkle, s.zzz, s.blush, s.mouth.wave).forEach { assertTrue(it in 0f..1f) }
        assertTrue(s.scaleL >= 0f && s.mouth.width >= 0f)
        // alpha mulut setelah sanitize selalu valid
        mouth.scaleAlpha(1f - s.mouth.wave); mouth.scaleAlpha(s.mouth.wave)
        // lerp dengan progres spring yang overshoot (1.05) tetap aman setelah sanitize
        val over = FaceParams().lerp(FaceParams(sparkle = 1f, blush = 1f, mouth = MouthShape(1f, 0f, 0f, wave = 1f)), 1.05f).sanitized()
        assertEquals(1f, over.sparkle, 0f); assertEquals(1f, over.mouth.wave, 0f)
    }
}
