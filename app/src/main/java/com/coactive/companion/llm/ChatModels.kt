package com.coactive.companion.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    /**
     * Gambar (data URL "data:image/jpeg;base64,...") untuk pesan user. Tidak diserialisasi langsung:
     * [OpenAiChatLlm] mengubah `content` menjadi array {text, image_url} (format vision OpenAI-compatible).
     */
    @Transient val images: List<String> = emptyList(),
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
        fun tool(id: String, name: String, result: String) = ChatMessage("tool", result, toolCallId = id, name = name)
    }
}

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: FunctionCall)

@Serializable
data class FunctionCall(val name: String, val arguments: String = "{}")

@Serializable
data class ToolSpec(val type: String = "function", val function: FunctionSpec)

@Serializable
data class FunctionSpec(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = 0.7,
)

@Serializable
data class ChatResponse(val choices: List<Choice> = emptyList()) {
    @Serializable
    data class Choice(val message: ChatMessage, @SerialName("finish_reason") val finishReason: String? = null)
}
