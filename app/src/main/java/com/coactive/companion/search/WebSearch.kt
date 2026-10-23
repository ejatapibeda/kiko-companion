package com.coactive.companion.search

import androidx.annotation.StringRes
import com.coactive.companion.R
import com.coactive.companion.net.ApiException
import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import com.coactive.companion.net.joinUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Pencarian web untuk tool `web_search`.
 *
 * Default [WebSearchProvider.AUTO] = tanpa API key, dengan rantai fallback:
 * Tavily keyless → SearXNG milik sendiri (bila URL diisi) → DuckDuckGo HTML → Wikipedia.
 * Penyedia ber-key (Tavily/Exa/Brave/LangSearch/Serper) atau Groq browser_search dicoba lebih dulu bila dipilih
 * dan dikonfigurasi; bila gagal, tetap jatuh ke rantai gratis.
 */
enum class WebSearchProvider(@StringRes val labelRes: Int, val needsKey: Boolean) {
    AUTO(R.string.search_provider_auto, false),
    TAVILY(R.string.search_provider_tavily, true),
    EXA(R.string.search_provider_exa, true),
    BRAVE(R.string.search_provider_brave, true),
    LANGSEARCH(R.string.search_provider_langsearch, true),
    SERPER(R.string.search_provider_serper, true),
    SEARXNG(R.string.search_provider_searxng, false),
    GROQ(R.string.search_provider_groq, false),
}

/** Sumber yang ditampilkan sebagai link di bawah jawaban chat. */
@kotlinx.serialization.Serializable
data class WebSource(val title: String, val url: String) {
    val host: String get() = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url
}

/** Ambil sumber dari output tool web_search/browse_web (JSON) untuk ditampilkan di chat. */
object SourceExtractor {
    fun fromToolOutput(tool: String, output: String, max: Int = 3): List<WebSource> = runCatching {
        val o = Http.json.parseToJsonElement(output).jsonObject
        when (tool) {
            "web_search" -> o.arr("results").mapNotNull { r ->
                val ro = r as? JsonObject ?: return@mapNotNull null
                val url = ro.s("url") ?: return@mapNotNull null
                WebSource(ro.s("title") ?: url, url)
            }.take(max)
            "browse_web" -> listOfNotNull(o.s("url")?.let { WebSource(o.s("title") ?: it, it) })
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private val URL = Regex("""\(?https?://[^\s)\]]+\)?""")
    private val MD_LINK = Regex("""\[([^\]]+)]\((https?://[^)]+)\)""")

    /** Untuk TTS: link markdown → teksnya saja, URL mentah dibuang (jangan pernah dibacakan). */
    fun stripUrlsForSpeech(text: String): String = text
        .replace(MD_LINK, "$1")
        .replace(URL, "")
        .replace(Regex("[ \t]{2,}"), " ")
        .replace(Regex("\\s+([.,!?])"), "$1")
        .trim()
}

data class SearchResult(val title: String, val url: String, val snippet: String, val published: String? = null)

/** Hasil satu pencarian: penyedia yang berhasil + daftar hasil (+ jawaban ringkas bila penyedia memberinya). */
data class SearchOutcome(val provider: String, val results: List<SearchResult>, val answer: String? = null, val attempts: List<String> = emptyList())

class SearchUnavailableException(val attempts: List<String>) : IOException("search unavailable: ${attempts.joinToString("; ")}")

/** Satu backend pencarian. [search] melempar exception bila gagal/diblokir; daftar kosong = tidak ada hasil. */
interface SearchBackend {
    val id: String
    suspend fun search(query: String, max: Int, lang: String): SearchOutcome
}

/** Konfigurasi yang dibutuhkan untuk menyusun rantai (diambil dari AppSettings). */
data class WebSearchConfig(
    val provider: WebSearchProvider = WebSearchProvider.AUTO,
    val tavilyKey: String = "",
    val exaKey: String = "",
    val exaBaseUrl: String = "https://api.exa.ai",
    val braveKey: String = "",
    val langsearchKey: String = "",
    val serperKey: String = "",
    val searxngUrl: String = "",
    val llmBaseUrl: String = "",
    val llmKey: String = "",
    val llmModel: String = "",
) {
    val groqAvailable get() = llmBaseUrl.contains("groq.com") && llmKey.isNotBlank()

    /** Apakah penyedia pilihan bisa dipakai (key/URL terisi). */
    fun configured(p: WebSearchProvider = provider): Boolean = when (p) {
        WebSearchProvider.AUTO -> true
        WebSearchProvider.TAVILY -> tavilyKey.isNotBlank()
        WebSearchProvider.EXA -> exaKey.isNotBlank()
        WebSearchProvider.BRAVE -> braveKey.isNotBlank()
        WebSearchProvider.LANGSEARCH -> langsearchKey.isNotBlank()
        WebSearchProvider.SERPER -> serperKey.isNotBlank()
        WebSearchProvider.SEARXNG -> searxngUrl.isNotBlank()
        WebSearchProvider.GROQ -> groqAvailable
    }
}

object WebSearch {
    const val TIMEOUT_MS = 10_000L
    const val TOTAL_TIMEOUT_MS = 25_000L

    /** Klien dengan batas waktu ~10 detik per permintaan. */
    val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    /** Urutan backend: pilihan pengguna (bila terkonfigurasi) lalu rantai gratis tanpa key. */
    fun chain(c: WebSearchConfig, client: OkHttpClient = this.client): List<SearchBackend> {
        val free = buildList<SearchBackend> {
            add(TavilyBackend("", client))
            if (c.searxngUrl.isNotBlank()) add(SearxngBackend(c.searxngUrl, client))
            add(DuckDuckGoBackend(client))
            add(WikipediaBackend(client))
        }
        val first: SearchBackend? = if (!c.configured()) null else when (c.provider) {
            WebSearchProvider.AUTO -> null
            WebSearchProvider.TAVILY -> TavilyBackend(c.tavilyKey, client)
            WebSearchProvider.EXA -> ExaBackend(c.exaKey, c.exaBaseUrl.ifBlank { "https://api.exa.ai" }, client)
            WebSearchProvider.BRAVE -> BraveBackend(c.braveKey, client)
            WebSearchProvider.LANGSEARCH -> LangSearchBackend(c.langsearchKey, client)
            WebSearchProvider.SERPER -> SerperBackend(c.serperKey, client)
            WebSearchProvider.SEARXNG -> SearxngBackend(c.searxngUrl, client)
            WebSearchProvider.GROQ -> GroqBrowserBackend(c.llmBaseUrl, c.llmKey, c.llmModel)
        }
        return (listOfNotNull(first) + free).distinctBy { it.id }
    }

    /**
     * Coba backend satu per satu sampai ada hasil. Galat (HTTP, diblokir, timeout) dicatat tanpa isi key,
     * lalu lanjut ke backend berikut. Semua gagal → [SearchUnavailableException].
     */
    suspend fun run(backends: List<SearchBackend>, query: String, max: Int, lang: String, perBackendTimeoutMs: Long = TIMEOUT_MS + 2_000): SearchOutcome {
        val attempts = mutableListOf<String>()
        for (b in backends) {
            try {
                val out = withTimeout(perBackendTimeoutMs) { b.search(query, max, lang) }
                if (out.results.isNotEmpty() || !out.answer.isNullOrBlank()) return out.copy(results = out.results.take(max), attempts = attempts + "${b.id}:ok")
                attempts += "${b.id}:empty"
            } catch (e: TimeoutCancellationException) {
                attempts += "${b.id}:timeout"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempts += "${b.id}:" + describe(e)
            }
        }
        throw SearchUnavailableException(attempts)
    }

    /** Ringkasan galat yang aman dicatat (tanpa body/headers yang bisa memuat key). */
    fun describe(e: Throwable): String = when (e) {
        is ApiException -> "http${e.code}"
        is BlockedException -> "blocked"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
        is java.net.UnknownHostException -> "offline"
        else -> e.javaClass.simpleName
    }

    fun clean(s: String?): String = s.orEmpty()
        .replace(Regex("<[^>]+>"), "")
        .let(::decodeEntities)
        .replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(s: String): String = s
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    // ---------- parser (murni, diuji unit) ----------

    fun parseTavily(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val res = o.arr("results").mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val url = r.s("url") ?: return@mapNotNull null
            SearchResult(clean(r.s("title")).ifBlank { url }, url, clean(r.s("content")), r.s("published_date")?.take(10))
        }
        return SearchOutcome("tavily", res, o.s("answer")?.takeIf { it.isNotBlank() })
    }

    fun parseExa(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val res = o.arr("results").mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val url = r.s("url") ?: return@mapNotNull null
            val hl = r.arr("highlights").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" … ")
            SearchResult(clean(r.s("title")).ifBlank { url }, url, clean(hl.ifBlank { r.s("summary") ?: r.s("text") }), r.s("publishedDate")?.take(10))
        }
        return SearchOutcome("exa", res)
    }

    fun parseBrave(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val res = (o["web"] as? JsonObject)?.arr("results").orEmpty().mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val url = r.s("url") ?: return@mapNotNull null
            val extra = r.arr("extra_snippets").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(1).joinToString(" ")
            SearchResult(clean(r.s("title")).ifBlank { url }, url, clean(listOfNotNull(r.s("description"), extra.ifBlank { null }).joinToString(" … ")), r.s("page_age")?.take(10) ?: r.s("age"))
        }
        return SearchOutcome("brave", res)
    }

    fun parseSerper(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val res = o.arr("organic").mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val url = r.s("link") ?: return@mapNotNull null
            SearchResult(clean(r.s("title")).ifBlank { url }, url, clean(r.s("snippet")), r.s("date"))
        }
        val box = o["answerBox"] as? JsonObject
        val answer = box?.let { it.s("answer") ?: it.s("snippet") }?.takeIf { it.isNotBlank() }
        return SearchOutcome("serper", res, answer)
    }

    fun parseLangSearch(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val pages = ((o["data"] as? JsonObject)?.get("webPages") as? JsonObject)?.arr("value").orEmpty()
        val res = pages.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val url = r.s("url") ?: return@mapNotNull null
            SearchResult(clean(r.s("name")).ifBlank { url }, url, clean(r.s("summary") ?: r.s("snippet") ?: r.s("text")), r.s("datePublished")?.take(10))
        }
        return SearchOutcome("langsearch", res)
    }

    fun parseSearxng(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val res = o.arr("results").mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val url = r.s("url") ?: return@mapNotNull null
            SearchResult(clean(r.s("title")).ifBlank { url }, url, clean(r.s("content")), r.s("publishedDate")?.take(10))
        }
        val answer = o.arr("answers").firstNotNullOfOrNull { a -> (a as? JsonPrimitive)?.contentOrNull ?: (a as? JsonObject)?.s("answer") }
        return SearchOutcome("searxng", res, answer?.takeIf { it.isNotBlank() })
    }

    fun parseWikipedia(json: String, lang: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val res = o.arr("pages").mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val key = p.s("key") ?: return@mapNotNull null
            val text = listOfNotNull(p.s("description"), p.s("excerpt")?.let(::clean)).joinToString(" — ")
            SearchResult(p.s("title") ?: key, "https://$lang.wikipedia.org/wiki/${java.net.URLEncoder.encode(key, "UTF-8").replace("+", "_")}", text)
        }
        return SearchOutcome("wikipedia", res)
    }

    private val DDG_RESULT = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val DDG_SNIPPET = Regex("""class="result__snippet"[^>]*>(.*?)</(?:a|div|td)>""", RegexOption.DOT_MATCHES_ALL)

    /** Halaman hasil html.duckduckgo.com. Melempar [BlockedException] bila halaman berupa tantangan anti-bot. */
    fun parseDuckDuckGoHtml(html: String): SearchOutcome {
        val matches = DDG_RESULT.findAll(html).toList()
        if (matches.isEmpty() && (html.contains("anomaly-modal") || html.contains("challenge-form") || html.contains("anomaly.js"))) throw BlockedException("duckduckgo")
        val res = matches.mapIndexedNotNull { i, m ->
            val url = ddgTarget(decodeEntities(m.groupValues[1])) ?: return@mapIndexedNotNull null
            if (url.contains("duckduckgo.com/y.js") || url.contains("ad_provider")) return@mapIndexedNotNull null
            val end = matches.getOrNull(i + 1)?.range?.first ?: html.length
            val snippet = DDG_SNIPPET.find(html.substring(m.range.last, end))?.groupValues?.get(1)
            SearchResult(clean(m.groupValues[2]).ifBlank { url }, url, clean(snippet))
        }.distinctBy { it.url }
        return SearchOutcome("duckduckgo", res)
    }

    /** Link DDG berbentuk //duckduckgo.com/l/?uddg=<url-encoded>&rut=… → URL asli. */
    fun ddgTarget(href: String): String? {
        val abs = if (href.startsWith("//")) "https:$href" else href
        val u = abs.toHttpUrlOrNull() ?: return null
        if (u.host.endsWith("duckduckgo.com") && u.encodedPath.startsWith("/l/")) {
            return u.queryParameter("uddg")?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.startsWith("http") }
        }
        if (u.host.endsWith("duckduckgo.com")) return if (u.encodedPath.startsWith("/y.js")) abs else null
        return abs
    }

    /** Jawaban Groq browser_search: isi teks (tanpa penanda sitasi 【…】) + daftar sumber dari executed_tools. */
    fun parseGroqBrowser(json: String): SearchOutcome {
        val o = Http.json.parseToJsonElement(json).jsonObject
        val msg = (o.arr("choices").firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val content = msg?.s("content").orEmpty().replace(Regex("【[^】]*】"), "").replace(Regex("[ \\t]+"), " ").trim()
        val res = msg?.arr("executed_tools").orEmpty().flatMap { t ->
            val sr = (t as? JsonObject)?.get("search_results")
            val obj = when (sr) {
                is JsonObject -> sr
                is JsonPrimitive -> sr.contentOrNull?.let { s -> runCatching { Http.json.parseToJsonElement(s).jsonObject }.getOrNull() }
                else -> null
            }
            obj?.arr("results").orEmpty().mapNotNull { r ->
                val ro = r as? JsonObject ?: return@mapNotNull null
                val url = ro.s("url") ?: return@mapNotNull null
                val title = ro.s("title").orEmpty().replace(Regex(" - viewing lines.*$"), "")
                SearchResult(title.ifBlank { url }, url, clean(ro.s("content")).take(300))
            }
        }.distinctBy { it.url }
        return SearchOutcome("groq", res, content.ifBlank { null })
    }

    /** JSON ringkas untuk LLM. */
    fun toToolJson(query: String, out: SearchOutcome, maxChars: Int = 700): String = buildJsonObject {
        put("query", query)
        put("provider", out.provider)
        out.answer?.let { put("answer", it.take(1500)) }
        putJsonArray("results") {
            out.results.forEachIndexed { i, r ->
                add(buildJsonObject {
                    put("n", i + 1); put("title", r.title.take(200)); put("url", r.url)
                    r.published?.let { put("published", it) }
                    put("text", r.snippet.take(maxChars))
                })
            }
        }
    }.toString()

    fun unavailableJson(query: String, lang: String): String = buildJsonObject {
        put("query", query)
        put("error", "search_unavailable")
        put("say_to_user", if (lang == "id") "Maaf, aku nggak bisa nyari sekarang. Coba lagi sebentar lagi ya." else "Sorry, I can't search right now. Please try again in a bit.")
    }.toString()
}

class BlockedException(source: String) : IOException("$source blocked the request")

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()

private val JSON = "application/json".toMediaType()
private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

private suspend fun OkHttpClient.text(req: Request): String = newCall(req).await().bodyOrThrow()
private fun postJson(url: String, body: JsonObject, headers: Map<String, String> = emptyMap()): Request =
    Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

/** Tavily /search. Key kosong = mode keyless (gratis, dibatasi laju, tanpa akun). */
class TavilyBackend(private val key: String, private val client: OkHttpClient, private val base: String = "https://api.tavily.com") : SearchBackend {
    override val id = if (key.isBlank()) "tavily_free" else "tavily"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val body = buildJsonObject { put("query", query); put("max_results", max); put("search_depth", "basic") }
        val h = if (key.isBlank()) mapOf("X-Tavily-Access-Mode" to "keyless") else mapOf("Authorization" to "Bearer $key")
        return WebSearch.parseTavily(client.text(postJson(joinUrl(base, "search"), body, h))).copy(provider = id)
    }
}

class ExaBackend(private val key: String, private val base: String, private val client: OkHttpClient) : SearchBackend {
    override val id = "exa"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val body = buildJsonObject {
            put("query", query); put("type", "auto"); put("numResults", max)
            putJsonObject("contents") { putJsonObject("highlights") { put("maxCharacters", 700) } }
        }
        return WebSearch.parseExa(client.text(postJson(joinUrl(base, "search"), body, mapOf("x-api-key" to key))))
    }
}

class BraveBackend(private val key: String, private val client: OkHttpClient, private val base: String = "https://api.search.brave.com") : SearchBackend {
    override val id = "brave"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val url = joinUrl(base, "res/v1/web/search").toHttpUrl().newBuilder().addQueryParameter("q", query)
            .addQueryParameter("count", max.toString()).addQueryParameter("search_lang", lang).build()
        val req = Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).build()
        return WebSearch.parseBrave(client.text(req))
    }
}

class LangSearchBackend(private val key: String, private val client: OkHttpClient, private val base: String = "https://api.langsearch.com") : SearchBackend {
    override val id = "langsearch"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val body = buildJsonObject { put("query", query); put("count", max); put("freshness", "noLimit") }
        return WebSearch.parseLangSearch(client.text(postJson(joinUrl(base, "v1/web-search"), body, mapOf("Authorization" to "Bearer $key"))))
    }
}

class SerperBackend(private val key: String, private val client: OkHttpClient, private val base: String = "https://google.serper.dev") : SearchBackend {
    override val id = "serper"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val body = buildJsonObject { put("q", query); put("num", max); put("hl", lang); if (lang == "id") put("gl", "id") }
        return WebSearch.parseSerper(client.text(postJson(joinUrl(base, "search"), body, mapOf("X-API-KEY" to key))))
    }
}

/** SearXNG (instans milik sendiri). Instans harus mengaktifkan format json di settings.yml. */
class SearxngBackend(private val baseUrl: String, private val client: OkHttpClient) : SearchBackend {
    override val id = "searxng"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val base = baseUrl.trim().removeSuffix("/").removeSuffix("/search")
        val url = "$base/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("format", "json")
            .addQueryParameter("language", lang).addQueryParameter("safesearch", "1").build()
        val text = client.text(Request.Builder().url(url).header("Accept", "application/json").build())
        if (text.trimStart().startsWith("<")) throw BlockedException("searxng")
        return WebSearch.parseSearxng(text)
    }
}

/** html.duckduckgo.com — gratis tanpa key; bisa menampilkan tantangan anti-bot dari IP pusat data. */
class DuckDuckGoBackend(private val client: OkHttpClient, private val base: String = "https://html.duckduckgo.com") : SearchBackend {
    override val id = "duckduckgo"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val form = FormBody.Builder().add("q", query).add("kl", if (lang == "id") "id-id" else "wt-wt").build()
        val req = Request.Builder().url(joinUrl(base, "html/")).post(form)
            .header("User-Agent", BROWSER_UA).header("Accept-Language", if (lang == "id") "id-ID,id;q=0.9,en;q=0.6" else "en-US,en;q=0.9")
            .header("Accept", "text/html").build()
        val resp = client.newCall(req).await()
        val code = resp.code
        val html = resp.bodyOrThrow()
        if (code == 202) throw BlockedException("duckduckgo")
        return WebSearch.parseDuckDuckGoHtml(html)
    }
}

class WikipediaBackend(private val client: OkHttpClient, private val baseFor: (String) -> String = { "https://$it.wikipedia.org" }) : SearchBackend {
    override val id = "wikipedia"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val l = lang.ifBlank { "en" }
        val url = joinUrl(baseFor(l), "w/rest.php/v1/search/page").toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", max.coerceAtMost(5).toString()).build()
        val req = Request.Builder().url(url).header("User-Agent", "CompanionApp/0.2 (Android; contact dev@coactive.local)").build()
        return WebSearch.parseWikipedia(client.text(req), l)
    }
}

/**
 * Groq built-in `browser_search` (gpt-oss): pencarian dijalankan di server Groq memakai key LLM.
 * Boros token (~8k token per pencarian), jadi lebih lambat dan cepat kena batas TPM paket gratis.
 */
class GroqBrowserBackend(
    private val baseUrl: String, private val key: String, private val model: String,
    private val client: OkHttpClient = Http.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build(),
) : SearchBackend {
    override val id = "groq"
    override suspend fun search(query: String, max: Int, lang: String): SearchOutcome {
        val m = if (model.startsWith("openai/gpt-oss")) model else "openai/gpt-oss-20b"
        val body = buildJsonObject {
            put("model", m)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Search the web and summarise the most relevant, current facts for: \"$query\". Reply in ${if (lang == "id") "Indonesian" else "English"}, at most 6 short sentences, include dates/numbers.")
                })
            }
            putJsonArray("tools") { add(buildJsonObject { put("type", "browser_search") }) }
            put("tool_choice", "required")
            put("reasoning_effort", "low")
            put("max_completion_tokens", 1200)
        }
        return WebSearch.parseGroqBrowser(client.text(postJson(joinUrl(baseUrl, "chat/completions"), body, mapOf("Authorization" to "Bearer $key"))))
    }
}
