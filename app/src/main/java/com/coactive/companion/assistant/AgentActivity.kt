package com.coactive.companion.assistant

import com.coactive.companion.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Apa yang sedang dikerjakan Kiko (untuk indikator di wajah, pil status, dan baris status chat). */
enum class ActivityKind {
    LISTENING, TRANSCRIBING, THINKING, VISION,
    WEB_SEARCH, BROWSING,
    SPOTIFY_PLAY, SPOTIFY_PAUSE, SPOTIFY_RESUME, SPOTIFY_NEXT, SPOTIFY_PREVIOUS, SPOTIFY_NOW_PLAYING,
    WEATHER, LYRICS, TIMER, ALARM, OPEN_APP, DATETIME, DANCE, TOOL,
    SPEAKING_PREP;

    val isSpotify get() = name.startsWith("SPOTIFY_")
    /** Aktivitas "alat" (tool) yang dicatat sebagai langkah di balasan chat. */
    val isTool get() = this !in setOf(LISTENING, TRANSCRIBING, THINKING, VISION, SPEAKING_PREP, LYRICS)
}

/** [seq] naik setiap perubahan → UI bisa membedakan dua panggilan tool berurutan yang sama. */
data class AgentActivity(val kind: ActivityKind, val detail: String? = null, val failed: Boolean = false, val seq: Int = 0)

/** Langkah tool yang tersimpan di pesan chat. [kind] berupa String supaya riwayat lama/baru tetap bisa dibaca. */
@Serializable
data class ActivityStep(val kind: String, val detail: String? = null, val failed: Boolean = false) {
    val activityKind: ActivityKind get() = runCatching { ActivityKind.valueOf(kind) }.getOrDefault(ActivityKind.TOOL)
}

/** Memetakan nama tool + argumen JSON (dari LLM) → jenis aktivitas & detail singkat yang aman ditampilkan. */
object ToolActivity {
    private const val MAX_DETAIL = 60

    fun of(toolName: String, argsJson: String): Pair<ActivityKind, String?> {
        val args = runCatching { Http.json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
        fun a(k: String) = args?.get(k)?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim()?.takeIf { it.isNotEmpty() }
        return when (toolName) {
            "web_search" -> ActivityKind.WEB_SEARCH to a("query")?.let(::clip)
            "browse_web" -> ActivityKind.BROWSING to a("url")?.let(::host)
            "spotify_play" -> ActivityKind.SPOTIFY_PLAY to a("query")?.let(::clip)
            "spotify_control" -> when (a("action")?.lowercase()) {
                "pause" -> ActivityKind.SPOTIFY_PAUSE
                "resume", "play" -> ActivityKind.SPOTIFY_RESUME
                "next", "skip" -> ActivityKind.SPOTIFY_NEXT
                "previous", "prev", "back" -> ActivityKind.SPOTIFY_PREVIOUS
                else -> ActivityKind.SPOTIFY_RESUME
            } to null
            "spotify_now_playing" -> ActivityKind.SPOTIFY_NOW_PLAYING to null
            "get_weather" -> ActivityKind.WEATHER to a("location")?.let(::clip)
            "set_timer" -> ActivityKind.TIMER to (args?.get("seconds") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { formatSeconds(it.toInt()) }
            "set_alarm" -> ActivityKind.ALARM to alarmTime(args)
            "open_app" -> ActivityKind.OPEN_APP to a("app_name")?.let(::clip)
            "get_datetime" -> ActivityKind.DATETIME to null
            "face_dance" -> ActivityKind.DANCE to null
            else -> ActivityKind.TOOL to toolName.take(MAX_DETAIL)
        }
    }

    /** Output tool dianggap gagal bila berupa objek JSON dengan "error" atau "ok": false. */
    fun isError(output: String): Boolean {
        val t = output.trimStart()
        if (!t.startsWith("{")) return false
        val o = runCatching { Http.json.parseToJsonElement(t).jsonObject }.getOrNull() ?: return false
        return "error" in o || (o["ok"] as? JsonPrimitive)?.booleanOrNull == false
    }

    fun clip(s: String): String = s.replace(Regex("\\s+"), " ").let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL - 1).trimEnd() + "…" else it }

    fun host(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull()
        ?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: clip(url.substringAfter("://").substringBefore('/'))

    private fun formatSeconds(s: Int): String = when {
        s <= 0 -> "0s"
        s % 3600 == 0 -> "${s / 3600}h"
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s % 60 == 0 -> "${s / 60}m"
        s > 60 -> "${s / 60}m ${s % 60}s"
        else -> "${s}s"
    }

    private fun alarmTime(args: JsonObject?): String? {
        val h = (args?.get("hour") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return null
        val m = (args["minute"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        return "%02d:%02d".format(h, m)
    }
}

/**
 * Mesin status aktivitas satu giliran. Digerakkan oleh siklus hidup nyata (mulai/akhir panggilan LLM & tool,
 * STT, TTS), bukan timer. Aman dipanggil dari coroutine mana pun (StateFlow).
 *
 *  listening → transcribing → thinking → [tool → (selesai/gagal) → thinking]* → speaking_prep → (clear)
 */
class ActivityTracker(private val onChange: (AgentActivity?) -> Unit = {}) {
    private val _current = MutableStateFlow<AgentActivity?>(null)
    val current: StateFlow<AgentActivity?> = _current.asStateFlow()
    private val _steps = mutableListOf<ActivityStep>()
    /** Langkah tool di giliran ini (urut), termasuk yang gagal. */
    val steps: List<ActivityStep> get() = synchronized(_steps) { _steps.toList() }
    private var seq = 0
    @Volatile private var turn = 0
    private var toolStartedKind: ActivityKind? = null

    private fun emit(a: AgentActivity?) { _current.value = a; onChange(a) }
    private fun set(kind: ActivityKind, detail: String? = null, failed: Boolean = false) = emit(AgentActivity(kind, detail, failed, ++seq))

    /** Awal giliran baru: buang langkah lama. */
    /** @return id giliran; berikan ke [clear] supaya giliran lama yang dibatalkan tidak menghapus status giliran baru. */
    fun beginTurn(): Int { synchronized(_steps) { _steps.clear() }; toolStartedKind = null; return ++turn }
    fun listening() = set(ActivityKind.LISTENING)
    fun transcribing() = set(ActivityKind.TRANSCRIBING)
    /** Dipanggil tepat sebelum setiap permintaan ke LLM. Putaran pertama dengan gambar = VISION. */
    fun llmRequest(round: Int, hasImages: Boolean) =
        set(if (round == 0 && hasImages) ActivityKind.VISION else ActivityKind.THINKING)
    fun toolStarted(name: String, argsJson: String) {
        val (kind, detail) = ToolActivity.of(name, argsJson)
        toolStartedKind = kind
        set(kind, detail)
    }
    /** Tool selesai. Bila gagal, status tetap tampil sebagai "gagal" sampai langkah berikutnya mulai. */
    fun toolFinished(name: String, output: String) {
        val cur = _current.value
        val (kind, detail) = if (cur != null && cur.kind == toolStartedKind) cur.kind to cur.detail else ToolActivity.of(name, "{}")
        val failed = ToolActivity.isError(output)
        synchronized(_steps) { _steps += ActivityStep(kind.name, detail, failed) }
        toolStartedKind = null
        if (failed) set(kind, detail, failed = true)
    }
    fun speakingPrep() = set(ActivityKind.SPEAKING_PREP)
    fun lyrics() = set(ActivityKind.LYRICS)
    /** Hapus status (idle, batal, error). Langkah tetap tersedia sampai [beginTurn]. */
    fun clear(turnId: Int? = null) {
        if (turnId != null && turnId != turn) return
        toolStartedKind = null
        if (_current.value != null) emit(null)
    }
}

/** Pendengar siklus hidup [AssistantBrain]. */
interface BrainListener {
    fun onLlmRequest(round: Int, hasImages: Boolean) {}
    fun onToolStart(name: String, argsJson: String) {}
    fun onToolEnd(name: String, output: String) {}
}

fun ActivityTracker.asBrainListener(): BrainListener = object : BrainListener {
    override fun onLlmRequest(round: Int, hasImages: Boolean) = llmRequest(round, hasImages)
    override fun onToolStart(name: String, argsJson: String) = toolStarted(name, argsJson)
    override fun onToolEnd(name: String, output: String) = toolFinished(name, output)
}
