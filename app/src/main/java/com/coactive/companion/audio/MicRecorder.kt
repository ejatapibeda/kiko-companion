package com.coactive.companion.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Mikrofon 16 kHz mono PCM16, mengeluarkan frame 1280 sampel (80 ms) — ukuran yang dipakai openWakeWord. */
object MicRecorder {
    const val SAMPLE_RATE = 16000
    const val FRAME = 1280
    private const val TAG = "Companion"

    /**
     * Jika sumber VOICE_COMMUNICATION menghasilkan hening total (beberapa ROM/HAL lama tidak merutekannya),
     * kita jatuh ke VOICE_RECOGNITION dan mengingatnya selama proses berjalan.
     */
    @Volatile private var voiceCommBroken = false

    /**
     * @param enhance true → pra-pemrosesan suara: sumber VOICE_COMMUNICATION (pipeline AEC/NS/AGC platform)
     *   + efek audiofx NoiseSuppressor, AcousticEchoCanceler (cegah suara TTS sendiri ikut terekam),
     *   AutomaticGainControl bila tersedia. false → perilaku lama (VOICE_RECOGNITION mentah).
     */
    fun frames(enhance: Boolean = true): Flow<ShortArray> = flow {
        var source = MicSourcePolicy.pick(enhance, voiceCommBroken)
        while (coroutineContext.isActive) {
            val fallback = record(source, enhance) { emit(it) }
            if (!fallback) break
            voiceCommBroken = true
            Log.w(TAG, "VOICE_COMMUNICATION hening/tidak didukung → fallback ke VOICE_RECOGNITION")
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }.flowOn(Dispatchers.IO)

    /** @return true bila harus diulang dengan sumber fallback. */
    @SuppressLint("MissingPermission")
    private suspend fun record(source: Int, enhance: Boolean, emit: suspend (ShortArray) -> Unit): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val canFallback = source != MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = runCatching {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            if (canFallback) return true
            error("AudioRecord gagal diinisialisasi")
        }
        val effects = if (enhance) attachEffects(rec.audioSessionId) else emptyList()
        val silence = SilenceWatch()
        try {
            rec.startRecording()
            if (com.coactive.companion.BuildConfig.DEBUG) Log.i(TAG, "MICDIAG start src=$source state=${rec.recordingState} rate=${rec.sampleRate} " +
                "routed=${rec.routedDevice?.let { "${it.type}/${it.productName}" }} minBuf=$minBuf fx=${effects.map { it.descriptor.name }}")
            while (coroutineContext.isActive) {
                val frame = ShortArray(FRAME)
                var off = 0
                while (off < FRAME) {
                    val n = rec.read(frame, off, FRAME - off)
                    if (n < 0) error("AudioRecord read error $n")
                    off += n
                }
                if (canFallback && silence.feed(frame)) return true
                emit(frame)
            }
            return false
        } finally {
            effects.forEach { e -> runCatching { e.enabled = false }; runCatching { e.release() } }
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Pasang efek yang tersedia; setiap efek dijaga isAvailable()+try/catch agar perangkat tanpa efek tidak crash. */
    private fun attachEffects(session: Int): List<AudioEffect> {
        val out = ArrayList<AudioEffect>(3)
        fun <T : AudioEffect> tryAdd(name: String, available: () -> Boolean, create: () -> T?) {
            runCatching {
                if (!available()) return
                val fx = create() ?: return
                fx.enabled = true
                out += fx
            }.onFailure { Log.w(TAG, "efek $name tidak bisa dipasang: ${it.message}") }
        }
        tryAdd("NoiseSuppressor", NoiseSuppressor::isAvailable) { NoiseSuppressor.create(session) }
        tryAdd("AcousticEchoCanceler", AcousticEchoCanceler::isAvailable) { AcousticEchoCanceler.create(session) }
        tryAdd("AutomaticGainControl", AutomaticGainControl::isAvailable) { AutomaticGainControl.create(session) }
        return out
    }
}

/** Logika murni (bisa diuji di JVM) untuk memilih sumber audio & mendeteksi rekaman hening total. */
object MicSourcePolicy {
    fun pick(enhance: Boolean, voiceCommBroken: Boolean): Int =
        if (enhance && !voiceCommBroken) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
}

/** true setelah [limit] frame pertama SEMUA bernilai nol persis (mikrofon tak terhubung di HAL), bukan sekadar sunyi. */
class SilenceWatch(private val limit: Int = 25) {
    private var zeros = 0
    private var decided = false
    fun feed(frame: ShortArray): Boolean {
        if (decided) return false
        if (frame.any { it.toInt() != 0 }) { decided = true; return false }
        zeros++
        if (zeros >= limit) { decided = true; return true }
        return false
    }
}
