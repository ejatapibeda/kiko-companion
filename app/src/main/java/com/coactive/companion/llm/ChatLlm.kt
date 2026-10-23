package com.coactive.companion.llm

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import com.coactive.companion.net.joinUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Antarmuka LLM yang bisa diganti (pluggable). */
interface ChatLlm {
    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage
}

/** Implementasi Chat Completions OpenAI-compatible (OpenAI, xAI, Groq, Gemini /v1beta/openai, OpenRouter, dll). */
class OpenAiChatLlm(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = Http.client,
) : ChatLlm {
    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        require(apiKey.isNotBlank()) { "API key LLM belum diisi di Pengaturan" }
        val req = ChatRequest(
            model = model,
            messages = messages,
            tools = tools.ifEmpty { null },
            toolChoice = if (tools.isEmpty()) null else "auto",
        )
        val body = encodeRequest(req)
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = client.newCall(request).await().bodyOrThrow()
        val resp = Http.json.decodeFromString(ChatResponse.serializer(), text)
        return resp.choices.firstOrNull()?.message ?: error("Respons LLM kosong")
    }

    companion object {
        /** JSON permintaan; pesan dengan gambar → content = [{type:text}, {type:image_url, image_url:{url}}...]. */
        fun encodeRequest(req: ChatRequest): String {
            if (req.messages.none { it.images.isNotEmpty() }) return Http.json.encodeToString(ChatRequest.serializer(), req)
            val root = Http.json.encodeToJsonElement(ChatRequest.serializer(), req).jsonObject
            val msgs = root["messages"]!!.jsonArray.mapIndexed { i, el ->
                val m = req.messages[i]
                if (m.images.isEmpty()) el else JsonObject(el.jsonObject + ("content" to buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", m.content.orEmpty()) })
                    m.images.forEach { url ->
                        add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) })
                    }
                }))
            }
            return Http.json.encodeToString(JsonObject.serializer(), JsonObject(root + ("messages" to JsonArray(msgs))))
        }
    }
}
