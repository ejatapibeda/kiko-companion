package com.coactive.companion.tools

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import com.coactive.companion.net.joinUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Klien kecil untuk Exa API (https://exa.ai): /search dan /contents, auth header x-api-key. */
class ExaClient(
    private val apiKey: () -> String,
    private val baseUrl: () -> String = { "https://api.exa.ai" },
    private val client: OkHttpClient = Http.client,
) {
    suspend fun post(path: String, body: JsonObject): JsonObject {
        val key = apiKey()
        require(key.isNotBlank()) { "API key Exa belum diisi di Pengaturan" }
        val req = Request.Builder().url(joinUrl(baseUrl(), path))
            .header("x-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return Http.json.parseToJsonElement(client.newCall(req).await().bodyOrThrow()).jsonObject
    }

    /** Ringkas hasil Exa jadi JSON pendek untuk LLM (judul, url, tanggal, potongan teks). */
    fun compact(results: JsonArray?, maxChars: Int): JsonArray = buildJsonObject {
        putJsonArray("r") {
            results.orEmpty().forEach { el ->
                val o = el.jsonObject
                fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val highlights = runCatching { o["highlights"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
                val text = (highlights.joinToString(" … ").ifBlank { null } ?: s("summary") ?: s("text").orEmpty())
                    .replace(Regex("\\s+"), " ").trim().take(maxChars)
                add(buildJsonObject {
                    put("title", s("title")); put("url", s("url"))
                    s("publishedDate")?.let { put("published", it.take(10)) }
                    put("text", text)
                })
            }
        }
    }["r"]!!.jsonArray
}

/**
 * Pencarian web segar via Exa. Didaftarkan sebagai "web_search" bila API key Exa diisi
 * (menggantikan pencarian Wikipedia/DuckDuckGo gratis).
 */
class ExaSearchTool(private val exa: ExaClient, private val numResults: Int = 5) : AssistantTool {
    override val name = "web_search"
    override val description = "Search the live web (Exa) for fresh or factual information: news, prices, schedules, " +
        "recent events, people, products. Returns titles, URLs, dates and text snippets to answer from."
    override val parameters = schema {
        string("query", "Natural-language search query (any language)", required = true)
        integer("num_results", "Number of results, 1-8 (default 5)")
        string("category", "Optional focus", enum = listOf("news", "company", "people", "publication", "personal site", "financial report"))
    }

    override suspend fun execute(args: JsonObject): String {
        val q = args.str("query")?.trim().orEmpty()
        if (q.isEmpty()) return """{"error":"query wajib"}"""
        val body = buildJsonObject {
            put("query", q)
            put("type", "auto")
            put("numResults", (args.int("num_results") ?: numResults).coerceIn(1, 8))
            args.str("category")?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            putJsonObject("contents") {
                putJsonObject("text") { put("maxCharacters", 1500) }
                putJsonObject("highlights") { put("maxCharacters", 700) }
            }
        }
        val res = exa.post("search", body)
        val results = exa.compact(res["results"] as? JsonArray, 900)
        if (results.isEmpty()) return """{"error":"tidak ada hasil"}"""
        return buildJsonObject { put("query", q); put("results", results) }.toString()
    }
}

/** Membaca isi halaman tertentu (URL) via Exa /contents — "browsing" satu halaman. */
class ExaBrowseTool(private val exa: ExaClient) : AssistantTool {
    override val name = "browse_web"
    override val description = "Open and read a specific web page URL (e.g. one returned by web_search) to get its main text."
    override val parameters = schema { string("url", "Full URL to read", required = true) }

    override suspend fun execute(args: JsonObject): String {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http")) return """{"error":"url tidak valid"}"""
        val body = buildJsonObject {
            putJsonArray("urls") { add(kotlinx.serialization.json.JsonPrimitive(url)) }
            putJsonObject("text") { put("maxCharacters", 4000) }
        }
        val res = exa.post("contents", body)
        val page = exa.compact(res["results"] as? JsonArray, 3500).firstOrNull()
            ?: return """{"error":"halaman tidak bisa dibaca"}"""
        return page.toString()
    }
}
