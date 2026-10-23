package com.coactive.companion

import com.coactive.companion.assistant.ActivityKind
import com.coactive.companion.assistant.ActivityStep
import com.coactive.companion.assistant.ActivityTracker
import com.coactive.companion.assistant.AgentActivity
import com.coactive.companion.assistant.AssistantBrain
import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.assistant.FaceLogic
import com.coactive.companion.assistant.Phase
import com.coactive.companion.assistant.ToolActivity
import com.coactive.companion.assistant.asBrainListener
import com.coactive.companion.chat.StoredChatMessage
import com.coactive.companion.llm.ChatLlm
import com.coactive.companion.llm.ChatMessage
import com.coactive.companion.llm.FunctionCall
import com.coactive.companion.llm.ToolCall
import com.coactive.companion.llm.ToolSpec
import com.coactive.companion.net.Http
import com.coactive.companion.tools.AssistantTool
import com.coactive.companion.tools.ToolRegistry
import com.coactive.companion.tools.schema
import com.coactive.companion.ui.face.Expression
import com.coactive.companion.ui.face.GazeMode
import com.coactive.companion.ui.face.GazePlanner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class ActivityTrackerTest {
    @Test fun mapsToolNamesAndArgs() {
        assertEquals(ActivityKind.WEB_SEARCH to "harga emas hari ini", ToolActivity.of("web_search", """{"query":"  harga emas hari ini "}"""))
        assertEquals(ActivityKind.BROWSING to "detik.com", ToolActivity.of("browse_web", """{"url":"https://www.detik.com/news/123"}"""))
        assertEquals(ActivityKind.SPOTIFY_PLAY to "Tulus", ToolActivity.of("spotify_play", """{"query":"Tulus"}"""))
        assertEquals(ActivityKind.SPOTIFY_PAUSE, ToolActivity.of("spotify_control", """{"action":"pause"}""").first)
        assertEquals(ActivityKind.SPOTIFY_NEXT, ToolActivity.of("spotify_control", """{"action":"next"}""").first)
        assertEquals(ActivityKind.SPOTIFY_PREVIOUS, ToolActivity.of("spotify_control", """{"action":"previous"}""").first)
        assertEquals(ActivityKind.SPOTIFY_RESUME, ToolActivity.of("spotify_control", """{"action":"resume"}""").first)
        assertEquals(ActivityKind.SPOTIFY_NOW_PLAYING, ToolActivity.of("spotify_now_playing", "").first)
        assertEquals(ActivityKind.WEATHER to "Bandung", ToolActivity.of("get_weather", """{"location":"Bandung"}"""))
        assertEquals(ActivityKind.WEATHER to null, ToolActivity.of("get_weather", "{}"))
        assertEquals(ActivityKind.TIMER to "5m", ToolActivity.of("set_timer", """{"seconds":300}"""))
        assertEquals(ActivityKind.TIMER to "1m 30s", ToolActivity.of("set_timer", """{"seconds":90}"""))
        assertEquals(ActivityKind.ALARM to "06:05", ToolActivity.of("set_alarm", """{"hour":6,"minute":5}"""))
        assertEquals(ActivityKind.OPEN_APP to "YouTube", ToolActivity.of("open_app", """{"app_name":"YouTube"}"""))
        assertEquals(ActivityKind.DATETIME, ToolActivity.of("get_datetime", "{}").first)
        assertEquals(ActivityKind.DANCE, ToolActivity.of("face_dance", """{"action":"start"}""").first)
        assertEquals(ActivityKind.TOOL to "mystery_tool", ToolActivity.of("mystery_tool", "{}"))
        // argumen rusak dari LLM tidak boleh crash
        assertEquals(ActivityKind.WEB_SEARCH to null, ToolActivity.of("web_search", "{not json"))
        // detail panjang dipotong
        val long = ToolActivity.of("web_search", """{"query":"${"a".repeat(200)}"}""").second!!
        assertTrue(long.length <= 60 && long.endsWith("…"))
    }

    @Test fun detectsToolErrors() {
        assertTrue(ToolActivity.isError("""{"error":"search_unavailable"}"""))
        assertTrue(ToolActivity.isError("""{"ok":false,"message":"no device"}"""))
        assertFalse(ToolActivity.isError("""{"ok":true}"""))
        assertFalse(ToolActivity.isError("""{"query":"x","results":[]}"""))
        assertFalse(ToolActivity.isError("plain text result"))
        assertFalse(ToolActivity.isError("{broken"))
    }

    @Test fun fullVoiceTurnLifecycle() {
        val seen = mutableListOf<AgentActivity?>()
        val t = ActivityTracker { seen += it }
        t.beginTurn(); t.listening(); t.transcribing()
        t.llmRequest(0, false)
        t.toolStarted("web_search", """{"query":"cuaca"}""")
        t.toolFinished("web_search", """{"results":[]}""")
        t.toolStarted("spotify_play", """{"query":"Tulus"}""")
        t.toolFinished("spotify_play", """{"ok":true}""")
        t.llmRequest(1, false)
        t.speakingPrep()
        t.clear()
        assertEquals(listOf(ActivityKind.LISTENING, ActivityKind.TRANSCRIBING, ActivityKind.THINKING, ActivityKind.WEB_SEARCH,
            ActivityKind.SPOTIFY_PLAY, ActivityKind.THINKING, ActivityKind.SPEAKING_PREP), seen.filterNotNull().map { it.kind })
        assertNull(seen.last())
        assertNull(t.current.value)
        // seq naik monoton → UI bisa membedakan panggilan berurutan
        val seqs = seen.filterNotNull().map { it.seq }
        assertEquals(seqs.sorted().distinct(), seqs)
        assertEquals(listOf(ActivityStep("WEB_SEARCH", "cuaca"), ActivityStep("SPOTIFY_PLAY", "Tulus")), t.steps)
    }

    @Test fun failedToolIsShownThenNextRoundReturnsToThinking() {
        val t = ActivityTracker()
        t.beginTurn(); t.llmRequest(0, false)
        t.toolStarted("spotify_play", """{"query":"x"}""")
        t.toolFinished("spotify_play", """{"error":"no active device"}""")
        assertEquals(AgentActivity(ActivityKind.SPOTIFY_PLAY, "x", failed = true, seq = t.current.value!!.seq), t.current.value)
        assertTrue(t.steps.single().failed)
        t.llmRequest(1, false)
        assertEquals(ActivityKind.THINKING, t.current.value!!.kind)
        assertFalse(t.current.value!!.failed)
    }

    @Test fun sameToolTwiceProducesTwoStepsAndNewSeq() {
        val t = ActivityTracker()
        t.beginTurn()
        t.toolStarted("web_search", """{"query":"a"}"""); val s1 = t.current.value!!.seq
        t.toolFinished("web_search", "{}")
        t.toolStarted("web_search", """{"query":"b"}"""); val s2 = t.current.value!!.seq
        t.toolFinished("web_search", "{}")
        assertTrue(s2 > s1)
        assertEquals(listOf("a", "b"), t.steps.map { it.detail })
    }

    @Test fun visionOnlyOnFirstRoundWithImages() {
        val t = ActivityTracker()
        t.llmRequest(0, true); assertEquals(ActivityKind.VISION, t.current.value!!.kind)
        t.llmRequest(1, true); assertEquals(ActivityKind.THINKING, t.current.value!!.kind)
    }

    @Test fun staleTurnCannotClearNewTurn() {
        val t = ActivityTracker()
        val old = t.beginTurn(); t.llmRequest(0, false)
        val new = t.beginTurn(); t.llmRequest(0, false)
        t.clear(old) // giliran lama dibatalkan belakangan
        assertEquals(ActivityKind.THINKING, t.current.value?.kind)
        t.clear(new)
        assertNull(t.current.value)
        // beginTurn membuang langkah lama
        t.toolStarted("get_datetime", "{}"); t.toolFinished("get_datetime", "{}")
        t.beginTurn(); assertTrue(t.steps.isEmpty())
    }

    @Test fun clearWhenIdleDoesNotEmit() {
        var n = 0
        val t = ActivityTracker { n++ }
        t.clear(); t.clear()
        assertEquals(0, n)
    }

    private class ScriptedLlm(val script: List<ChatMessage>, val onCall: () -> Unit = {}) : ChatLlm {
        var i = 0
        override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage { onCall(); return script[i++] }
    }
    private class EchoTool(override val name: String, val out: String) : AssistantTool {
        override val description = "t"; override val parameters = schema()
        override suspend fun execute(args: JsonObject) = out
    }
    private fun call(id: String, name: String, args: String) = ToolCall(id, function = FunctionCall(name, args))

    @Test fun brainDrivesTrackerFromRealLifecycle() = runBlocking {
        val kinds = mutableListOf<ActivityKind?>()
        val t = ActivityTracker { kinds += it?.kind }
        val llm = ScriptedLlm(listOf(
            ChatMessage("assistant", null, toolCalls = listOf(call("1", "web_search", """{"query":"berita"}"""), call("2", "browse_web", """{"url":"https://kompas.com/a"}"""))),
            ChatMessage("assistant", null, toolCalls = listOf(call("3", "spotify_play", """{"query":"Tulus"}"""))),
            ChatMessage("assistant", "[happy] Siap!"),
        ))
        val reg = ToolRegistry(listOf(EchoTool("web_search", """{"results":[]}"""), EchoTool("browse_web", """{"text":"x"}"""),
            EchoTool("spotify_play", """{"error":"no device"}""")))
        t.beginTurn()
        val reply = AssistantBrain(llm, reg, ConversationMemory(), { "S" }, listener = t.asBrainListener()).respond("hai")
        assertEquals("Siap!", reply.text)
        assertEquals(listOf(ActivityKind.THINKING, ActivityKind.WEB_SEARCH, ActivityKind.BROWSING, ActivityKind.THINKING,
            ActivityKind.SPOTIFY_PLAY, ActivityKind.SPOTIFY_PLAY /* gagal */, ActivityKind.THINKING), kinds)
        assertEquals(listOf(false, false, true), t.steps.map { it.failed })
        assertEquals(listOf("berita", "kompas.com", "Tulus"), t.steps.map { it.detail })
    }

    @Test fun brainErrorPropagatesSoCallerCanClear() = runBlocking {
        val t = ActivityTracker()
        val llm = object : ChatLlm { override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage = error("boom") }
        try { AssistantBrain(llm, ToolRegistry(emptyList()), ConversationMemory(), { "S" }, listener = t.asBrainListener()).respond("x"); fail() }
        catch (e: IllegalStateException) { t.clear() }
        assertNull(t.current.value)
    }

    @Test fun faceExpressionPerActivity() {
        fun e(k: ActivityKind, failed: Boolean = false) = FaceLogic.expressionFor(Phase.THINKING, Expression.NEUTRAL, AgentActivity(k, failed = failed))
        assertEquals(Expression.THINKING, e(ActivityKind.THINKING))
        assertEquals(Expression.CURIOUS, e(ActivityKind.WEB_SEARCH))
        assertEquals(Expression.HAPPY, e(ActivityKind.SPOTIFY_PLAY))
        assertEquals(Expression.SAD, e(ActivityKind.SPOTIFY_PLAY, failed = true))
        assertEquals(Expression.THINKING, FaceLogic.expressionFor(Phase.THINKING, Expression.NEUTRAL, null))
        // di luar fase berpikir aktivitas tidak mengubah ekspresi
        assertEquals(Expression.TALKING, FaceLogic.expressionFor(Phase.TALKING, Expression.NEUTRAL, AgentActivity(ActivityKind.SPEAKING_PREP)))
    }

    @Test fun scanningGazeSweepsSideToSide() {
        val rnd = Random(3)
        var x = 0f; var y = 0f
        val xs = (0 until 8).map { GazePlanner.next(GazeMode.SCANNING, x, y, rnd).also { s -> x = s.x; y = s.y }.x }
        xs.zipWithNext().forEach { (a, b) -> assertTrue("must alternate sides: $a -> $b", a * b < 0) }
        assertTrue(xs.all { kotlin.math.abs(it) in 0.4f..0.6f })
    }

    @Test fun readingGazeStepsRightThenReturns() {
        val rnd = Random(5)
        var x = -0.45f; var y = 0.1f
        val xs = (0 until 12).map { GazePlanner.next(GazeMode.READING, x, y, rnd).also { s -> x = s.x; y = s.y }.x }
        assertTrue("has line breaks", xs.count { it == -0.45f } >= 2)
        xs.zipWithNext().forEach { (a, b) -> assertTrue(b > a || b == -0.45f) }
    }

    @Test fun storedMessageWithStepsRoundTripsAndOldJsonStillLoads() {
        val ser = ListSerializer(StoredChatMessage.serializer())
        val m = StoredChatMessage(1, "assistant", "hai", timestamp = 0, steps = listOf(ActivityStep("WEB_SEARCH", "x"), ActivityStep("FUTURE_KIND")))
        val back = Http.json.decodeFromString(ser, Http.json.encodeToString(ser, listOf(m)))
        assertEquals(m, back.single())
        assertEquals(ActivityKind.TOOL, back.single().steps[1].activityKind) // jenis tak dikenal → fallback
        val old = """[{"id":1,"role":"assistant","text":"lama","timestamp":0}]"""
        assertTrue(Http.json.decodeFromString(ser, old).single().steps.isEmpty())
    }
}
