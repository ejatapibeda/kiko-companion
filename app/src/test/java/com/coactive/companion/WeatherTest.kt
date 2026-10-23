package com.coactive.companion

import com.coactive.companion.ui.face.FaceParams
import com.coactive.companion.ui.face.WeatherAct
import com.coactive.companion.ui.face.WeatherAnim
import com.coactive.companion.weather.WeatherClassifier
import com.coactive.companion.weather.WeatherMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WeatherTest {
    @Test fun classify() {
        assertEquals(WeatherMood.RAIN, WeatherClassifier.classify(35.0, 61)) // hujan menang atas panas
        assertEquals(WeatherMood.RAIN, WeatherClassifier.classify(20.0, 95))
        assertEquals(WeatherMood.HOT, WeatherClassifier.classify(33.0, 0))
        assertEquals(WeatherMood.COLD, WeatherClassifier.classify(22.9, 3))
        assertNull(WeatherClassifier.classify(27.0, 2))
        assertNull(WeatherClassifier.classify(23.0, 45)) // kabut bukan hujan
    }

    @Test fun animationsReturnToBase() {
        val base = FaceParams()
        WeatherAct.entries.forEach { a ->
            val end = WeatherAnim.apply(base, a, 1f)
            assertTrue("$a tilt", abs(end.tilt) < 0.01f)
            assertTrue("$a bounce", abs(end.bounceY) < 0.01f)
            assertTrue("$a open", abs(end.openL - 1f) < 0.01f)
            for (i in 0..100) WeatherAnim.apply(base, a, i / 100f).sanitized() // tidak melempar
        }
        // puncak bersin: mata terpejam
        assertTrue(WeatherAnim.apply(base, WeatherAct.SNEEZE, 0.65f).openL < 0.18f)
    }
}
