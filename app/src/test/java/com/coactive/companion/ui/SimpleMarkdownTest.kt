package com.coactive.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleMarkdownTest {
    @Test fun stripsMarkersKeepsText() {
        val a = simpleMarkdown("Kode: **PINEAPPLE-77** dan *miring* serta `x_y` - item\n- 3*4*5 snake_case_name")
        assertEquals("Kode: PINEAPPLE-77 dan miring serta x_y - item\n- 3*4*5 snake_case_name", a.text)
        assertEquals(3, a.spanStyles.size)
    }
}
