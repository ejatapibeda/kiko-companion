package com.coactive.companion.stt

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import com.coactive.companion.net.joinUrl
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Antarmuka STT yang bisa diganti. Input: WAV 16 kHz mono PCM16. */
interface SpeechToText {
    suspend fun transcribe(wav: ByteArray, languageCode: String): String
}

/** OpenAI-compatible /audio/transcriptions (OpenAI Whisper, Groq whisper-large-v3, dll). */
class OpenAiSpeechToText(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = Http.client,
) : SpeechToText {
    override suspend fun transcribe(wav: ByteArray, languageCode: String): String {
        require(apiKey.isNotBlank()) { "API key STT belum diisi di Pengaturan" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", languageCode)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "audio/transcriptions"))
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        val text = client.newCall(request).await().bodyOrThrow()
        return Http.json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content?.trim().orEmpty()
    }
}
