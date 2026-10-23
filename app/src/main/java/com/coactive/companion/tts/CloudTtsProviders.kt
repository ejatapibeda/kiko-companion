package com.coactive.companion.tts

import android.util.Base64
import android.util.Log
import com.coactive.companion.net.ApiException
import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import com.coactive.companion.net.joinUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()

/** Decoder base64 yang bisa diganti di unit test JVM (android.util.Base64 tidak tersedia di JVM). */
internal var base64Decode: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) }

/**
 * TikTok TTS (tidak resmi, tanpa API key). Rantai per potongan teks:
 * 1. Proxy utama sesuai [baseUrl]:
 *    - lazypy.ro (default): POST {baseUrl}/tts/request_tts.php (form service=TikTok&voice&text)
 *      -> {"success":true,"audio_url":"...mp3","error_msg":null}, lalu GET audio_url.
 *    - proxy gaya Weilbyte (base URL lain/self-host): POST {baseUrl}/api/generation {"text","voice"}
 *      -> {"success":true,"data":"<base64 mp3>"}.
 * 2. Jika proxy gagal/timeout dan [sessionId] diisi: API resmi TikTok dengan cookie sessionid.
 * 3. Jika semuanya gagal, exception dilempar -> [FallbackTtsEngine] memakai Android TTS.
 * Hasil disimpan di [TtsAudioCache] (voice+teks). Batas ~300 karakter per permintaan, jadi teks dipecah.
 */
class TikTokTts(
    private val baseUrl: String,
    private val voice: String,
    private val sessionId: String = "",
    private val client: OkHttpClient = Http.client,
    private val proxyTimeoutMs: Long = 12_000,
    private val cache: TtsAudioCache? = TtsAudioCache,
    private val officialUrl: String = OFFICIAL_URL,
    /** Format request lazypy; default dideteksi dari host [baseUrl]. */
    private val lazypyMode: Boolean = isLazypy(baseUrl),
) : CloudTts {
    private val proxyClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(proxyTimeoutMs, TimeUnit.MILLISECONDS).build()
    }

    override fun synthesize(text: String): Flow<TtsAudio> = flow {
        for (part in TextChunker.chunk(text, 280)) emit(TtsAudio.Encoded(synthesizeChunk(part), "mp3"))
    }

    private suspend fun synthesizeChunk(part: String): ByteArray {
        cache?.get(voice, part)?.let { return it }
        val bytes = try {
            viaProxy(part)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (sessionId.isBlank()) throw e
            runCatching { Log.w("TikTokTts", "proxy gagal (${e.message}), coba API resmi") } // Log tidak tersedia di unit test JVM
            viaOfficial(part)
        }
        cache?.put(voice, part, bytes)
        return bytes
    }

    private suspend fun viaProxy(text: String): ByteArray =
        if (lazypyMode) viaLazypy(text) else viaWeilbyte(text)

    private suspend fun viaLazypy(text: String): ByteArray {
        val form = FormBody.Builder().add("service", "TikTok").add("voice", voice).add("text", text).build()
        val req = Request.Builder().url(joinUrl(baseUrl, "tts/request_tts.php")).post(form).build()
        val obj = Http.json.parseToJsonElement(proxyClient.newCall(req).await().bodyOrThrow()).jsonObject
        val url = obj["audio_url"]?.jsonPrimitive?.contentOrNull
        if (obj["success"]?.jsonPrimitive?.contentOrNull != "true" || url.isNullOrBlank()) {
            throw ApiException(200, "lazypy TTS gagal: ${obj["error_msg"]?.jsonPrimitive?.contentOrNull ?: obj["error_msg"]}")
        }
        // hanya ikuti URL audio di host yang sama dengan proxy (atau relatif terhadapnya)
        val audioUrl = baseUrl.toHttpUrl().resolve(url) ?: throw ApiException(200, "lazypy audio_url tidak valid")
        if (audioUrl.host != baseUrl.toHttpUrl().host) throw ApiException(200, "lazypy audio_url di host lain: ${audioUrl.host}")
        val bytes = proxyClient.newCall(Request.Builder().url(audioUrl).get().build()).await().use {
            if (!it.isSuccessful) throw ApiException(it.code, "unduh audio lazypy gagal")
            it.body!!.bytes()
        }
        if (!looksLikeMp3(bytes)) throw ApiException(200, "audio lazypy bukan mp3 (${bytes.size} B)")
        return bytes
    }

    private suspend fun viaWeilbyte(text: String): ByteArray {
        val body = buildJsonObject { put("text", text); put("voice", voice) }.toString()
        val req = Request.Builder().url(joinUrl(baseUrl, "api/generation"))
            .post(body.toRequestBody(JSON)).build()
        val obj = Http.json.parseToJsonElement(proxyClient.newCall(req).await().bodyOrThrow()).jsonObject
        val data = obj["data"]?.jsonPrimitive?.contentOrNull
        if (obj["success"]?.jsonPrimitive?.contentOrNull != "true" || data.isNullOrBlank()) {
            throw ApiException(200, "TikTok TTS gagal: ${obj["error"]}")
        }
        return base64Decode(data)
    }

    private suspend fun viaOfficial(text: String): ByteArray {
        val url = officialUrl.toHttpUrl().newBuilder()
            .addQueryParameter("text_speaker", voice)
            .addQueryParameter("req_text", text)
            .addQueryParameter("speaker_map_type", "0")
            .addQueryParameter("aid", "1233").build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "com.zhiliaoapp.musically/2022600030 (Linux; U; Android 7.1.2; es_ES; SM-G988N; Build/NRD90M;tt-ok/3.12.13.1)")
            .header("Cookie", "sessionid=$sessionId")
            .post(ByteArray(0).toRequestBody(null)).build()
        val obj = Http.json.parseToJsonElement(client.newCall(req).await().bodyOrThrow()).jsonObject
        val data = obj["data"]?.jsonObject?.get("v_str")?.jsonPrimitive?.contentOrNull
        if (data.isNullOrBlank()) throw ApiException(200, "TikTok resmi gagal: ${obj["message"]}")
        return base64Decode(data)
    }

    companion object {
        const val LAZYPY_URL = "https://lazypy.ro"
        const val OFFICIAL_URL = "https://api16-normal-v6.tiktokv.com/media/api/text/speech/invoke/"
        /** Proxy lama Weilbyte (sudah mati sejak 2025/2026) — dimigrasikan ke [LAZYPY_URL]. */
        val DEAD_URLS = setOf("https://tiktok-tts.weilnet.workers.dev", "https://ottsy.weilbyte.dev")

        fun isLazypy(baseUrl: String): Boolean =
            runCatching { baseUrl.trim().toHttpUrl().host.let { it == "lazypy.ro" || it.endsWith(".lazypy.ro") } }.getOrDefault(false)

        fun looksLikeMp3(b: ByteArray): Boolean = b.size > 64 &&
            ((b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) ||
                ((b[0].toInt() and 0xff) == 0xff && (b[1].toInt() and 0xe0) == 0xe0))
    }
}

/**
 * ElevenLabs resmi: POST /v1/text-to-speech/{voice_id}/stream?output_format=pcm_22050 dengan header xi-api-key.
 * Memakai endpoint streaming sehingga audio mulai diputar sebelum unduhan selesai.
 */
class ElevenLabsTts(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String,
    private val languageCode: String? = null,
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val client: OkHttpClient = Http.client,
) : CloudTts {
    override fun synthesize(text: String): Flow<TtsAudio> = flow {
        require(apiKey.isNotBlank()) { "API key ElevenLabs belum diisi" }
        val body = buildJsonObject {
            put("text", text)
            put("model_id", modelId)
            // language_code hanya didukung sebagian model (mis. eleven_flash_v2_5 / turbo_v2_5)
            if (languageCode != null && (modelId.contains("v2_5") || modelId.contains("v3"))) put("language_code", languageCode)
            putJsonObject("voice_settings") { put("stability", 0.5); put("similarity_boost", 0.75) }
        }.toString()
        val req = Request.Builder()
            .url(joinUrl(baseUrl, "v1/text-to-speech/$voiceId/stream?output_format=pcm_22050"))
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/pcm")
            .post(body.toRequestBody(JSON)).build()
        val resp = client.newCall(req).await()
        if (!resp.isSuccessful) {
            val msg = resp.body?.string().orEmpty(); resp.close()
            throw ApiException(resp.code, msg.take(300))
        }
        emit(TtsAudio.PcmStream(22050, resp.body!!.byteStream()))
    }
}

/** OpenAI-compatible /audio/speech (OpenAI, Groq, server lokal, dll). */
class OpenAiCompatTts(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val client: OkHttpClient = Http.client,
) : CloudTts {
    override fun synthesize(text: String): Flow<TtsAudio> = flow {
        val body = buildJsonObject {
            put("model", model); put("input", text); put("voice", voice); put("response_format", "mp3")
        }.toString()
        val req = Request.Builder().url(joinUrl(baseUrl, "audio/speech"))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toRequestBody(JSON)).build()
        val resp = client.newCall(req).await()
        val bytes = resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, it.body?.string().orEmpty().take(300))
            it.body!!.bytes()
        }
        val ext = if (bytes.size > 4 && String(bytes, 0, 4) == "RIFF") "wav" else "mp3"
        emit(TtsAudio.Encoded(bytes, ext))
    }
}
