package com.coactive.companion.wakeword

/** Tiga tahap model openWakeWord. Dipisah agar logika buffer bisa diuji tanpa ONNX. */
interface WakeModels : AutoCloseable {
    /** Jumlah embedding yang dibutuhkan model wake word (biasanya 16). */
    val featureWindow: Int
    /** Audio (nilai int16 sebagai float) -> frame mel [n][32] (sudah ditransform x/10+2). */
    fun melspectrogram(samples: FloatArray): Array<FloatArray>
    /** 76 frame mel -> embedding 96 dimensi. */
    fun embed(mel: Array<FloatArray>): FloatArray
    /** [featureWindow][96] -> skor 0..1. */
    fun score(features: Array<FloatArray>): Float
}

/**
 * Pipeline streaming openWakeWord (port dari openwakeword/utils.py AudioFeatures + Model.predict):
 * setiap 1280 sampel (80 ms @16 kHz) -> mel dihitung atas 1280+480 sampel terakhir (8 frame),
 * embedding dari 76 frame mel terakhir, skor dari 16 embedding terakhir.
 */
class WakeWordDetector(
    private val models: WakeModels,
    var threshold: Float = 0.5f,
    private val cooldownChunks: Int = 25, // ~2 detik
) {
    private val raw = FloatArray(CHUNK + MEL_CONTEXT)
    private var rawFilled = 0
    private val mel = ArrayDeque<FloatArray>().apply { repeat(EMB_WINDOW) { addLast(FloatArray(32) { 1f }) } }
    private val features = ArrayDeque<FloatArray>()
    private var cooldown = 0
    var lastScore = 0f; private set

    /** Proses satu chunk 1280 sampel. Mengembalikan true jika wake word terdeteksi. */
    fun process(chunk: ShortArray): Boolean {
        require(chunk.size == CHUNK) { "Chunk harus $CHUNK sampel" }
        System.arraycopy(raw, CHUNK, raw, 0, raw.size - CHUNK)
        for (i in 0 until CHUNK) raw[raw.size - CHUNK + i] = chunk[i].toFloat()
        rawFilled = minOf(raw.size, rawFilled + CHUNK)
        if (rawFilled < raw.size) return false

        models.melspectrogram(raw).forEach { mel.addLast(it) }
        while (mel.size > MEL_MAX) mel.removeFirst()

        val window = Array(EMB_WINDOW) { mel[mel.size - EMB_WINDOW + it] }
        features.addLast(models.embed(window))
        while (features.size > models.featureWindow) features.removeFirst()
        if (cooldown > 0) { cooldown--; lastScore = 0f; return false }
        if (features.size < models.featureWindow) return false

        lastScore = models.score(features.toTypedArray())
        if (lastScore >= threshold) {
            cooldown = cooldownChunks
            features.clear()
            return true
        }
        return false
    }

    fun reset() {
        rawFilled = 0; features.clear(); cooldown = 0; lastScore = 0f
    }

    companion object {
        const val CHUNK = 1280
        const val MEL_CONTEXT = 480
        const val EMB_WINDOW = 76
        const val MEL_MAX = 120
    }
}
