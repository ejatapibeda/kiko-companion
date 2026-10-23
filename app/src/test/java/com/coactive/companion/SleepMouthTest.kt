package com.coactive.companion

import com.coactive.companion.ui.face.SleepMouth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepMouthTest {
    @Test fun oscillatesSmallToBigAndBack() {
        assertEquals(0f, SleepMouth.open(0f), 1e-4f)
        assertEquals(1f, SleepMouth.open(0.5f), 1e-4f)
        assertEquals(0f, SleepMouth.open(1f), 1e-4f)
        assertTrue(SleepMouth.shape(0.5f).width > SleepMouth.shape(0f).width)
    }

    @Test fun staysSmallRoundO() {
        for (i in 0..20) {
            val m = SleepMouth.shape(i / 20f)
            val h = m.bottom - m.top
            assertTrue("kecil", m.width <= 0.45f && h <= 0.55f)
            assertTrue("bulat", h / m.width in 0.8f..1.4f)
            assertTrue("terbuka", m.top < 0f && m.bottom > 0f)
        }
    }
}
