package com.coactive.companion.tts

import java.io.File
import java.security.MessageDigest

/**
 * Cache audio TTS kecil berbasis kunci voice+teks, agar frasa yang sering diulang
 * (sapaan, "Oke!", dll.) tidak memanggil server lagi.
 * - Lapisan memori: LRU dibatasi total byte.
 * - Lapisan disk (opsional, [diskDir] di-set dari Application): file .mp3 di cacheDir, dipangkas berdasarkan umur akses.
 */
object TtsAudioCache {
    @Volatile var diskDir: File? = null
    var maxMemoryBytes = 4L * 1024 * 1024
    var maxDiskBytes = 16L * 1024 * 1024

    private val mem = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {}
    private var memBytes = 0L

    fun key(voice: String, text: String): String {
        val raw = "${voice.trim()}|${text.replace(Regex("\\s+"), " ").trim().lowercase()}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }

    fun get(voice: String, text: String): ByteArray? {
        val k = key(voice, text)
        synchronized(this) { mem[k]?.let { return it } }
        val f = diskDir?.let { File(it, "$k.mp3") }?.takeIf { it.isFile && it.length() > 0 } ?: return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        putMem(k, bytes)
        return bytes
    }

    fun put(voice: String, text: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val k = key(voice, text)
        putMem(k, bytes)
        val dir = diskDir ?: return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$k.tmp"); tmp.writeBytes(bytes); tmp.renameTo(File(dir, "$k.mp3"))
            trimDisk(dir)
        }
    }

    fun clear() {
        synchronized(this) { mem.clear(); memBytes = 0 }
        diskDir?.listFiles()?.forEach { it.delete() }
    }

    internal fun clearMemoryForTest() = synchronized(this) { mem.clear(); memBytes = 0 }

    private fun putMem(k: String, bytes: ByteArray) = synchronized(this) {
        mem.put(k, bytes)?.let { memBytes -= it.size }
        memBytes += bytes.size
        val it = mem.entries.iterator()
        while (memBytes > maxMemoryBytes && it.hasNext()) {
            val e = it.next(); if (e.key == k) continue
            memBytes -= e.value.size; it.remove()
        }
    }

    private fun trimDisk(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".mp3") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxDiskBytes) break; total -= f.length(); f.delete() }
    }
}
