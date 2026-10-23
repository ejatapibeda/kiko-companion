package com.coactive.companion.tools

import com.coactive.companion.llm.FunctionSpec
import com.coactive.companion.llm.ToolSpec
import com.coactive.companion.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool yang bisa dipanggil LLM. Untuk menambah tool baru: buat class yang meng-implement
 * interface ini, lalu daftarkan di [com.coactive.companion.tools.DefaultTools.create].
 */
interface AssistantTool {
    val name: String
    val description: String
    /** JSON Schema parameter (pakai [schema] untuk menyusunnya). */
    val parameters: JsonObject
    /** Mengembalikan hasil singkat (teks/JSON) yang akan dibaca LLM. */
    suspend fun execute(args: JsonObject): String
}

class ToolRegistry(tools: List<AssistantTool>) {
    private val byName = tools.associateBy { it.name }
    val tools: Collection<AssistantTool> get() = byName.values

    fun specs(): List<ToolSpec> = byName.values.map { ToolSpec(function = FunctionSpec(it.name, it.description, it.parameters)) }

    /** Menjalankan tool; error dikembalikan sebagai teks supaya LLM bisa menjelaskannya ke pengguna. */
    suspend fun execute(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return """{"error":"tool '$name' tidak dikenal"}"""
        val args = runCatching { Http.json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }
            .getOrElse { return """{"error":"argumen JSON tidak valid"}""" }
        return try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }.toString()
        }
    }
}

/** DSL kecil untuk JSON Schema. */
class SchemaBuilder {
    internal val props = mutableMapOf<String, JsonObject>()
    internal val required = mutableListOf<String>()
    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(name, "string", description, required) { if (enum != null) putJsonArray("enum") { enum.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
    fun integer(name: String, description: String, required: Boolean = false) = prop(name, "integer", description, required)
    fun number(name: String, description: String, required: Boolean = false) = prop(name, "number", description, required)
    fun boolean(name: String, description: String, required: Boolean = false) = prop(name, "boolean", description, required)
    private fun prop(name: String, type: String, desc: String, req: Boolean, extra: JsonObjectBuilder.() -> Unit = {}) {
        props[name] = buildJsonObject { put("type", type); put("description", desc); extra() }
        if (req) required += name
    }
}

fun schema(block: SchemaBuilder.() -> Unit = {}): JsonObject {
    val b = SchemaBuilder().apply(block)
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { b.props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { b.required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
fun JsonObject.num(key: String): Double? = runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
