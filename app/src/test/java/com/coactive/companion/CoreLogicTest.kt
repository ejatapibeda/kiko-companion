package com.coactive.companion

import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.assistant.EmotionParser
import com.coactive.companion.assistant.FaceLogic
import com.coactive.companion.assistant.Phase
import com.coactive.companion.audio.EndpointDetector
import com.coactive.companion.audio.Level
import com.coactive.companion.audio.Smoother
import com.coactive.companion.audio.Wav
import com.coactive.companion.spotify.Pkce
import com.coactive.companion.tools.AppMatcher
import com.coactive.companion.tts.TextChunker
import com.coactive.companion.ui.face.Expression
import com.coactive.companion.ui.face.FaceParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionParserTest {
    @Test fun parsesLeadingTagAndStripsIt() {
        val r = EmotionParser.parse("[happy] Siap, timer 5 menit sudah aku pasang!")
        assertEquals(Expression.HAPPY, r.emotion)
        assertEquals("Siap, timer 5 menit sudah aku pasang!", r.text)
    }
    @Test fun tagIsCaseInsensitive() = assertEquals(Expression.SAD, EmotionParser.parse("[SAD] Maaf ya.").emotion)
    @Test fun heuristicsWithoutTag() {
        assertEquals(Expression.SAD, EmotionParser.parse("Maaf, aku tidak menemukan lagunya.").emotion)
        assertEquals(Expression.CURIOUS, EmotionParser.parse("Kamu mau dengar lagu apa?").emotion)
        assertEquals(Expression.EXCITED, EmotionParser.parse("Keren banget").emotion)
        assertEquals(Expression.NEUTRAL, EmotionParser.parse("Sekarang jam tujuh").emotion)
    }
}

class FaceLogicTest {
    @Test fun phaseDrivesExpression() {
        assertEquals(Expression.THINKING, FaceLogic.expressionFor(Phase.THINKING, Expression.HAPPY))
        assertEquals(Expression.SLEEPY, FaceLogic.expressionFor(Phase.SLEEPING, Expression.HAPPY))
        assertEquals(Expression.TALKING, FaceLogic.expressionFor(Phase.TALKING, Expression.NEUTRAL))
        assertEquals(Expression.HAPPY, FaceLogic.expressionFor(Phase.TALKING, Expression.HAPPY))
        assertEquals(Expression.SAD, FaceLogic.expressionFor(Phase.IDLE, Expression.SAD))
    }
    @Test fun sleepsAfterInactivityOnlyWhenIdle() {
        assertTrue(FaceLogic.shouldSleep(Phase.IDLE, 0, 90_000, 90))
        assertFalse(FaceLogic.shouldSleep(Phase.IDLE, 0, 89_000, 90))
        assertFalse(FaceLogic.shouldSleep(Phase.TALKING, 0, 900_000, 90))
        assertFalse(FaceLogic.shouldSleep(Phase.IDLE, 0, 900_000, 0))
    }
    @Test fun faceParamsLerpAndBlink() {
        val a = FaceParams.of(Expression.NEUTRAL); val b = FaceParams.of(Expression.HAPPY)
        assertEquals(a, a.lerp(b, 0f)); assertEquals(b, a.lerp(b, 1f))
        assertEquals(0.5f, a.lerp(b, 0.5f).openL, 1e-6f)
        val blinked = a.withBlink(1f)
        assertEquals(0f, blinked.openL, 0f); assertEquals(0f, blinked.openR, 0f)
        val talkOpen = a.withTalkLevel(1f).mouth; val talkClosed = a.withTalkLevel(0f).mouth
        assertTrue(talkOpen.bottom - talkOpen.top > talkClosed.bottom - talkClosed.top)
    }
    @Test fun everyExpressionHasParams() { Expression.entries.forEach { FaceParams.of(it) } }
}

class MemoryTest {
    @Test fun keepsLastTurns() {
        val m = ConversationMemory(maxTurns = 2)
        m.add("a", "1"); m.add("b", "2"); m.add("c", "3")
        val msgs = m.messages()
        assertEquals(4, msgs.size)
        assertEquals("b", msgs[0].content); assertEquals("assistant", msgs[3].role)
    }
}

class AudioTest {
    @Test fun wavRoundTrip() {
        val pcm = ShortArray(1000) { (it * 31).toShort() }
        val wav = Wav.fromPcm16(pcm)
        assertEquals(44 + 2000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4))
        assertTrue(pcm.contentEquals(Wav.readPcm16(wav)))
    }
    @Test fun levelsAndMouth() {
        assertEquals(0f, Level.rms(ShortArray(100)), 0f)
        val loud = ShortArray(100) { if (it % 2 == 0) 16000 else -16000 }
        assertTrue(Level.rms(loud) > 0.45f)
        assertEquals(1f, Level.mouthOpenness(0.5f), 0f)
        assertEquals(0f, Level.mouthOpenness(0.001f), 0f)
        val s = Smoother(); repeat(20) { s.next(1f) }; assertTrue(s.value > 0.99f)
    }
    @Test fun endpointStopsAfterTrailingSilence() {
        val ep = EndpointDetector(frameMs = 80, trailingSilenceMs = 800)
        repeat(5) { assertFalse(ep.feed(0.002f)) }       // noise
        repeat(10) { assertFalse(ep.feed(0.1f)) }        // speech
        assertTrue(ep.speechDetected)
        var stopped = false; var n = 0
        while (!stopped && n < 50) { stopped = ep.feed(0.002f); n++ }
        assertTrue(stopped); assertEquals(10, n)
    }
    @Test fun endpointTimesOutWithoutSpeech() {
        val ep = EndpointDetector(frameMs = 80, noSpeechTimeoutMs = 800)
        var n = 0; while (!ep.feed(0.001f)) n++
        assertEquals(9, n); assertFalse(ep.speechDetected)
    }
}

class TextChunkerTest {
    @Test fun chunksAtSentenceBoundaries() {
        val text = "Halo semua. Ini kalimat kedua yang agak panjang. Dan ini ketiga!"
        val c = TextChunker.chunk(text, 30)
        assertTrue(c.all { it.length <= 30 })
        assertEquals(text, c.joinToString(" "))
    }
    @Test fun splitsLongWordsSequence() {
        val text = (1..100).joinToString(" ") { "kata$it" }
        val c = TextChunker.chunk(text, 50)
        assertTrue(c.all { it.length <= 50 }); assertEquals(text, c.joinToString(" "))
    }
    @Test fun cleansMarkdownAndEmoji() = assertEquals("Halo dunia", TextChunker.forSpeech("**Halo** _dunia_ 👋"))
}

class MiscTest {
    @Test fun pkceMatchesRfc7636Example() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    @Test fun pkceVerifierLength() = assertTrue(Pkce.verifier().length in 43..128)
    @Test fun appMatcher() {
        val apps = listOf("YouTube" to "yt", "YouTube Music" to "ytm", "WhatsApp" to "wa", "Kamera" to "cam")
        assertEquals("yt", AppMatcher.best("youtube", apps)?.second)
        assertEquals("ytm", AppMatcher.best("youtube music", apps)?.second)
        assertEquals("wa", AppMatcher.best("whats", apps)?.second)
        assertEquals("cam", AppMatcher.best("aplikasi kamera", apps)?.second)
        assertEquals(null, AppMatcher.best("telegram", apps))
    }
}

class EarconTest {
    @Test fun startEarconIsShortAndAudible() {
        val ms = com.coactive.companion.audio.Earcon.durationMs(com.coactive.companion.audio.Earcon.START)
        assertTrue(ms in 150..250)
        val pcm = com.coactive.companion.audio.Earcon.synthesize(com.coactive.companion.audio.Earcon.START)
        assertEquals(ms * com.coactive.companion.audio.Earcon.SAMPLE_RATE / 1000, pcm.size)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak in 10000..Short.MAX_VALUE.toInt())
        assertTrue(kotlin.math.abs(pcm[0].toInt()) < 200) // tanpa klik di awal
    }
    @Test fun endEarconIsSofterWhenGainLower() {
        val e = com.coactive.companion.audio.Earcon
        assertTrue(e.synthesize(e.END, 0.35f).maxOf { kotlin.math.abs(it.toInt()) } < e.synthesize(e.START).maxOf { kotlin.math.abs(it.toInt()) })
    }
}

class GazePlannerTest {
    private val gp = com.coactive.companion.ui.face.GazePlanner

    @Test fun idleGlancesAroundAndReturnsToCenter() {
        val rnd = kotlin.random.Random(42)
        var x = 0f; var y = 0f; var glances = 0; var returnsAfterGlance = 0
        repeat(400) {
            val wasAway = kotlin.math.abs(x) > 0.2f || kotlin.math.abs(y) > 0.15f
            val s = gp.next(com.coactive.companion.ui.face.GazeMode.IDLE, x, y, rnd)
            assertTrue(s.x in -0.8f..0.8f && s.y in -0.5f..0.5f)
            assertTrue(s.moveMs in 300..900 && s.holdMs in 600..4500)
            val away = kotlin.math.abs(s.x) > 0.2f || kotlin.math.abs(s.y) > 0.15f
            if (away) glances++
            if (wasAway) { assertTrue("must return after glance", !away); returnsAfterGlance++ }
            x = s.x; y = s.y
        }
        assertTrue(glances > 80); assertEquals(glances, returnsAfterGlance)
    }

    @Test fun modesStayWithinTastefulRanges() {
        val rnd = kotlin.random.Random(7)
        repeat(200) {
            val l = gp.next(com.coactive.companion.ui.face.GazeMode.LISTENING, 0f, 0f, rnd); assertTrue(kotlin.math.abs(l.x) <= 0.06f && kotlin.math.abs(l.y) <= 0.05f)
            val t = gp.next(com.coactive.companion.ui.face.GazeMode.TALKING, 0f, 0f, rnd); assertTrue(kotlin.math.abs(t.x) <= 0.16f)
            val th = gp.next(com.coactive.companion.ui.face.GazeMode.THINKING, 0f, 0f, rnd); assertTrue(th.y < 0f) // menengadah
        }
        assertEquals(0f, gp.next(com.coactive.companion.ui.face.GazeMode.SLEEPING, 0.5f, 0.5f, rnd).x, 0f)
    }

    @Test fun thinkingAlternatesSides() {
        val rnd = kotlin.random.Random(1)
        val a = gp.next(com.coactive.companion.ui.face.GazeMode.THINKING, 0f, 0f, rnd); val b = gp.next(com.coactive.companion.ui.face.GazeMode.THINKING, a.x, a.y, rnd)
        assertTrue(a.x < 0f && b.x > 0f)
    }

    @Test fun stiffnessSlowerForLongerMoves() {
        assertTrue(gp.stiffnessFor(800) < gp.stiffnessFor(400))
    }
}

class PersonalityTest {
    private val F = com.coactive.companion.ui.face.FaceParams()

    @Test fun idleQuirksVaryNeverRepeatAndAreSparse() {
        val rnd = kotlin.random.Random(3)
        var last: com.coactive.companion.ui.face.IdleQuirk? = null
        val seen = mutableMapOf<com.coactive.companion.ui.face.IdleQuirk, Int>()
        repeat(2000) {
            val s = com.coactive.companion.ui.face.IdleMouthPlanner.next(last, rnd)
            assertTrue(s.quirk != last)
            assertTrue(s.waitMs in 5000..12000)
            if (s.quirk == com.coactive.companion.ui.face.IdleQuirk.YAWN) { assertTrue(s.holdMs in 1300..1700); assertTrue(!s.withGlance) }
            else assertTrue(s.holdMs in 500..1500)
            seen[s.quirk] = (seen[s.quirk] ?: 0) + 1
            last = s.quirk
        }
        assertEquals(com.coactive.companion.ui.face.IdleQuirk.entries.size, seen.size)
        val yawn = seen[com.coactive.companion.ui.face.IdleQuirk.YAWN]!!
        assertTrue("yawn should be rare: $yawn", yawn < 2000 / 8)
    }

    @Test fun quirkApplyBlendsSmoothly() {
        val q = com.coactive.companion.ui.face.IdleQuirk.SMALL_O
        assertEquals(F, com.coactive.companion.ui.face.IdleMouthPlanner.apply(F, q, 0f))
        com.coactive.companion.ui.face.IdleMouthPlanner.apply(F, q, 1f).mouth.let { assertEquals(q.mouth.width, it.width, 1e-4f); assertEquals(q.mouth.bottom, it.bottom, 1e-4f) }
        val half = com.coactive.companion.ui.face.IdleMouthPlanner.apply(F, q, 0.5f).mouth.width
        assertEquals((F.mouth.width + q.mouth.width) / 2, half, 1e-4f)
        val yawn = com.coactive.companion.ui.face.IdleMouthPlanner.apply(F, com.coactive.companion.ui.face.IdleQuirk.YAWN, 1f)
        assertTrue(yawn.openL < 0.5f)
    }

    @Test fun reactionsStartAndEndAtRest() {
        com.coactive.companion.ui.face.Reaction.entries.forEach { r ->
            assertEquals(0f, r.envelope(0f), 1e-4f); assertEquals(0f, r.envelope(1f), 1e-4f); assertEquals(1f, r.envelope(0.4f), 1e-4f)
            val m = r.motion(1f)
            assertEquals(0f, m.tilt, 0.6f); assertEquals(0f, m.bounceY, 1e-3f); assertEquals(0f, m.lookX, 1e-3f)
            val end = r.apply(F, 1f); assertEquals(F.mouth.width, end.mouth.width, 1e-3f)
            // tidak ada lompatan besar antar frame (60 fps)
            var prev = r.apply(F, 0f)
            for (i in 1..(r.durationMs / 16)) {
                val cur = r.apply(F, i * 16f / r.durationMs)
                assertTrue("$r tilt jump", kotlin.math.abs(cur.tilt - prev.tilt) < 2.5f)
                assertTrue("$r mouth jump", kotlin.math.abs(cur.mouth.width - prev.mouth.width) < 0.15f)
                prev = cur
            }
        }
    }

    @Test fun pickerRotatesTapsAndRespectsCooldown() {
        val p = com.coactive.companion.ui.face.ReactionPicker(kotlin.random.Random(5))
        var t = 0L
        var prev: com.coactive.companion.ui.face.Reaction? = null
        repeat(10) {
            val r = p.onTap(t)!!
            assertTrue(r in com.coactive.companion.ui.face.Reaction.TAP && r != prev)
            prev = r; t += 5000
        }
        assertEquals(null, p.onTap(t - 5000 + 100)) // terlalu cepat → diabaikan
        assertEquals(com.coactive.companion.ui.face.Reaction.HAPPY_BOUNCE, p.onDoubleTap(t + 1000))
        val l1 = p.onLongPress(t + 2000)!!; val l2 = p.onLongPress(t + 3000)!!
        assertTrue(l1 in com.coactive.companion.ui.face.Reaction.LONG_PRESS && l1 != l2)
    }

    @Test fun tapSpamMakesItDizzyAndShakeHasCooldown() {
        val p = com.coactive.companion.ui.face.ReactionPicker(kotlin.random.Random(1))
        val got = (0 until 5).map { p.onTap(it * 500L) }
        assertEquals(com.coactive.companion.ui.face.Reaction.DIZZY, got.last())
        assertEquals(com.coactive.companion.ui.face.Reaction.DIZZY, p.onShake(10_000))
        assertEquals(null, p.onShake(11_000))
        assertEquals(com.coactive.companion.ui.face.Reaction.DIZZY, p.onShake(12_000))
    }

    @Test fun shakeDetectorNeedsSeveralStrongPeaks() {
        val d = com.coactive.companion.motion.ShakeDetector()
        assertTrue(!d.feed(0f, 9.8f, 0f, 0)) // diam
        assertTrue(!d.feed(25f, 9.8f, 0f, 100))
        assertTrue(!d.feed(-25f, 9.8f, 0f, 250))
        assertTrue(d.feed(25f, 9.8f, 0f, 400))
        val d2 = com.coactive.companion.motion.ShakeDetector()
        assertTrue(!d2.feed(25f, 9.8f, 0f, 0)); assertTrue(!d2.feed(25f, 9.8f, 0f, 2000)); assertTrue(!d2.feed(25f, 9.8f, 0f, 4000))
    }

    @Test fun leanFollowsTiltWithDeadZone() {
        val f = com.coactive.companion.motion.LeanFilter()
        var v = 0f
        repeat(100) { v = f.feed(0.3f) } // hampir tegak → 0
        assertEquals(0f, v, 0f)
        repeat(100) { v = f.feed(-5f) } // kanan turun → mata condong ke kanan
        assertTrue(v > 0.5f)
        assertEquals(-3f, com.coactive.companion.motion.LeanFilter.screenX(1f, 3f, android.view.Surface.ROTATION_90), 0f)
    }
}

class FaceLayoutTest {
    private val L = com.coactive.companion.ui.face.FaceLayout

    private fun assertFits(w: Float, h: Float) {
        val f = L.compute(w, h)
        val half = f.spacing + f.r * L.EYE_HALF_EXTENT
        assertTrue("x fits $w×$h", f.cx - half >= w * 0.05f && f.cx + half <= w * 0.95f)
        assertTrue("top fits $w×$h", f.cy - f.r * L.UP_EXTENT >= 0f)
        if (w > h) assertTrue("leaves room for caption/mic $w×$h", f.cy + f.r * L.DOWN_EXTENT <= h * L.LANDSCAPE_BOTTOM + 0.5f)
        else assertTrue(f.cy + f.r * L.DOWN_EXTENT <= h * 0.62f)
    }

    @Test fun galaxyS22IsMuchBigger() {
        val land = L.compute(2220f, 1080f)
        val oldLand = minOf(2220f * 0.05f, 1080f * 0.07f)
        assertTrue("landscape r=${land.r}", land.r > oldLand * 1.6f)
        // wajah memakai >55% lebar layar (dulu ~30%)
        assertTrue((land.spacing + land.r * L.EYE_HALF_EXTENT) * 2 / 2220f > 0.55f)
        val port = L.compute(1080f, 2220f)
        assertTrue("portrait r=${port.r}", port.r > minOf(1080f * 0.068f, 2220f * 0.05f) * 1.3f)
    }

    @Test fun fitsOnCommonScreens() {
        listOf(2220f to 1080f, 2340f to 1080f, 1920f to 1080f, 1280f to 800f, 1024f to 768f, 2560f to 1600f, 800f to 480f)
            .forEach { (a, b) -> assertFits(a, b); assertFits(b, a) }
    }
}

class LyricsLogicTest {
    private val lines = listOf(0L, 1000L, 5000L, 9000L).map { com.coactive.companion.spotify.LyricsLine(it, "l$it") }
    @Test fun activeLineIsLastStarted() {
        assertEquals(0, com.coactive.companion.ui.activeLyricIndex(lines, true, 0))
        assertEquals(1, com.coactive.companion.ui.activeLyricIndex(lines, true, 4999))
        assertEquals(2, com.coactive.companion.ui.activeLyricIndex(lines, true, 5000))
        assertEquals(3, com.coactive.companion.ui.activeLyricIndex(lines, true, 999_999))
        assertEquals(-1, com.coactive.companion.ui.activeLyricIndex(lines.drop(1), true, 500))
        assertEquals(-1, com.coactive.companion.ui.activeLyricIndex(lines, false, 5000))
    }
    @Test fun parsesLyricsContract() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val r = json.decodeFromString<com.coactive.companion.spotify.LyricsResponse>(
            """{"track_id":"6dGnYIeXmHdcikdzNNDMm2","synced":true,"source":"lrclib","lines":[{"start_ms":1234,"text":"hai"},{"start_ms":2000,"text":""}]}""")
        assertEquals("6dGnYIeXmHdcikdzNNDMm2", r.trackId); assertTrue(r.synced); assertEquals("lrclib", r.source)
        assertEquals(1234L, r.lines[0].startMs); assertEquals("", r.lines[1].text)
    }
}
