package com.coactive.companion

import com.coactive.companion.assistant.EmotionParser
import com.coactive.companion.llm.ChatMessage
import com.coactive.companion.llm.ChatRequest
import com.coactive.companion.llm.OpenAiChatLlm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
    @Test fun textOnlyRequestKeepsStringContent() {
        val body = OpenAiChatLlm.encodeRequest(ChatRequest("m", listOf(ChatMessage.system("s"), ChatMessage.user("hi"))))
        val msgs = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        assertTrue(msgs[1].jsonObject["content"] is JsonPrimitive)
    }

    @Test fun imageMessageBecomesVisionContentArray() {
        val req = ChatRequest("m", listOf(ChatMessage.system("s"), ChatMessage("user", "what is this?", images = listOf("data:image/jpeg;base64,AAA"))))
        val msgs = Json.parseToJsonElement(OpenAiChatLlm.encodeRequest(req)).jsonObject["messages"]!!.jsonArray
        val content = msgs[1].jsonObject["content"] as JsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this?", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAA", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(msgs[0].jsonObject["content"] is JsonPrimitive)
    }

    @Test fun thinkBlockStripped() {
        assertEquals("Hello!", EmotionParser.parse("<think>hmm let me see</think>\n[happy] Hello!").text)
    }
}
