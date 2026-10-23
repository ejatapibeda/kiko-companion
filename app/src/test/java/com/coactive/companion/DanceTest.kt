package com.coactive.companion

import androidx.compose.ui.geometry.Offset
import com.coactive.companion.assistant.DanceMode
import com.coactive.companion.assistant.DanceReason
import com.coactive.companion.assistant.Phase
import com.coactive.companion.settings.SpotifySource
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.PlaybackIntent
import com.coactive.companion.spotify.SpotifyTrack
import com.coactive.companion.tools.FaceDanceTool
import com.coactive.companion.ui.face.Catchlight
import com.coactive.companion.ui.face.DanceMotion
import com.coactive.companion.ui.face.FaceParams
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DanceTest {
    private val track = SpotifyTrack("Monokrom", listOf("Tulus"))
    private val playing = NowPlaying(isPlaying = true, track = track)
    private val paused = NowPlaying(isPlaying = false, isPaused = true, track = track)

    // ---------- mesin status ----------

    @Test fun entersOnIsPlayingAndExitsOnPause_officialAndSpotapi() {
        val d = DanceMode()
        for (src in listOf(SpotifySource.OFFICIAL, SpotifySource.SPOTAPI)) {
            assertTrue(d.shouldDance(true, src, playing, Phase.IDLE))
            assertEquals(DanceReason.PLAYBACK, d.reason(true, src, playing))
            assertFalse("pause → berhenti", d.shouldDance(true, src, paused, Phase.IDLE))
            assertFalse("tidak ada yang diputar (null) → berhenti", d.shouldDance(true, src, null, Phase.IDLE))
        }
    }

    @Test fun yieldsToAssistantPhasesAndResumes() {
        val d = DanceMode()
        for (ph in listOf(Phase.LISTENING, Phase.THINKING, Phase.TALKING, Phase.SLEEPING))
            assertFalse("$ph didahulukan", d.shouldDance(true, SpotifySource.OFFICIAL, playing, ph))
        // musik masih "aktif" selama interaksi → otomatis lanjut joget setelah kembali idle
        assertEquals(DanceReason.PLAYBACK, d.reason(true, SpotifySource.OFFICIAL, playing))
        assertTrue(d.shouldDance(true, SpotifySource.OFFICIAL, playing, Phase.IDLE))
    }

    @Test fun toggleOffDisablesEverything() {
        val d = DanceMode()
        d.setManual(true)
        d.onPlaybackAction(PlaybackIntent.PLAY, SpotifySource.LOCAL)
        for (src in SpotifySource.entries) {
            assertFalse(d.shouldDance(false, src, playing, Phase.IDLE))
            assertNull(d.reason(false, src, playing))
        }
    }

    @Test fun localModeUsesActionSessionProxy() {
        var now = 1_000_000L
        val d = DanceMode { now }
        // tanpa aksi: tidak joget otomatis (Lokal tak punya now-playing; data basi diabaikan)
        assertFalse(d.shouldDance(true, SpotifySource.LOCAL, playing, Phase.IDLE))
        d.onPlaybackAction(PlaybackIntent.PLAY, SpotifySource.LOCAL)
        assertTrue(d.shouldDance(true, SpotifySource.LOCAL, null, Phase.IDLE))
        assertEquals(DanceReason.LOCAL_SESSION, d.reason(true, SpotifySource.LOCAL, null))
        d.onPlaybackAction(PlaybackIntent.PAUSE, SpotifySource.LOCAL)
        assertFalse("jeda → berhenti", d.shouldDance(true, SpotifySource.LOCAL, null, Phase.IDLE))
        d.onPlaybackAction(PlaybackIntent.PLAY, SpotifySource.LOCAL)
        now += DanceMode.LOCAL_SESSION_MS - 1
        assertTrue(d.shouldDance(true, SpotifySource.LOCAL, null, Phase.IDLE))
        now += 2
        assertFalse("sesi habis sendiri", d.shouldDance(true, SpotifySource.LOCAL, null, Phase.IDLE))
    }

    @Test fun playActionInOfficialModeDoesNotStartLocalSession() {
        val d = DanceMode()
        d.onPlaybackAction(PlaybackIntent.PLAY, SpotifySource.OFFICIAL)
        assertFalse(d.shouldDance(true, SpotifySource.LOCAL, null, Phase.IDLE))
        assertFalse(d.shouldDance(true, SpotifySource.OFFICIAL, null, Phase.IDLE)) // menunggu now-playing
    }

    @Test fun manualDanceWorksInAnyModeAndStopsOnPause() {
        val d = DanceMode()
        d.setManual(true)
        for (src in SpotifySource.entries) assertEquals(DanceReason.MANUAL, d.reason(true, src, null))
        assertFalse(d.shouldDance(true, SpotifySource.LOCAL, null, Phase.TALKING))
        d.onPlaybackAction(PlaybackIntent.PAUSE, SpotifySource.OFFICIAL)
        assertFalse(d.manual)
        d.setManual(true); d.setManual(false)
        assertNull(d.reason(true, SpotifySource.LOCAL, null))
    }

    @Test fun faceDanceTool() = runTest {
        var enabled = true
        var last: Boolean? = null
        val tool = FaceDanceTool { on -> if (on && !enabled) false else { last = on; true } }
        assertTrue(tool.execute(buildJsonObject { put("action", JsonPrimitive("start")) }).contains("\"dancing\":true"))
        assertEquals(true, last)
        assertTrue(tool.execute(buildJsonObject { put("action", JsonPrimitive("stop")) }).contains("\"dancing\":false"))
        enabled = false
        assertTrue(tool.execute(buildJsonObject { put("action", JsonPrimitive("start")) }).contains("\"ok\":false"))
    }

    // ---------- nilai animasi ----------

    private val amounts = listOf(-0.0001f, 0f, 0.3f, 1f, 1.0000131f, Float.NaN)

    @Test fun danceValuesStayClamped() {
        var b = -3f
        while (b < 40f) {
            for (a in amounts) {
                val f = DanceMotion.frame(b, a)
                for (v in listOf(f.happy, f.mouthOpen, f.glow, f.amount)) assertTrue("b=$b a=$a v=$v", v in 0f..1f)
                for (v in listOf(f.bounceY, f.tilt, f.squash, f.lookX, f.lookY, f.glintBob)) assertTrue(v.isFinite())
                assertTrue(abs(f.tilt) <= 5f && abs(f.bounceY) <= 0.13f && abs(f.lookX) <= 0.34f)
                // juga dari pose yang sudah "overshoot"
                for (base in listOf(FaceParams(), FaceParams(openL = 1.00002f, openR = -0.0001f, sparkle = 1.0000131f, glowPulse = 1.00001f))) {
                    val p = DanceMotion.apply(base, f).sanitized()
                    for (v in listOf(p.openL, p.openR, p.sparkle, p.glowPulse, p.blush, p.mouth.wave)) assertTrue("b=$b a=$a v=$v", v in 0f..1f)
                    assertTrue(p.mouth.width >= 0f && p.scaleL >= 0f && p.glintX in -1f..1f && p.glintY in -1f..1f)
                }
                for (n in DanceMotion.notes(b, a)) {
                    assertTrue(n.alpha in 0f..1f && n.age in 0f..1f && n.scale > 0f)
                }
            }
            b += 0.013f
        }
        // beats tak valid tidak meledak
        DanceMotion.frame(Float.NaN, 1f); DanceMotion.frame(Float.POSITIVE_INFINITY, 1f)
        assertTrue(DanceMotion.notes(Float.NaN, 1f).isEmpty())
    }

    @Test fun zeroAmountIsIdentityAndLoopIsPeriodic() {
        val base = FaceParams()
        assertEquals(base, DanceMotion.apply(base, DanceMotion.frame(1.3f, 0f)))
        for (b in listOf(0.2f, 1.1f, 2.7f, 3.4f)) {
            val x = DanceMotion.frame(b, 1f); val y = DanceMotion.frame(b + 4f, 1f)
            assertEquals(x.lookX, y.lookX, 1e-4f); assertEquals(x.bounceY, y.bounceY, 1e-4f); assertEquals(x.happy, y.happy, 1e-4f)
        }
        // pola not tetap mulus saat waktu di-wrap (CYCLE_BEATS)
        val before = DanceMotion.notes(DanceMotion.CYCLE_BEATS - 0.01f, 1f).map { it.side to it.age }
        val after = DanceMotion.notes(-0.01f, 1f).map { it.side to it.age }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (p, q) -> assertEquals(p.first, q.first); assertEquals(p.second, q.second, 1e-3f) }
    }

    @Test fun beatRhythm() {
        // ketukan 1 lirik kiri, ketukan 2 lirik kanan; mendarat (bounce 0) tepat di ketukan, naik di tengahnya
        assertTrue(DanceMotion.frame(0f, 1f).lookX < -0.3f)
        assertTrue(DanceMotion.frame(1f, 1f).lookX > 0.3f)
        assertEquals(0f, DanceMotion.frame(2f, 1f).bounceY, 1e-4f)
        assertTrue(DanceMotion.frame(2.5f, 1f).bounceY < -0.1f)
        assertEquals(1f, DanceMotion.frame(0f, 1f).mouthOpen, 1e-4f) // mulut berdenyut di ketukan
        assertTrue(DanceMotion.frame(3f, 1f).happy > 0.99f && DanceMotion.frame(0.5f, 1f).happy < 0.01f)
        val ms = 60_000f / DanceMotion.DEFAULT_BPM
        assertTrue("~110–120 BPM", ms in 500f..546f)
    }

    // ---------- kilau mata (catchlight) ----------

    @Test fun catchlightFollowsGazeSign() {
        val c = Offset(500f, 400f); val r = 100f
        val anchor = Offset(0.32f, -0.38f); val dotR = r * 0.24f
        val rest = Catchlight.center(c, r, r, anchor, Offset.Zero, dotR)
        for (look in listOf(0.2f, 0.5f, 0.9f)) {
            val right = Catchlight.center(c, r, r, anchor, Offset(Catchlight.glintFor(look), 0f), dotR)
            val left = Catchlight.center(c, r, r, anchor, Offset(Catchlight.glintFor(-look), 0f), dotR)
            val up = Catchlight.center(c, r, r, anchor, Offset(0f, Catchlight.glintFor(-look)), dotR)
            val down = Catchlight.center(c, r, r, anchor, Offset(0f, Catchlight.glintFor(look)), dotR)
            assertTrue("lirik kanan → kilau ke kanan", right.x > rest.x)
            assertTrue("lirik kiri → kilau ke kiri", left.x < rest.x)
            assertTrue("menengadah → kilau naik", up.y < rest.y)
            assertTrue("menunduk → kilau turun", down.y > rest.y)
        }
        assertTrue(Catchlight.glintFor(0.5f) > 0f && Catchlight.glintFor(-0.5f) < 0f && Catchlight.glintFor(0f) == 0f)
    }

    @Test fun catchlightAlwaysInsideEye() {
        val c = Offset(300f, 300f)
        val anchors = listOf(Offset(0.32f, -0.38f) to 0.24f, Offset(-0.35f, 0.35f) to 0.1f, Offset(-0.4f, -0.25f) to 0.14f, Offset(0.45f, 0.3f) to 0.08f)
        val glints = listOf(-5f, -1f, -0.3f, 0f, 0.3f, 1f, 5f, Float.NaN)
        for (r in listOf(40f, 100f)) for (open in listOf(0.46f, 0.7f, 1f)) {
            val ry = r * open
            for ((a, rel) in anchors) for (gx in glints) for (gy in glints) {
                val dr = r * rel
                val p = Catchlight.center(c, r, ry, a, Offset(gx, gy), dr)
                assertTrue(p.x.isFinite() && p.y.isFinite())
                val dx = p.x - c.x; val dy = p.y - c.y
                // titik + jari-jarinya tetap di dalam elips mata
                val ax = r - dr; val ay = ry - dr
                if (ax > 0 && ay > 0) assertTrue("r=$r open=$open g=($gx,$gy)", (dx / ax) * (dx / ax) + (dy / ay) * (dy / ay) <= 1.0001f)
                assertTrue(hypot(dx, dy) + dr <= r + 0.01f)
            }
        }
        // mata sangat sipit: kilau ke tengah, tidak keluar
        assertEquals(c, Catchlight.center(c, 10f, 2f, Offset(0.32f, -0.38f), Offset(1f, 1f), 2.4f))
    }

    @Test fun danceFxAreBoundedPeriodicAndStopAtZero() {
        // efek musik saat joget: ada not, bar EQ, denyut, kilau — semuanya hilang saat amount 0
        assertTrue(DanceMotion.notes(1.3f, 0f).isEmpty())
        assertTrue(DanceMotion.sparkles(1.3f, 0f).isEmpty())
        assertEquals(0f, DanceMotion.beatPulse(1.3f, 0f))
        assertTrue(DanceMotion.eqLevels(1.3f, 0f).all { it == 0f })
        assertTrue(DanceMotion.eqLevels(Float.NaN, 1f).all { it == 0f })
        assertEquals(0f, DanceMotion.beatPulse(Float.POSITIVE_INFINITY, 1f))
        var b = 0f
        var maxNotes = 0; var anyHue = mutableSetOf<Int>(); var minNotes = 99
        while (b < DanceMotion.CYCLE_BEATS) {
            val ns = DanceMotion.notes(b, 1f)
            maxNotes = maxOf(maxNotes, ns.size); minNotes = minOf(minNotes, ns.size)
            ns.forEach { assertTrue(it.hue in 0..2 && it.lane in 0f..1f && it.side in listOf(-1, 1)); anyHue += it.hue }
            val eq = DanceMotion.eqLevels(b, 1f)
            assertEquals(DanceMotion.EQ_BARS, eq.size)
            eq.forEach { assertTrue(it in 0f..1f) }
            assertTrue(DanceMotion.beatPulse(b, 1f) in 0f..1f)
            DanceMotion.sparkles(b, 1f).forEach { assertTrue(it.slot in 0..3 && it.alpha in 0f..1f && it.scale in 0f..1f) }
            b += 0.05f
        }
        assertTrue("musik harus terlihat: beberapa not sekaligus", maxNotes in 2..4)
        assertTrue(minNotes >= 1)
        assertEquals(setOf(0, 1, 2), anyHue)
        // denyut tertinggi tepat di ketukan, meluruh sebelum ketukan berikut
        assertTrue(DanceMotion.beatPulse(2.0f, 1f) > 0.6f && DanceMotion.beatPulse(2.8f, 1f) < 0.05f)
        // bar EQ bergerak (tidak statis) dan mulus saat waktu di-wrap
        assertTrue(abs(DanceMotion.eqLevels(0.1f, 1f)[2] - DanceMotion.eqLevels(0.6f, 1f)[2]) > 0.05f)
        val e1 = DanceMotion.eqLevels(DanceMotion.CYCLE_BEATS - 0.001f, 1f); val e2 = DanceMotion.eqLevels(-0.001f, 1f)
        e1.indices.forEach { assertEquals(e1[it], e2[it], 1e-3f) }
        // intensitas mengecil saat joget berhenti (fade keluar mulus)
        assertTrue(DanceMotion.eqLevels(1.2f, 0.3f).sum() < DanceMotion.eqLevels(1.2f, 1f).sum())
    }
}
