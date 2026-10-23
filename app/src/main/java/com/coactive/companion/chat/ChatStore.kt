package com.coactive.companion.chat

import android.content.Context
import com.coactive.companion.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Jenis lampiran pesan chat. */
@Serializable
enum class AttachmentKind { IMAGE, TEXT }

/**
 * Lampiran tersimpan. [path] = file di penyimpanan internal aplikasi (gambar JPEG yang sudah diperkecil,
 * atau salinan teks), [name] = nama file asli, [chars] = panjang teks (lampiran TEXT).
 */
@Serializable
data class ChatAttachment(val kind: AttachmentKind, val path: String, val name: String, val chars: Int = 0, val truncated: Boolean = false)

/** Satu pesan chat yang disimpan permanen. role = "user" | "assistant". */
@Serializable
data class StoredChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val attachment: ChatAttachment? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** Pesan galat (ditampilkan beda, tidak dikirim balik ke LLM sebagai konteks). */
    val error: Boolean = false,
    /** Sumber web (hasil web_search) — ditampilkan sebagai link di bawah jawaban. */
    val sources: List<com.coactive.companion.search.WebSource> = emptyList(),
)

/**
 * Riwayat chat permanen: satu file JSON kecil di filesDir (tanpa Room/DataStore — cukup untuk ratusan pesan).
 * Ditulis atomik (file sementara → rename). Lampiran disimpan di filesDir/chat_files/.
 */
class ChatStore(context: Context, private val maxMessages: Int = 500) {
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, "chat_history.json")
    val filesDir: File = File(dir, "chat_files").apply { mkdirs() }
    private val serializer = ListSerializer(StoredChatMessage.serializer())
    private val lock = Mutex()
    private val _messages = MutableStateFlow<List<StoredChatMessage>>(emptyList())
    val messages: StateFlow<List<StoredChatMessage>> = _messages.asStateFlow()
    @Volatile private var loaded = false

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        _messages.value = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) Http.json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())
        }
        loaded = true
    }

    fun nextId(): Long = (_messages.value.maxOfOrNull { it.id } ?: 0L) + 1

    suspend fun add(m: StoredChatMessage) = lock.withLock {
        _messages.update { (it + m).takeLast(maxMessages) }
        persist()
    }

    suspend fun clear() = lock.withLock {
        _messages.value = emptyList()
        withContext(Dispatchers.IO) {
            file.delete()
            filesDir.listFiles()?.forEach { it.delete() }
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val tmp = File(dir, "chat_history.json.tmp")
        tmp.writeText(Http.json.encodeToString(serializer, _messages.value))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        // lampiran yatim (pesan lama terpangkas) dibersihkan
        val used = _messages.value.mapNotNull { it.attachment?.path }.toSet()
        filesDir.listFiles()?.filter { it.absolutePath !in used }?.forEach { it.delete() }
    }
}
