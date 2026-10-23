package com.coactive.companion

import com.coactive.companion.assistant.AssistantBrain
import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.llm.OpenAiChatLlm
import com.coactive.companion.net.Http
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.tools.DefaultTools
import com.coactive.companion.tools.ExaBrowseTool
import com.coactive.companion.tools.ExaClient
import com.coactive.companion.tools.ExaSearchTool
import com.coactive.companion.tools.ToolRegistry
import com.coactive.companion.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExaToolTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()
    private fun json(b: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(b)
    private fun exa(key: String = "exa-key") = ExaClient({ key }, { server.url("/").toString() })

    private val searchResponse = """{"requestId":"r1","results":[
        {"title":"Timnas menang 2-0","url":"https://berita.example/timnas","publishedDate":"2026-10-21T10:00:00.000Z","text":"Teks panjang artikel...","highlights":["Indonesia menang 2-0 atas Vietnam tadi malam."]},
        {"title":"Jadwal","url":"https://b.example/j","text":"Jadwal pertandingan berikutnya Sabtu."}]}"""

    @Test fun searchSendsKeyAndContentsAndCompactsResults() = runBlocking {
        server.enqueue(json(searchResponse))
        val out = ExaSearchTool(exa()).execute(Http.json.parseToJsonElement("""{"query":"skor timnas semalam","category":"news"}""").jsonObject)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/search", req.path)
        assertEquals("exa-key", req.getHeader("x-api-key"))
        val body = Http.json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("skor timnas semalam", body["query"]!!.jsonPrimitive.content)
        assertEquals("news", body["category"]!!.jsonPrimitive.content)
        assertEquals(5, body["numResults"]!!.jsonPrimitive.int)
        assertTrue(body["contents"]!!.jsonObject.containsKey("text"))
        val results = Http.json.parseToJsonElement(out).jsonObject["results"]!!.jsonArray
        assertEquals(2, results.size)
        val first = results[0].jsonObject
        assertEquals("https://berita.example/timnas", first["url"]!!.jsonPrimitive.content)
        assertEquals("2026-10-21", first["published"]!!.jsonPrimitive.content)
        assertTrue(first["text"]!!.jsonPrimitive.content.contains("menang 2-0")) // highlights diutamakan
        assertEquals("Jadwal pertandingan berikutnya Sabtu.", results[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun browseUsesContentsEndpoint() = runBlocking {
        server.enqueue(json("""{"results":[{"title":"Hal","url":"https://x.example","text":"Isi halaman"}]}"""))
        val out = ExaBrowseTool(exa()).execute(Http.json.parseToJsonElement("""{"url":"https://x.example"}""").jsonObject)
        val req = server.takeRequest()
        assertEquals("/contents", req.path)
        assertTrue(req.body.readUtf8().contains("\"urls\":[\"https://x.example\"]"))
        assertTrue(out.contains("Isi halaman"))
    }

    @Test fun errorsAreReturnedToLlmNotThrown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid key"}"""))
        val reg = ToolRegistry(listOf(ExaSearchTool(exa())))
        val out = reg.execute("web_search", """{"query":"x"}""")
        assertTrue(out, out.contains("401"))
        val noKey = ToolRegistry(listOf(ExaSearchTool(exa(key = "")))).execute("web_search", """{"query":"x"}""")
        assertTrue(noKey.contains("API key Exa"))
    }

    @Test fun defaultToolsUseUnifiedWebSearch() {
        assertTrue(DefaultTools.webTools { AppSettings() }.first() is WebSearchTool)
        assertTrue(DefaultTools.webTools { AppSettings(exaApiKey = "k") }.first() is WebSearchTool)
    }

    @Test fun llmUsesExaResultsInConversation() = runBlocking {
        // 1) LLM minta web_search, 2) Exa menjawab, 3) LLM menjawab dari hasil
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"t1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"skor timnas\"}"}}]}}]}"""))
        server.enqueue(json(searchResponse))
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":"[excited] Indonesia menang 2-0!"}}]}"""))
        val base = server.url("/").toString()
        val brain = AssistantBrain(OpenAiChatLlm(base, "k", "m"), ToolRegistry(listOf(ExaSearchTool(exa()))), ConversationMemory(), { "SYS" })
        val r = brain.respond("skor timnas semalam berapa?")
        assertEquals("Indonesia menang 2-0!", r.text)
        server.takeRequest(); server.takeRequest()
        val third = Http.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        val toolMsg = third.last().jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertTrue(toolMsg["content"]!!.jsonPrimitive.content.contains("berita.example/timnas"))
    }
}
