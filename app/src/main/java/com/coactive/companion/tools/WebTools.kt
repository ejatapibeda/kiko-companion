package com.coactive.companion.tools

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.coactive.companion.search.SearchBackend
import com.coactive.companion.search.SearchUnavailableException
import com.coactive.companion.search.WebSearch
import com.coactive.companion.search.WebSearchConfig

/** Cuaca via Open-Meteo (gratis, tanpa API key). */
class WeatherTool(
    private val defaultCity: () -> String,
    private val language: () -> String,
    private val client: OkHttpClient = Http.client,
    private val geoBase: String = "https://geocoding-api.open-meteo.com",
    private val forecastBase: String = "https://api.open-meteo.com",
) : AssistantTool {
    override val name = "get_weather"
    override val description = "Get current weather and today's forecast for a city. If no city is given, uses the user's default city."
    override val parameters = schema { string("location", "City name, e.g. 'Bandung'") }

    override suspend fun execute(args: JsonObject): String {
        val city = args.str("location")?.takeIf { it.isNotBlank() } ?: defaultCity()
        val geoUrl = "$geoBase/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", city).addQueryParameter("count", "1")
            .addQueryParameter("language", language()).build()
        val geo = Http.json.parseToJsonElement(get(geoUrl.toString())).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return """{"error":"kota '$city' tidak ditemukan"}"""
        val lat = geo["latitude"]!!.jsonPrimitive.doubleOrNull
        val lon = geo["longitude"]!!.jsonPrimitive.doubleOrNull
        val url = "$forecastBase/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString()).addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code")
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "1").build()
        val f = Http.json.parseToJsonElement(get(url.toString())).jsonObject
        val cur = f["current"]?.jsonObject
        val daily = f["daily"]?.jsonObject
        fun d(k: String) = daily?.get(k)?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        return buildJsonObject {
            put("location", geo["name"]?.jsonPrimitive?.contentOrNull ?: city)
            put("temperature_c", cur?.get("temperature_2m")?.jsonPrimitive?.contentOrNull)
            put("feels_like_c", cur?.get("apparent_temperature")?.jsonPrimitive?.contentOrNull)
            put("humidity_pct", cur?.get("relative_humidity_2m")?.jsonPrimitive?.contentOrNull)
            put("wind_kmh", cur?.get("wind_speed_10m")?.jsonPrimitive?.contentOrNull)
            put("condition", WeatherCodes.describe(cur?.get("weather_code")?.jsonPrimitive?.intOrNull ?: -1))
            put("today_max_c", d("temperature_2m_max")); put("today_min_c", d("temperature_2m_min"))
            put("rain_chance_pct", d("precipitation_probability_max"))
        }.toString()
    }

    private suspend fun get(url: String) = client.newCall(Request.Builder().url(url).build()).await().bodyOrThrow()
}

object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "cerah"; 1 -> "cerah berawan"; 2 -> "berawan sebagian"; 3 -> "mendung"
        45, 48 -> "berkabut"
        51, 53, 55, 56, 57 -> "gerimis"
        61, 63, 65, 66, 67, 80, 81, 82 -> "hujan"
        71, 73, 75, 77, 85, 86 -> "salju"
        95, 96, 99 -> "badai petir"
        else -> "tidak diketahui"
    }
}

/**
 * `web_search`: pencarian web dengan penyedia yang dipilih di Pengaturan (default gratis tanpa key, ada fallback).
 * Hasil dinormalkan (judul, url, tanggal, potongan teks) untuk LLM. Bila semua penyedia gagal, mengembalikan
 * `error: search_unavailable` + kalimat permintaan maaf untuk diucapkan.
 */
class WebSearchTool(
    private val config: () -> WebSearchConfig,
    private val language: () -> String,
    private val backends: (WebSearchConfig) -> List<SearchBackend> = { WebSearch.chain(it) },
    private val totalTimeoutMs: Long = WebSearch.TOTAL_TIMEOUT_MS,
) : AssistantTool {
    override val name = "web_search"
    override val description = "Search the live web for current or factual info: news, prices, schedules, scores, recent events, " +
        "people, places, products. Use it whenever the user asks you to search/look something up (cari, cariin, carikan, search, " +
        "googling) or the answer may have changed recently. Returns titles, URLs, dates and snippets to answer from."
    override val parameters = schema {
        string("query", "Concise search query in the most useful language (keep names, add the year for current events)", required = true)
        integer("num_results", "Number of results, 1-8 (default 5)")
    }

    override suspend fun execute(args: JsonObject): String {
        val q = args.str("query")?.trim().orEmpty()
        if (q.isEmpty()) return """{"error":"query wajib"}"""
        val n = (args.int("num_results") ?: 5).coerceIn(1, 8)
        val lang = language().ifBlank { "en" }
        val cfg = config()
        return try {
            val out = kotlinx.coroutines.withTimeout(totalTimeoutMs) { WebSearch.run(backends(cfg), q, n, lang) }
            android.util.Log.i("WebSearch", "provider=${out.provider} results=${out.results.size} tried=${out.attempts.joinToString(",")}")
            WebSearch.toToolJson(q, out)
        } catch (e: SearchUnavailableException) {
            android.util.Log.w("WebSearch", "unavailable tried=${e.attempts.joinToString(",")}")
            WebSearch.unavailableJson(q, lang)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("WebSearch", "unavailable: total timeout")
            WebSearch.unavailableJson(q, lang)
        }
    }
}

/**
 * `browse_web` tanpa key: baca isi halaman via Tavily extract (keyless atau ber-key), lalu Jina Reader.
 * Bila key Exa diisi, Exa /contents dicoba lebih dulu.
 */
class ReaderBrowseTool(
    private val config: () -> WebSearchConfig,
    private val client: OkHttpClient = WebSearch.client,
    private val tavilyBase: String = "https://api.tavily.com",
    private val jinaBase: String = "https://r.jina.ai",
    private val exa: (() -> ExaClient?)? = null,
) : AssistantTool {
    override val name = "browse_web"
    override val description = "Read the main text of a web page (e.g. a URL from web_search results) when snippets are not enough."
    override val parameters = schema { string("url", "Full http(s) URL", required = true) }

    override suspend fun execute(args: JsonObject): String {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http")) return """{"error":"url tidak valid"}"""
        val tried = mutableListOf<String>()
        exa?.invoke()?.let { ex ->
            runCatching { return ExaBrowseTool(ex).execute(args) }.onFailure { tried += "exa:" + WebSearch.describe(it) }
        }
        try {
            val key = config().tavilyKey
            val h = if (key.isBlank()) "X-Tavily-Access-Mode" to "keyless" else "Authorization" to "Bearer $key"
            val body = buildJsonObject { putJsonArray("urls") { add(kotlinx.serialization.json.JsonPrimitive(url)) } }
            val req = Request.Builder().url(com.coactive.companion.net.joinUrl(tavilyBase, "extract")).header(h.first, h.second)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val o = Http.json.parseToJsonElement(client.newCall(req).await().bodyOrThrow()).jsonObject
            val r = o["results"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = r?.get("raw_content")?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) return pageJson(url, r["title"]?.jsonPrimitive?.contentOrNull, text)
            tried += "tavily:empty"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { tried += "tavily:" + WebSearch.describe(e) }
        try {
            val req = Request.Builder().url(com.coactive.companion.net.joinUrl(jinaBase, url)).header("Accept", "text/plain").build()
            val text = client.newCall(req).await().bodyOrThrow()
            if (text.isNotBlank()) return pageJson(url, null, text)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { tried += "jina:" + WebSearch.describe(e) }
        android.util.Log.w("WebSearch", "browse failed tried=${tried.joinToString(",")}")
        return """{"error":"halaman tidak bisa dibaca"}"""
    }

    private fun pageJson(url: String, title: String?, raw: String) = buildJsonObject {
        put("url", url); title?.let { put("title", it) }
        put("text", cleanPage(raw).take(4000))
    }.toString()

    companion object {
        /** Buang gambar/link markdown & baris navigasi pendek agar hemat token. */
        fun cleanPage(raw: String): String = raw
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .lines().map { it.trim() }.filter { it.length > 2 }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }
}
