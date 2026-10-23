package com.coactive.companion

import com.coactive.companion.ui.face.Snowfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class SnowfallTest {
    private val w = 1080f; private val h = 2220f; private val r = 120f; private val top = 168f

    @Test fun particleCountUnchanged() = assertEquals(42, Snowfall.COUNT)

    @Test fun mixOfSizesIncludingTinyDots() {
        val tiers = (0 until Snowfall.COUNT).map { Snowfall.tierOf(it) }.groupingBy { it }.eachCount()
        Snowfall.Tier.values().forEach { assertTrue("tier $it present: $tiers", (tiers[it] ?: 0) > 0) }
        val dots = tiers[Snowfall.Tier.DOT]!!
        assertTrue("a few dots, mostly crystals: $tiers", dots in 4..Snowfall.COUNT / 2)
    }

    @Test fun sixArmsWithSideBranchesForEveryVariant() {
        for (v in 0 until Snowfall.VARIANTS) {
            val s = Snowfall.segments(10f, v)
            // lengan utama: segmen dari pusat sepanjang radius, 6 buah, berjarak 60°
            val arms = (s.indices step 4).filter { s[it] == 0f && s[it + 1] == 0f && abs(hypot(s[it + 2], s[it + 3]) - 10f) < 1e-3f }
            assertEquals("variant $v arms", 6, arms.size)
            val angles = arms.map { Math.toDegrees(atan2(s[it + 3].toDouble(), s[it + 2].toDouble())).let { a -> (a + 360) % 360 } }.sorted()
            angles.zipWithNext().forEach { (a, b) -> assertEquals(60.0, b - a, 0.01) }
            val branches = s.size / 4 - 6 - (if (v == 2) 6 else 0)
            assertTrue("variant $v has side branches", branches >= 12 && branches % 12 == 0)
            // semua titik di dalam radius
            for (k in s.indices step 2) assertTrue(hypot(s[k], s[k + 1]) <= 10f + 1e-3f)
        }
    }

    @Test fun invisibleUnderTopBarAndIsland() {
        val f = Snowfall.Flake()
        var t = 0f
        while (t < 60f) {
            for (i in 0 until Snowfall.COUNT) {
                Snowfall.flake(i, t, w, h, r, top, f)
                if (f.y <= top) assertEquals("flake $i at t=$t y=${f.y}", 0f, f.alpha, 0f)
                assertTrue(f.alpha in 0f..1f)
            }
            t += 0.37f
        }
    }

    @Test fun fallsSlowlyDriftsSidewaysAndRotatesGently() {
        val a = Snowfall.Flake(); val b = Snowfall.Flake()
        var swayed = 0
        for (i in 0 until Snowfall.COUNT) {
            Snowfall.flake(i, 10f, w, h, r, top, a)
            Snowfall.flake(i, 10.1f, w, h, r, top, b)
            val dy = b.y - a.y
            if (dy > 0) assertTrue("flake $i falls slowly: $dy px / 0.1 s", dy < h * 0.014f) // < 14% tinggi layar per detik (≥ 7 dtk melintasi layar)
            assertTrue("flake $i rotates gently", abs(b.rotation - a.rotation) <= 1.8f + 1e-3f) // ≤ 18°/dtk
            // drift: x berubah sepanjang waktu tapi tetap terbatas
            val xs = (0 until 40).map { Snowfall.flake(i, it * 0.5f, w, h, r, top, a).x }
            val span = xs.max() - xs.min()
            assertTrue("flake $i sway bounded: $span", span < r * 2f)
            if (span > r * 0.2f) swayed++
        }
        assertTrue("most flakes drift side to side: $swayed", swayed > Snowfall.COUNT * 0.8)
    }

    @Test fun deterministicAndCacheKeysBounded() {
        val a = Snowfall.Flake(); val b = Snowfall.Flake()
        val keys = HashSet<Int>()
        for (i in 0 until Snowfall.COUNT) {
            Snowfall.flake(i, 3.3f, w, h, r, top, a); Snowfall.flake(i, 3.3f, w, h, r, top, b)
            assertEquals(a.x, b.x, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.rotation, b.rotation, 0f)
            assertTrue(a.sizeStep in 0 until Snowfall.SIZE_STEPS && a.variant in 0 until Snowfall.VARIANTS)
            assertEquals(r * Snowfall.radiusFactor(a.tier, a.sizeStep), a.radius, 1e-3f)
            if (a.tier != Snowfall.Tier.DOT) keys += (a.tier.ordinal * Snowfall.SIZE_STEPS + a.sizeStep) * Snowfall.VARIANTS + a.variant
        }
        assertTrue("few cached paths: ${keys.size}", keys.size <= 36)
    }
}
