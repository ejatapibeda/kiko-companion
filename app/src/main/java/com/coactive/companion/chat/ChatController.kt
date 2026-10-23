package com.coactive.companion.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.coactive.companion.R
import com.coactive.companion.assistant.AssistantBrain
import com.coactive.companion.assistant.ConversationMemory
import com.coactive.companion.llm.OpenAiChatLlm
import com.coactive.companion.net.ApiException
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** Lampiran yang sudah disiapkan tapi belum dikirim (file sementara di cache). */
data class PendingAttachment(val kind: AttachmentKind, val file: File, val name: String, val chars: Int = 0, val truncated: Boolean = false)

/**
 * Mode chat teks: riwayat permanen ([ChatStore]), lampiran gambar (vision) & teks (.txt/.md), memakai otak,
 * persona, dan tool yang sama dengan mode suara (lihat [AssistantBrain]). Balasan dirender saat selesai
 * (klien LLM tidak streaming karena loop tool-calling) dengan indikator mengetik.
 *
 * [str] = ambil string resource sesuai bahasa aplikasi yang dipilih.
 */
class ChatController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val tools: () -> ToolRegistry,
    private val str: (Int, Array<out Any>) -> String,
) {
    val store = ChatStore(context)
    val messages: StateFlow<List<StoredChatMessage>> get() = store.messages
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()
    private val _pending = MutableStateFlow<PendingAttachment?>(null)
    val pending: StateFlow<PendingAttachment?> = _pending.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** Pesan singkat (mis. gagal membaca file) untuk snackbar. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var job: Job? = null
    private val pendingDir = File(context.cacheDir, "chat_pending").apply { mkdirs() }

    init { scope.launch { store.load() } }

    private fun s(id: Int, vararg args: Any) = str(id, args)

    fun clearNotice() { _notice.value = null }
    fun removePending() { _pending.value?.file?.delete(); _pending.value = null }

    /** Gambar → diperkecil (sisi terpanjang ≤ [MAX_IMAGE_PX]) & JPEG 80% supaya permintaan tetap ringan. */
    fun attachImage(uri: Uri) = scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val bmp = decodeScaled(uri, MAX_IMAGE_PX)
                val f = File(pendingDir, "img_${UUID.randomUUID()}.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                bmp.recycle()
                PendingAttachment(AttachmentKind.IMAGE, f, displayName(uri) ?: "image.jpg")
            }
        }.onSuccess { removePending(); _pending.value = it }
            .onFailure { Log.w(TAG, "attach image", it); _notice.value = s(R.string.chat_image_failed) }
    }

    /** File teks (.txt/.md) → dibaca maksimal [MAX_TEXT_CHARS] karakter (sisanya dipotong dengan catatan). */
    fun attachText(uri: Uri) = scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val buf = CharArray(MAX_TEXT_CHARS + 1)
                var n = 0
                context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { r ->
                    while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                }
                val truncated = n > MAX_TEXT_CHARS
                val text = String(buf, 0, minOf(n, MAX_TEXT_CHARS))
                require(text.count { it == '\u0000' } < 4) { "binary" }
                val f = File(pendingDir, "txt_${UUID.randomUUID()}.txt").apply { writeText(text) }
                PendingAttachment(AttachmentKind.TEXT, f, displayName(uri) ?: "file.txt", text.length, truncated)
            }
        }.onSuccess { removePending(); _pending.value = it }
            .onFailure { Log.w(TAG, "attach text", it); _notice.value = s(R.string.chat_not_text_file) }
    }

    fun send(textIn: String) {
        val text = textIn.trim()
        val att = _pending.value
        if ((text.isEmpty() && att == null) || _sending.value) return
        _pending.value = null
        job = scope.launch {
            _sending.value = true
            try {
                // lampiran dipindah dari cache ke penyimpanan permanen riwayat
                val stored = att?.let {
                    val dest = File(store.filesDir, it.file.name)
                    withContext(Dispatchers.IO) { if (!it.file.renameTo(dest)) { it.file.copyTo(dest, true); it.file.delete() } }
                    ChatAttachment(it.kind, dest.absolutePath, it.name, it.chars, it.truncated)
                }
                val history = store.messages.value
                store.add(StoredChatMessage(store.nextId(), "user", text, stored))
                val reply = ask(text, stored, history)
                _sending.value = false
                store.add(StoredChatMessage(store.nextId(), "assistant", reply.text.ifBlank { "…" }, sources = reply.sources))
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                Log.w(TAG, "chat failed", e)
                store.add(StoredChatMessage(store.nextId(), "assistant", errorText(e, att?.kind == AttachmentKind.IMAGE), error = true))
            } finally { _sending.value = false }
        }
    }

    fun cancel() { job?.cancel(); _sending.value = false }

    fun clearHistory() = scope.launch { cancel(); removePending(); store.clear() }

    private suspend fun ask(text: String, att: ChatAttachment?, history: List<StoredChatMessage>): com.coactive.companion.assistant.ParsedReply {
        val a = settings()
        val memory = ConversationMemory(maxTurns = 12)
        // riwayat → pasangan (user, assistant); lampiran lama hanya disebut namanya agar permintaan tetap kecil
        var lastUser: String? = null
        for (m in history) {
            if (m.error) { lastUser = null; continue }
            if (m.role == "user") lastUser = m.text + (m.attachment?.let { "\n[${if (it.kind == AttachmentKind.IMAGE) "image" else "file: ${it.name}"}]" } ?: "")
            else if (lastUser != null) { memory.add(lastUser, m.text); lastUser = null }
        }
        val images = mutableListOf<String>()
        val prompt = StringBuilder(text.ifEmpty { if (att?.kind == AttachmentKind.IMAGE) s(R.string.chat_default_image_prompt) else s(R.string.chat_default_file_prompt) })
        var memoryText = prompt.toString()
        when (att?.kind) {
            AttachmentKind.IMAGE -> {
                val bytes = withContext(Dispatchers.IO) { File(att.path).readBytes() }
                images += "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                memoryText += "\n[image]"
            }
            AttachmentKind.TEXT -> {
                val content = withContext(Dispatchers.IO) { File(att.path).readText() }
                prompt.append("\n\n[Attached file: ${att.name}]\n```\n").append(content).append("\n```")
                if (att.truncated) prompt.append("\n(Note: the file was truncated to the first $MAX_TEXT_CHARS characters.)")
                memoryText += "\n[file: ${att.name}]"
            }
            null -> Unit
        }
        val model = if (images.isNotEmpty() && a.visionModel.isNotBlank()) a.visionModel else a.llmModel
        val brain = AssistantBrain(
            OpenAiChatLlm(a.llmBaseUrl, a.llmApiKey, model), tools(), memory,
            { AssistantBrain.defaultSystemPrompt(a.assistantName, a.language, a.defaultCity, Locale.forLanguageTag(a.language), chat = true) },
            onToolCall = { Log.i(TAG, "chat tool call: $it") },
        )
        return brain.respond(prompt.toString(), images, memoryText)
    }

    private fun errorText(e: Throwable, hadImage: Boolean): String = when {
        e.message?.contains("API key") == true -> s(R.string.chat_error_no_key)
        e is ApiException && e.code == 401 -> s(R.string.chat_error_no_key)
        hadImage && e is ApiException && e.code in 400..422 -> s(R.string.chat_error_vision)
        e is java.net.UnknownHostException -> s(R.string.chat_error_offline)
        else -> s(R.string.chat_error_generic, e.message?.take(160) ?: e.javaClass.simpleName)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun decodeScaled(uri: Uri, maxPx: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
                dec.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode")
        val scale = minOf(1f, maxPx.toFloat() / maxOf(raw.width, raw.height))
        if (scale >= 1f) return raw
        return Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true).also { if (it !== raw) raw.recycle() }
    }

    companion object {
        const val TAG = "CompanionChat"
        const val MAX_IMAGE_PX = 1024
        const val MAX_TEXT_CHARS = 100_000
    }
}
