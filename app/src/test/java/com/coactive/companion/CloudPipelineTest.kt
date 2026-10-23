package com.coactive.companion

import com.coactive.companion.assistant.AssistantBrain
import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.llm.ChatMessage
import com.coactive.companion.llm.OpenAiChatLlm
import com.coactive.companion.net.ApiException
import com.coactive.companion.net.Http
import com.coactive.companion.stt.OpenAiSpeechToText
import com.coactive.companion.tools.AssistantTool
import com.coactive.companion.tools.ToolRegistry
import com.coactive.companion.tools.WeatherTool
import com.coactive.companion.tools.int
import com.coactive.companion.tools.schema
import com.coactive.companion.tts.ElevenLabsTts
import com.coactive.companion.tts.FallbackTtsEngine
import com.coactive.companion.tts.OpenAiCompatTts
import com.coactive.companion.tts.TikTokTts
import com.coactive.companion.tts.TtsAudio
import com.coactive.companion.tts.TtsAudioCache
import com.coactive.companion.tts.TtsEngine
import com.coactive.companion.tts.base64Decode
import com.coactive.companion.ui.face.Expression
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class CloudPipelineTest {
    private lateinit var server: MockWebServer
    private val base get() = server.url("/v1").toString()

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        base64Decode = { Base64.getDecoder().decode(it) }
    }
    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private class FakeTimer : AssistantTool {
        var seconds: Int? = null
        override val name = "set_timer"
        override val description = "timer"
        override val parameters = schema { integer("seconds", "s", required = true) }
        override suspend fun execute(args: JsonObject): String { seconds = args.int("seconds"); return """{"ok":true}""" }
    }

    @Test fun llmSendsToolsAndParsesToolCalls() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","reasoning":"x","tool_calls":[{"id":"c1","type":"function","function":{"name":"set_timer","arguments":"{\"seconds\":300}"}}]},"finish_reason":"tool_calls"}]}"""))
        val tools = ToolRegistry(listOf(FakeTimer()))
        val msg = OpenAiChatLlm(base, "sk-test", "m1").complete(listOf(ChatMessage.user("timer 5 menit")), tools.specs())
        assertEquals("set_timer", msg.toolCalls!!.single().function.name)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = Http.json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("m1", body["model"]!!.jsonPrimitive.content)
        assertEquals("set_timer", body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test fun brainRunsToolLoopAndRemembers() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"set_timer","arguments":"{\"seconds\":300}"}}]}}]}"""))
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":"[happy] Siap, timer 5 menit jalan!"}}]}"""))
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":"[neutral] Tadi 5 menit."}}]}"""))
        val timer = FakeTimer(); val mem = ConversationMemory()
        val brain = AssistantBrain(OpenAiChatLlm(base, "k", "m"), ToolRegistry(listOf(timer)), mem, { "SYS" })
        val r = brain.respond("pasang timer 5 menit")
        assertEquals(300, timer.seconds)
        assertEquals(Expression.HAPPY, r.emotion); assertEquals("Siap, timer 5 menit jalan!", r.text)
        server.takeRequest()
        val second = Http.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool"), second.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("c1", second[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        brain.respond("berapa lama tadi?")
        val third = Http.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), third.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("Siap, timer 5 menit jalan!", third[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun llmErrorBecomesApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        try { OpenAiChatLlm(base, "k", "m").complete(listOf(ChatMessage.user("x")), emptyList()); fail() } catch (e: ApiException) { assertEquals(401, e.code) }
    }

    @Test fun sttUploadsMultipartWav() = runBlocking {
        server.enqueue(json("""{"text":" Halo, ada yang bisa aku bantu? "}"""))
        val text = OpenAiSpeechToText(base, "k", "whisper-large-v3-turbo").transcribe(ByteArray(64), "id")
        assertEquals("Halo, ada yang bisa aku bantu?", text)
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("whisper-large-v3-turbo")); assertTrue(body.contains("filename=\"speech.wav\""))
        assertTrue(body.contains("name=\"language\"\r\n\r\nid") || body.contains("\r\n\r\nid\r\n"))
    }

    @Test fun elevenLabsStreamsPcm() = runBlocking {
        val pcm = ByteArray(4410) { (it % 7).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(pcm)))
        val audio = ElevenLabsTts("xi-key", "voice123", "eleven_flash_v2_5", "id", baseUrl = server.url("/").toString())
            .synthesize("Halo").toList().single() as TtsAudio.PcmStream
        assertEquals(22050, audio.sampleRate)
        assertTrue(pcm.contentEquals(audio.stream.readBytes()))
        val req = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice123/stream?output_format=pcm_22050", req.path)
        assertEquals("xi-key", req.getHeader("xi-api-key"))
        val body = Http.json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("eleven_flash_v2_5", body["model_id"]!!.jsonPrimitive.content)
        assertEquals("id", body["language_code"]!!.jsonPrimitive.content)
    }

    @Test fun elevenLabsErrorThrows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        try { ElevenLabsTts("x", "v", "eleven_multilingual_v2", baseUrl = server.url("/").toString()).synthesize("a").toList(); fail() }
        catch (e: ApiException) { assertEquals(401, e.code) }
    }

    @Test fun openAiTtsReturnsEncodedAudio() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0))))
        val a = OpenAiCompatTts(base, "k", "gpt-4o-mini-tts", "alloy").synthesize("Halo").toList().single() as TtsAudio.Encoded
        assertEquals("mp3", a.extension); assertEquals(6, a.bytes.size)
        val req = server.takeRequest(); assertEquals("/v1/audio/speech", req.path)
        assertTrue(req.body.readUtf8().contains("\"voice\":\"alloy\""))
    }

    @Test fun tiktokChunksAndFollowsPostRedirect() = runBlocking {
        val mp3 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        // proxy asli mengalihkan (307) ke host lain; OkHttp harus mempertahankan POST + body
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/real/api/generation")))
        server.enqueue(json("""{"success":true,"data":"$mp3","error":null}"""))
        server.enqueue(json("""{"success":true,"data":"$mp3","error":null}"""))
        val long = "Kalimat pertama yang cukup panjang. ".repeat(10) // > 280 karakter
        val out = TikTokTts(server.url("/").toString(), "id_001", cache = null).synthesize(long).toList()
        assertEquals(2, out.size)
        assertTrue((out[0] as TtsAudio.Encoded).bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("/api/generation", server.takeRequest().path)
        val redirected = server.takeRequest()
        assertEquals("POST", redirected.method); assertEquals("/real/api/generation", redirected.path)
        assertTrue(redirected.body.readUtf8().contains("\"voice\":\"id_001\""))
    }

    @Test fun tiktokFailureThrows() = runBlocking {
        server.enqueue(json("""{"success":false,"data":null,"error":"limit"}"""))
        try { TikTokTts(server.url("/").toString(), "id_001", cache = null).synthesize("halo").toList(); fail() } catch (e: ApiException) { }
    }

    private fun fakeMp3(n: Int = 200) = ByteArray(n).also { it[0] = 'I'.code.toByte(); it[1] = 'D'.code.toByte(); it[2] = '3'.code.toByte(); it[3] = 4 }
    private fun lazypy(sessionId: String = "", cache: TtsAudioCache? = null, timeoutMs: Long = 12_000) =
        TikTokTts(server.url("/").toString(), "id_001", sessionId, proxyTimeoutMs = timeoutMs, cache = cache,
            officialUrl = server.url("/official/invoke/").toString(), lazypyMode = true)
    private fun lazypyOk(path: String = "/tts/assets/audio/TikTok_id_001_x.mp3") =
        json("""{"success":true,"audio_url":"${server.url(path)}","info":"ok","error_msg":null}""")

    @Test fun lazypyPostsFormThenDownloadsMp3PerChunk() = runBlocking {
        val mp3 = fakeMp3()
        repeat(2) { server.enqueue(lazypyOk()); server.enqueue(MockResponse().setBody(Buffer().write(mp3))) }
        val long = "Kalimat pertama yang cukup panjang. ".repeat(10)
        val out = lazypy().synthesize(long).toList()
        assertEquals(2, out.size)
        assertTrue((out[0] as TtsAudio.Encoded).bytes.contentEquals(mp3)); assertEquals("mp3", (out[0] as TtsAudio.Encoded).extension)
        val post = server.takeRequest()
        assertEquals("POST", post.method); assertEquals("/tts/request_tts.php", post.path)
        assertTrue(post.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val form = post.body.readUtf8()
        assertTrue(form, form.contains("service=TikTok") && form.contains("voice=id_001") && form.contains("text=Kalimat"))
        val get = server.takeRequest(); assertEquals("GET", get.method); assertEquals("/tts/assets/audio/TikTok_id_001_x.mp3", get.path)
    }

    @Test fun lazypyDetectedFromHost() {
        assertTrue(TikTokTts.isLazypy("https://lazypy.ro")); assertTrue(TikTokTts.isLazypy("https://lazypy.ro/"))
        assertTrue(!TikTokTts.isLazypy("https://tiktok-tts.weilnet.workers.dev")); assertTrue(!TikTokTts.isLazypy("bukan url"))
        assertEquals("https://lazypy.ro", com.coactive.companion.settings.AppSettings().tiktokBaseUrl)
        assertTrue("https://tiktok-tts.weilnet.workers.dev" in TikTokTts.DEAD_URLS)
    }

    @Test fun lazypyFailureWithoutSessionThrows() = runBlocking {
        server.enqueue(json("""{"success":false,"audio_url":null,"error_msg":"limit"}"""))
        try { lazypy().synthesize("halo").toList(); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("limit")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun lazypyRejectsNonMp3AndForeignHost() = runBlocking {
        server.enqueue(lazypyOk()); server.enqueue(MockResponse().setBody("<html>error</html>"))
        try { lazypy().synthesize("halo").toList(); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("bukan mp3")) }
        server.enqueue(json("""{"success":true,"audio_url":"https://evil.example/a.mp3","error_msg":null}"""))
        try { lazypy().synthesize("halo dua").toList(); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("host lain")) }
    }

    @Test fun lazypyFailureFallsBackToOfficialWhenSessionSet() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        val v = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        server.enqueue(json("""{"data":{"v_str":"$v"},"message":"success","status_code":0}"""))
        val out = lazypy(sessionId = "dummy-session").synthesize("halo").toList().single() as TtsAudio.Encoded
        assertTrue(out.bytes.contentEquals(byteArrayOf(9, 8, 7)))
        server.takeRequest()
        val official = server.takeRequest()
        assertTrue(official.path!!.startsWith("/official/invoke/")); assertTrue(official.path!!.contains("text_speaker=id_001"))
        assertEquals("sessionid=dummy-session", official.getHeader("Cookie"))
    }

    @Test fun lazypyTimeoutFallsBackToOfficial() = runBlocking {
        server.enqueue(lazypyOk().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        val v = Base64.getEncoder().encodeToString(byteArrayOf(5))
        server.enqueue(json("""{"data":{"v_str":"$v"},"message":"success","status_code":0}"""))
        val t0 = System.currentTimeMillis()
        val out = lazypy(sessionId = "dummy-session", timeoutMs = 500).synthesize("halo").toList().single() as TtsAudio.Encoded
        assertTrue(out.bytes.contentEquals(byteArrayOf(5))); assertTrue(System.currentTimeMillis() - t0 < 2500)
    }

    @Test fun lazypyCacheAvoidsSecondRequest() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("ttscache").toFile()
        TtsAudioCache.clear(); TtsAudioCache.diskDir = dir
        try {
            val mp3 = fakeMp3(300)
            server.enqueue(lazypyOk()); server.enqueue(MockResponse().setBody(Buffer().write(mp3)))
            lazypy(cache = TtsAudioCache).synthesize("Halo, apa kabar?").toList()
            val again = lazypy(cache = TtsAudioCache).synthesize("halo,   APA kabar?").toList().single() as TtsAudio.Encoded
            assertTrue(again.bytes.contentEquals(mp3)); assertEquals(2, server.requestCount)
            // disk tetap ada setelah memori dikosongkan (mis. app restart)
            TtsAudioCache.clearMemoryForTest()
            assertTrue(TtsAudioCache.get("id_001", "Halo, apa kabar?")!!.contentEquals(mp3))
            assertEquals(null, TtsAudioCache.get("en_us_001", "Halo, apa kabar?"))
        } finally { TtsAudioCache.clear(); TtsAudioCache.diskDir = null; dir.deleteRecursively() }
    }

    @Test fun fallbackTtsUsedWhenPrimaryFails() = runBlocking {
        var fellBack = false; var spoken = ""
        val bad = object : TtsEngine { override suspend fun speak(text: String, onLevel: (Float) -> Unit) = throw ApiException(500, "x") }
        val good = object : TtsEngine { override suspend fun speak(text: String, onLevel: (Float) -> Unit) { spoken = text } }
        FallbackTtsEngine(bad, good) { fellBack = true }.speak("halo") {}
        assertTrue(fellBack); assertEquals("halo", spoken)
    }

    @Test fun weatherToolUsesOpenMeteo() = runBlocking {
        server.enqueue(json("""{"results":[{"name":"Bandung","latitude":-6.9,"longitude":107.6}]}"""))
        server.enqueue(json("""{"current":{"temperature_2m":24.5,"apparent_temperature":25,"relative_humidity_2m":80,"weather_code":61,"wind_speed_10m":5},"daily":{"temperature_2m_max":[29],"temperature_2m_min":[19],"precipitation_probability_max":[70],"weather_code":[61]}}"""))
        val tool = WeatherTool({ "Jakarta" }, { "id" }, geoBase = server.url("/").toString().trimEnd('/'), forecastBase = server.url("/").toString().trimEnd('/'))
        val out = Http.json.parseToJsonElement(tool.execute(Http.json.parseToJsonElement("""{"location":"Bandung"}""").jsonObject)).jsonObject
        assertEquals("Bandung", out["location"]!!.jsonPrimitive.content)
        assertEquals("hujan", out["condition"]!!.jsonPrimitive.content)
        assertTrue(server.takeRequest().path!!.startsWith("/v1/search?name=Bandung"))
    }

    @Test fun registryHandlesUnknownToolAndBadJson() = runBlocking {
        val reg = ToolRegistry(listOf(FakeTimer()))
        assertTrue(reg.execute("nope", "{}").contains("tidak dikenal"))
        assertTrue(reg.execute("set_timer", "{not json").contains("tidak valid"))
        assertEquals("""{"ok":true}""", reg.execute("set_timer", """{"seconds":"60"}"""))
    }
}
