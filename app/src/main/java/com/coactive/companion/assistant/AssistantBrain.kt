package com.coactive.companion.assistant

import com.coactive.companion.llm.ChatLlm
import com.coactive.companion.llm.ChatMessage
import com.coactive.companion.tools.ToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Otak asisten: memanggil LLM, menjalankan tool yang diminta (loop sampai jawaban teks),
 * lalu menyimpan percakapan di memori sesi.
 */
class AssistantBrain(
    private val llm: ChatLlm,
    private val tools: ToolRegistry,
    private val memory: ConversationMemory,
    private val systemPrompt: () -> String,
    private val maxToolRounds: Int = 5,
    private val onToolCall: (String) -> Unit = {},
    /** Siklus hidup (mulai LLM / mulai & selesai tool) untuk indikator aktivitas. */
    private val listener: BrainListener? = null,
) {
    /**
     * @param images data URL gambar untuk pesan ini (format vision), hanya dikirim sekali (tidak masuk memori).
     * @param memoryText teks yang disimpan di memori sesi (mis. tanpa isi lampiran panjang); default = [userText].
     */
    suspend fun respond(userText: String, images: List<String> = emptyList(), memoryText: String = userText): ParsedReply {
        val convo = mutableListOf(ChatMessage.system(systemPrompt()))
        convo += memory.messages()
        convo += ChatMessage("user", userText, images = images)
        val specs = tools.specs()
        val sources = mutableListOf<com.coactive.companion.search.WebSource>()
        repeat(maxToolRounds) { round ->
            listener?.onLlmRequest(round, images.isNotEmpty())
            val msg = llm.complete(convo, specs)
            val calls = msg.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                val reply = EmotionParser.parse(msg.content.orEmpty().ifBlank { "Hmm, aku belum punya jawaban." })
                memory.add(memoryText, reply.text)
                return reply.copy(sources = sources.distinctBy { it.url }.take(5))
            }
            convo += ChatMessage(role = "assistant", content = msg.content, toolCalls = calls)
            for (c in calls) {
                onToolCall(c.function.name)
                listener?.onToolStart(c.function.name, c.function.arguments)
                val out = tools.execute(c.function.name, c.function.arguments)
                listener?.onToolEnd(c.function.name, out)
                sources += com.coactive.companion.search.SourceExtractor.fromToolOutput(c.function.name, out)
                convo += ChatMessage.tool(c.id, c.function.name, out)
            }
        }
        // terlalu banyak putaran tool: minta jawaban akhir tanpa tool
        listener?.onLlmRequest(maxToolRounds, false)
        val final = llm.complete(convo, emptyList())
        val reply = EmotionParser.parse(final.content.orEmpty())
        memory.add(memoryText, reply.text)
        return reply.copy(sources = sources.distinctBy { it.url }.take(5))
    }

    companion object {
        /** [chat] = mode chat teks: jawaban boleh lebih panjang & terstruktur ringan (tidak diucapkan TTS). */
        fun defaultSystemPrompt(name: String, language: String, city: String, locale: Locale, chat: Boolean = false): String {
            val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", locale).format(Date())
            val langName = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)
            val style = if (chat)
                "Ini mode chat teks (tidak diucapkan): jawab jelas dan lengkap sesuai kebutuhan; boleh beberapa paragraf atau daftar pendek dengan tanda \"-\", hindari tabel/markdown berat. Jika ada gambar atau isi file terlampir, gunakan itu untuk menjawab."
            else
                "Jawaban akan diucapkan lewat TTS: singkat (1-3 kalimat), natural, tanpa markdown, tanpa emoji, tanpa daftar panjang. JANGAN pernah menyebut atau membacakan URL/alamat web; cukup sebut nama situsnya (mis. \"menurut Detik\")."
            val sourceStyle = if (chat)
                "Setelah web_search: jawab ringkas dari hasilnya, sebut nama sumbernya singkat. Jangan menulis URL mentah atau daftar link — link sumber otomatis ditampilkan di bawah jawaban."
            else
                "Setelah web_search: rangkum jadi 1-3 kalimat yang enak didengar, sebut nama situs sumbernya saja (tanpa URL)."
            return """
                Kamu adalah $name, asisten suara kecil yang ramah di layar ponsel (seperti desktop companion).
                ALWAYS reply in $langName ($language) unless the user clearly asks for another language. Selalu jawab dalam bahasa $langName ($language), kecuali pengguna jelas meminta bahasa lain.
                $style
                Awali SETIAP jawaban dengan satu tag emosi: [neutral], [happy], [excited], [curious], [thinking], [surprised], [sad] atau [sleepy].
                Gunakan tool bila perlu (musik Spotify, timer, alarm, buka aplikasi, waktu, cuaca, pencarian web). Jangan mengarang hasil tool.
                Jika pengguna menyuruh mencari sesuatu (cari, cariin, carikan, search, googling, cek di internet) atau butuh info terkini (berita, harga, jadwal, skor, kurs), panggil web_search; pakai browse_web bila potongan hasil kurang. Jangan mengarang fakta terkini tanpa mencari.
                $sourceStyle
                Jika web_search mengembalikan error "search_unavailable", sampaikan dengan singkat bahwa kamu tidak bisa mencari sekarang (pakai kalimat say_to_user) dan jangan mengarang jawaban.
                Waktu sekarang: $now. Kota default pengguna: $city.
            """.trimIndent()
        }
    }
}
