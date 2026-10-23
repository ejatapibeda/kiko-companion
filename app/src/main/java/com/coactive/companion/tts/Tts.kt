package com.coactive.companion.tts

import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/** Mesin TTS yang dipakai asisten. [speak] selesai ketika audio selesai diputar. */
interface TtsEngine {
    /** @param onLevel dipanggil ~30x/detik dengan bukaan mulut 0..1 (sinkron dengan audio). */
    suspend fun speak(text: String, onLevel: (Float) -> Unit)
}

/** Audio hasil sintesis cloud. */
sealed interface TtsAudio {
    /** File audio terkompresi (mp3/wav/ogg) — didekode dengan MediaCodec. */
    class Encoded(val bytes: ByteArray, val extension: String = "mp3") : TtsAudio
    /** Stream PCM16 mono mentah (mis. ElevenLabs output_format=pcm_22050) — diputar sambil diunduh. */
    class PcmStream(val sampleRate: Int, val stream: InputStream) : TtsAudio
}

/** Penyedia TTS berbasis audio. Teks panjang bisa dipecah jadi beberapa potong audio. */
interface CloudTts {
    fun synthesize(text: String): Flow<TtsAudio>
}

/** Menggabungkan [CloudTts] dengan pemutar audio yang menghitung amplitudo untuk sinkron mulut. */
class AudioTtsEngine(private val cloud: CloudTts, private val player: AudioPlayback) : TtsEngine {
    override suspend fun speak(text: String, onLevel: (Float) -> Unit) {
        cloud.synthesize(text).collect { audio -> player.play(audio, onLevel) }
        onLevel(0f)
    }
}

/** Jika TTS utama gagal (jaringan, kuota, key salah), otomatis pakai Android TTS bawaan. */
class FallbackTtsEngine(
    private val primary: TtsEngine,
    private val fallback: TtsEngine,
    private val onFallback: (Throwable) -> Unit = {},
) : TtsEngine {
    override suspend fun speak(text: String, onLevel: (Float) -> Unit) {
        try {
            primary.speak(text, onLevel)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFallback(e)
            fallback.speak(text, onLevel)
        }
    }
}

object TextChunker {
    /** Memecah teks menjadi potongan <= [max] karakter, sebisa mungkin di batas kalimat/kata. */
    fun chunk(text: String, max: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = Regex("(?<=[.!?…])\\s+").split(clean)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
        for (s in sentences) {
            if (s.length > max) {
                flush()
                var rest = s
                while (rest.length > max) {
                    val cut = rest.lastIndexOf(' ', max).takeIf { it > max / 3 } ?: max
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) cur.append(rest)
            } else if (cur.length + s.length + 1 > max) {
                flush(); cur.append(s)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        flush()
        return out
    }

    /** Membersihkan markdown/emoji agar enak diucapkan. */
    fun forSpeech(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_#`>~]"), "")
        .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
        .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
