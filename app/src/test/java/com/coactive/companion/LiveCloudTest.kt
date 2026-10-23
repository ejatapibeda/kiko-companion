package com.coactive.companion

import com.coactive.companion.assistant.AssistantBrain
import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.audio.Wav
import com.coactive.companion.llm.ChatMessage
import com.coactive.companion.llm.OpenAiChatLlm
import com.coactive.companion.stt.OpenAiSpeechToText
import com.coactive.companion.tools.AssistantTool
import com.coactive.companion.tools.ToolRegistry
import com.coactive.companion.tools.schema
import com.coactive.companion.tools.str
import com.coactive.companion.tts.TikTokTts
import com.coactive.companion.tts.TtsAudio
import com.coactive.companion.tts.base64Decode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Locale

/**
 * Tes ke layanan sungguhan. Dilewati kecuali env diset:
 *   GROQ_API_KEY=... LIVE_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveCloudTest*'
 * Key tidak pernah disimpan di repo.
 */
class LiveCloudTest {
    private val key = System.getenv("GROQ_API_KEY").orEmpty()
    private val groq = "https://api.groq.com/openai/v1"
    private val model = "openai/gpt-oss-120b"

    private class Recorder(override val name: String, props: List<String>) : AssistantTool {
        val calls = mutableListOf<JsonObject>()
        override val description = when (name) {
            "set_timer" -> "Start a countdown timer in the device clock app."
            "spotify_play" -> "Search Spotify and play a song, artist, album or playlist."
            else -> "Get current weather for a city"
        }
        override val parameters = schema { props.forEach { if (it == "seconds") integer(it, "Timer length in seconds", true) else string(it, it, it == "query") } }
        override suspend fun execute(args: JsonObject): String { calls += args; return """{"ok":true}""" }
    }

    private fun tools(): Triple<ToolRegistry, Recorder, Recorder> {
        val timer = Recorder("set_timer", listOf("seconds", "label"))
        val spotify = Recorder("spotify_play", listOf("query", "type"))
        return Triple(ToolRegistry(listOf(timer, spotify, Recorder("get_weather", listOf("location")))), timer, spotify)
    }

    private fun prompt() = AssistantBrain.defaultSystemPrompt("Kiko", "id-ID", "Jakarta", Locale("id", "ID"))

    @Test fun groqToolCallTimer() = runBlocking {
        assumeTrue(key.isNotBlank())
        val (reg, timer, _) = tools()
        val r = AssistantBrain(OpenAiChatLlm(groq, key, model), reg, ConversationMemory(), ::prompt).respond("pasang timer 5 menit ya")
        println("LIVE timer reply: [${r.emotion}] ${r.text} | args=${timer.calls}")
        assertEquals("300", timer.calls.single().str("seconds")?.toDouble()?.toInt()?.toString())
        assertTrue(r.text.isNotBlank())
    }

    @Test fun groqToolCallSpotify() = runBlocking {
        assumeTrue(key.isNotBlank())
        val (reg, _, spotify) = tools()
        val r = AssistantBrain(OpenAiChatLlm(groq, key, model), reg, ConversationMemory(), ::prompt).respond("putar lagu Sempurna dari Andra and The Backbone di Spotify")
        println("LIVE spotify reply: [${r.emotion}] ${r.text} | args=${spotify.calls}")
        assertTrue(spotify.calls.isNotEmpty())
        assertTrue(spotify.calls.first().str("query")!!.lowercase().contains("sempurna"))
    }

    @Test fun groqIndonesianChatWithMemory() = runBlocking {
        assumeTrue(key.isNotBlank())
        val (reg, _, _) = tools()
        val brain = AssistantBrain(OpenAiChatLlm(groq, key, model), reg, ConversationMemory(), ::prompt)
        val r1 = brain.respond("Halo! Namaku Budi. Apa kabar?")
        val r2 = brain.respond("Siapa namaku tadi?")
        println("LIVE chat: [${r1.emotion}] ${r1.text}\nLIVE memory: [${r2.emotion}] ${r2.text}")
        assertTrue(r2.text.contains("Budi"))
    }

    @Test fun groqWhisperTranscribesIndonesian() = runBlocking {
        assumeTrue(key.isNotBlank())
        val wav = File("src/androidTest/assets/negative_sample.wav").readBytes() // "Halo! Ada yang bisa aku bantu?" (TikTok TTS)
        Wav.readPcm16(wav) // valid
        val text = OpenAiSpeechToText(groq, key, "whisper-large-v3-turbo").transcribe(wav, "id")
        println("LIVE STT: $text")
        assertTrue(text.lowercase().contains("bantu"))
    }

    @Test fun tiktokProxyReturnsMp3() = runBlocking {
        assumeTrue(System.getenv("LIVE_TESTS") == "1")
        base64Decode = { Base64.getDecoder().decode(it) }
        val audio = TikTokTts("https://lazypy.ro", "id_001", cache = null).synthesize("Halo! Ada yang bisa aku bantu?").toList()
        val bytes = (audio.single() as TtsAudio.Encoded).bytes
        println("LIVE TikTok bytes=${bytes.size}")
        assertTrue(bytes.size > 5000)
        assertTrue(String(bytes, 0, 3) == "ID3" || (bytes[0].toInt() and 0xff) == 0xff)
    }

    @Suppress("unused") private fun unused() = ChatMessage.user("")
}
