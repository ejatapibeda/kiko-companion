package com.coactive.companion

import com.coactive.companion.assistant.AssistantBrain
import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.llm.OpenAiChatLlm
import com.coactive.companion.net.ApiException
import com.coactive.companion.net.Http
import com.coactive.companion.search.BlockedException
import com.coactive.companion.search.BraveBackend
import com.coactive.companion.search.DuckDuckGoBackend
import com.coactive.companion.search.SearchBackend
import com.coactive.companion.search.SearchOutcome
import com.coactive.companion.search.SearchResult
import com.coactive.companion.search.SearchUnavailableException
import com.coactive.companion.search.SearxngBackend
import com.coactive.companion.search.SourceExtractor
import com.coactive.companion.search.TavilyBackend
import com.coactive.companion.search.WebSearch
import com.coactive.companion.search.WebSearchConfig
import com.coactive.companion.search.WebSearchProvider
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.tools.DefaultTools
import com.coactive.companion.tools.ReaderBrowseTool
import com.coactive.companion.tools.ToolRegistry
import com.coactive.companion.tools.WebSearchTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WebSearchTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()
    private fun json(b: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(b)
    private val base get() = server.url("/").toString()
    private fun obj(s: String) = Http.json.parseToJsonElement(s).jsonObject

    // ---------- parser ----------

    @Test fun duckDuckGoHtmlParsesRealPage() {
        val html = javaClass.classLoader!!.getResource("ddg_sample.html")!!.readText()
        val out = WebSearch.parseDuckDuckGoHtml(html)
        assertEquals("duckduckgo", out.provider)
        assertEquals(10, out.results.size)
        val first = out.results[0]
        assertEquals("https://news.detik.com/", first.url) // uddg didekode
        assertEquals("detikNews - Berita hari ini di Indonesia dan Internasional", first.title)
        assertTrue(first.snippet, first.snippet.isNotBlank() && !first.snippet.contains("<b>"))
        assertTrue(out.results.none { it.url.contains("duckduckgo.com") })
    }

    @Test fun duckDuckGoSkipsAdsAndDecodesEntities() {
        val html = """
            <div class="result results_links result--ad"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=x.com&amp;u3=1">Iklan</a>
            <a class="result__snippet" href="#">Beli sekarang</a></div>
            <div class="result results_links web-result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.example%2Fp%3Fq%3D1%26r%3D2&amp;rut=abc">Tom &amp; Jerry &#x27;24</a>
            <a class="result__snippet" href="#">Ada <b>kabar</b> baru&nbsp;hari ini</a></div>"""
        val r = WebSearch.parseDuckDuckGoHtml(html).results
        assertEquals(1, r.size)
        assertEquals("https://a.example/p?q=1&r=2", r[0].url)
        assertEquals("Tom & Jerry '24", r[0].title)
        assertEquals("Ada kabar baru hari ini", r[0].snippet)
    }

    @Test fun duckDuckGoChallengePageIsBlocked() {
        try { WebSearch.parseDuckDuckGoHtml("<html><div class=\"anomaly-modal__title\">Unfortunately, bots use DuckDuckGo too.</div></html>"); fail() }
        catch (e: BlockedException) { /* ok */ }
    }

    @Test fun ddgTargetDecodesRedirect() {
        assertEquals("https://x.example/a b", WebSearch.ddgTarget("//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.example%2Fa%20b&rut=1"))
        assertEquals("https://direct.example/", WebSearch.ddgTarget("https://direct.example/"))
        assertNull(WebSearch.ddgTarget("//duckduckgo.com/settings"))
    }

    @Test fun tavilyParse() {
        val o = WebSearch.parseTavily("""{"query":"q","answer":"Jawaban","results":[{"url":"https://t.example/1","title":"Judul <b>1</b>","content":"Isi  satu","score":0.9,"published_date":"2026-10-23T03:00:00Z"},{"title":"tanpa url"}],"response_time":1.2}""")
        assertEquals(1, o.results.size)
        assertEquals(SearchResult("Judul 1", "https://t.example/1", "Isi satu", "2026-10-23"), o.results[0])
        assertEquals("Jawaban", o.answer)
    }

    @Test fun braveSerperLangSearchSearxngExaParse() {
        val b = WebSearch.parseBrave("""{"web":{"results":[{"title":"B","url":"https://b.example","description":"desc <strong>x</strong>","page_age":"2026-10-22T00:00:00","extra_snippets":["lagi"]}]}}""")
        assertEquals(SearchResult("B", "https://b.example", "desc x … lagi", "2026-10-22"), b.results.single())
        val s = WebSearch.parseSerper("""{"answerBox":{"answer":"42"},"organic":[{"title":"S","link":"https://s.example","snippet":"sn","date":"1 hari lalu"}]}""")
        assertEquals("https://s.example", s.results.single().url); assertEquals("42", s.answer)
        val l = WebSearch.parseLangSearch("""{"code":200,"data":{"webPages":{"value":[{"name":"L","url":"https://l.example","snippet":"ls","datePublished":"2026-10-20T01:02:03Z"}]}},"usage":{"input_tokens":1,"output_tokens":2}}""")
        assertEquals(SearchResult("L", "https://l.example", "ls", "2026-10-20"), l.results.single())
        val x = WebSearch.parseSearxng("""{"results":[{"title":"X","url":"https://x.example","content":"cx","publishedDate":null}],"answers":["ans"]}""")
        assertEquals("cx", x.results.single().snippet); assertEquals("ans", x.answer)
        val e = WebSearch.parseExa("""{"results":[{"title":"E","url":"https://e.example","publishedDate":"2026-10-21T10:00:00.000Z","highlights":["h1","h2"]}]}""")
        assertEquals(SearchResult("E", "https://e.example", "h1 … h2", "2026-10-21"), e.results.single())
    }

    @Test fun groqBrowserParse() {
        val j = """{"choices":[{"message":{"role":"assistant","content":"Harga emas Antam Rp2 juta【0†L3-L5】 hari ini.","executed_tools":[
            {"type":"browser_search","search_results":{"results":[{"title":"Harga Emas","url":"https://bisnis.example/emas","content":"","score":0}]}},
            {"type":"browser.open","search_results":{"results":[{"title":"bisnis.example - viewing lines [0 - 55] of 55","url":"https://bisnis.example/emas","content":"L0"}]}}]}}]}"""
        val o = WebSearch.parseGroqBrowser(j)
        assertEquals("Harga emas Antam Rp2 juta hari ini.", o.answer)
        assertEquals(listOf("https://bisnis.example/emas"), o.results.map { it.url })
    }

    // ---------- rantai & fallback ----------

    private class Fake(override val id: String, val behave: suspend () -> SearchOutcome) : SearchBackend {
        var calls = 0
        override suspend fun search(query: String, max: Int, lang: String): SearchOutcome { calls++; return behave() }
    }
    private fun ok(id: String, n: Int = 2) = SearchOutcome(id, (1..n).map { SearchResult("t$it", "https://$id.example/$it", "s$it") })

    @Test fun fallbackSkipsFailingAndEmptyBackends() = runBlocking {
        val a = Fake("a") { throw ApiException(429, "rate limited") }
        val b = Fake("b") { SearchOutcome("b", emptyList()) }
        val c = Fake("c") { throw BlockedException("c") }
        val d = Fake("d") { ok("d", 9) }
        val e = Fake("e") { ok("e") }
        val out = WebSearch.run(listOf(a, b, c, d, e), "q", 5, "id")
        assertEquals("d", out.provider)
        assertEquals(5, out.results.size)
        assertEquals(listOf("a:http429", "b:empty", "c:blocked", "d:ok"), out.attempts)
        assertEquals(0, e.calls)
    }

    @Test fun slowBackendTimesOutAndFallsBack() = runBlocking {
        val slow = Fake("slow") { delay(5_000); ok("slow") }
        val out = WebSearch.run(listOf(slow, Fake("fast") { ok("fast") }), "q", 3, "en", perBackendTimeoutMs = 100)
        assertEquals("fast", out.provider)
        assertEquals("slow:timeout", out.attempts.first())
    }

    @Test fun allFailingThrowsUnavailable() = runBlocking {
        try { WebSearch.run(listOf(Fake("a") { throw java.net.UnknownHostException("x") }), "q", 3, "id"); fail() }
        catch (e: SearchUnavailableException) { assertEquals(listOf("a:offline"), e.attempts) }
    }

    @Test fun chainOrderDependsOnProviderAndKeys() {
        fun ids(c: WebSearchConfig) = WebSearch.chain(c).map { it.id }
        assertEquals(listOf("tavily_free", "duckduckgo", "wikipedia"), ids(WebSearchConfig()))
        assertEquals(listOf("tavily_free", "searxng", "duckduckgo", "wikipedia"), ids(WebSearchConfig(searxngUrl = "http://pi:8888")))
        // key kosong → tetap rantai gratis
        assertEquals(listOf("tavily_free", "duckduckgo", "wikipedia"), ids(WebSearchConfig(provider = WebSearchProvider.BRAVE)))
        assertEquals(listOf("brave", "tavily_free", "duckduckgo", "wikipedia"), ids(WebSearchConfig(provider = WebSearchProvider.BRAVE, braveKey = "k")))
        assertEquals(listOf("tavily", "tavily_free", "duckduckgo", "wikipedia"), ids(WebSearchConfig(provider = WebSearchProvider.TAVILY, tavilyKey = "k")))
        assertEquals(listOf("exa", "tavily_free", "duckduckgo", "wikipedia"), ids(AppSettings(webSearchProvider = WebSearchProvider.EXA, exaApiKey = "k").webSearchConfig()))
        assertEquals(listOf("searxng", "tavily_free", "duckduckgo", "wikipedia"), ids(WebSearchConfig(provider = WebSearchProvider.SEARXNG, searxngUrl = "http://pi:8888")))
        // Groq hanya bila LLM memang Groq
        assertEquals("tavily_free", ids(WebSearchConfig(provider = WebSearchProvider.GROQ, llmBaseUrl = "https://api.openai.com/v1", llmKey = "k")).first())
        assertEquals("groq", ids(WebSearchConfig(provider = WebSearchProvider.GROQ, llmBaseUrl = "https://api.groq.com/openai/v1", llmKey = "k")).first())
    }

    // ---------- HTTP (MockWebServer) ----------

    @Test fun tavilyKeylessAndKeyedHeaders() = runBlocking {
        server.enqueue(json("""{"results":[{"url":"https://t.example","title":"T","content":"c"}]}"""))
        server.enqueue(json("""{"results":[{"url":"https://t.example","title":"T","content":"c"}]}"""))
        TavilyBackend("", WebSearch.client, base).search("berita", 5, "id")
        val r1 = server.takeRequest()
        assertEquals("/search", r1.path); assertEquals("keyless", r1.getHeader("X-Tavily-Access-Mode")); assertNull(r1.getHeader("Authorization"))
        assertEquals("berita", obj(r1.body.readUtf8())["query"]!!.jsonPrimitive.content)
        val out = TavilyBackend("tv-key", WebSearch.client, base).search("berita", 5, "id")
        val r2 = server.takeRequest()
        assertEquals("Bearer tv-key", r2.getHeader("Authorization")); assertNull(r2.getHeader("X-Tavily-Access-Mode"))
        assertEquals("tavily", out.provider)
    }

    @Test fun braveSendsSubscriptionToken() = runBlocking {
        server.enqueue(json("""{"web":{"results":[]}}"""))
        BraveBackend("bk", WebSearch.client, base).search("a b", 4, "id")
        val r = server.takeRequest()
        assertEquals("bk", r.getHeader("X-Subscription-Token"))
        assertEquals("a b", r.requestUrl!!.queryParameter("q")); assertEquals("4", r.requestUrl!!.queryParameter("count"))
    }

    @Test fun duckDuckGo202IsBlockedAndSearxngHtmlIsBlocked() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("<html>anomaly</html>"))
        try { DuckDuckGoBackend(WebSearch.client, base).search("q", 5, "id"); fail() } catch (e: BlockedException) { }
        val ddgReq = server.takeRequest()
        assertEquals("POST", ddgReq.method); assertTrue(ddgReq.body.readUtf8().contains("kl=id-id"))
        server.enqueue(MockResponse().setBody("<!doctype html><title>Making sure you're not a bot</title>"))
        try { SearxngBackend(base, WebSearch.client).search("q", 5, "id"); fail() } catch (e: BlockedException) { }
        assertEquals("json", server.takeRequest().requestUrl!!.queryParameter("format"))
    }

    @Test fun toolReturnsFriendlyErrorWhenEverythingFails() = runBlocking {
        val tool = WebSearchTool({ WebSearchConfig() }, { "id" }, { listOf(Fake("a") { throw ApiException(500, "x") }) })
        val out = obj(tool.execute(obj("""{"query":"harga emas"}""")))
        assertEquals("search_unavailable", out["error"]!!.jsonPrimitive.content)
        assertTrue(out["say_to_user"]!!.jsonPrimitive.content.contains("nggak bisa nyari"))
    }

    @Test fun toolOutputIsCompactJson() = runBlocking {
        val tool = WebSearchTool({ WebSearchConfig() }, { "id" }, { listOf(Fake("x") { ok("x", 3) }) })
        val out = obj(tool.execute(obj("""{"query":"q","num_results":2}""")))
        assertEquals("x", out["provider"]!!.jsonPrimitive.content)
        assertEquals(2, out["results"]!!.jsonArray.size)
        assertEquals("https://x.example/1", out["results"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun browseFallsBackFromTavilyToJina() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        server.enqueue(MockResponse().setBody("Title: Hal\n\n![img](https://i.example/x.png)\nIsi [link](https://y.example) penting halaman ini."))
        val out = ReaderBrowseTool({ WebSearchConfig() }, tavilyBase = base, jinaBase = base + "r").execute(obj("""{"url":"https://x.example/a"}"""))
        assertEquals("/extract", server.takeRequest().path)
        assertEquals("/r/https://x.example/a", server.takeRequest().path)
        val text = obj(out)["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("Isi link penting") && !text.contains("i.example"))
    }

    @Test fun defaultToolsAlwaysHaveSearchAndBrowse() {
        assertEquals(listOf("web_search", "browse_web"), DefaultTools.webTools { AppSettings() }.map { it.name })
        assertEquals(listOf("web_search", "browse_web"), DefaultTools.webTools { AppSettings(exaApiKey = "k") }.map { it.name })
    }

    // ---------- sumber & suara ----------

    @Test fun sourcesExtractedAndUrlsStrippedForSpeech() {
        val src = SourceExtractor.fromToolOutput("web_search", WebSearch.toToolJson("q", ok("detik", 4)))
        assertEquals(3, src.size); assertEquals("detik.example", src[0].host)
        assertTrue(SourceExtractor.fromToolOutput("get_weather", """{"a":1}""").isEmpty())
        assertEquals("Menurut Detik, Indonesia menang.", SourceExtractor.stripUrlsForSpeech("Menurut [Detik](https://detik.com/x), Indonesia menang (https://detik.com/x)."))
        assertEquals("Cek di situs resmi.", SourceExtractor.stripUrlsForSpeech("Cek di situs resmi https://www.bmkg.go.id/cuaca ."))
    }

    @Test fun brainCollectsSourcesFromWebSearch() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"t1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"timnas\"}"}}]}}]}"""))
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":"[excited] Timnas menang!"}}]}"""))
        val tool = WebSearchTool({ WebSearchConfig() }, { "id" }, { listOf(Fake("x") { ok("berita", 2) }) })
        val r = AssistantBrain(OpenAiChatLlm(base, "k", "m"), ToolRegistry(listOf(tool)), ConversationMemory(), { "SYS" }).respond("cariin berita timnas")
        assertEquals("Timnas menang!", r.text)
        assertEquals(listOf("https://berita.example/1", "https://berita.example/2"), r.sources.map { it.url })
        assertFalse(AssistantBrain.defaultSystemPrompt("Kiko", "id-ID", "Jakarta", java.util.Locale("id"), chat = false).contains("link sumber otomatis"))
        assertTrue(AssistantBrain.defaultSystemPrompt("Kiko", "id-ID", "Jakarta", java.util.Locale("id"), chat = false).contains("JANGAN pernah menyebut atau membacakan URL"))
    }

    /** LIVE_TESTS=1: rantai gratis sungguhan (internet). */
    @Test fun liveFreeChain() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("LIVE_TESTS") == "1")
        val out = WebSearch.run(WebSearch.chain(WebSearchConfig()), "berita terbaru timnas indonesia", 5, "id")
        println("LIVE provider=${out.provider} attempts=${out.attempts} first=${out.results.firstOrNull()?.title}")
        assertTrue(out.results.isNotEmpty())
    }
}
