package com.coactive.companion.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/** Implementasi [WakeModels] dengan ONNX Runtime Android. */
class OnnxWakeModels(melBytes: ByteArray, embBytes: ByteArray, wakeBytes: ByteArray) : WakeModels {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melSession = env.createSession(melBytes, opts)
    private val embSession = env.createSession(embBytes, opts)
    private val wakeSession = env.createSession(wakeBytes, opts)
    private val melIn = melSession.inputNames.first()
    private val embIn = embSession.inputNames.first()
    private val wakeIn = wakeSession.inputNames.first()

    override val featureWindow: Int =
        ((wakeSession.inputInfo[wakeIn]?.info as? TensorInfo)?.shape?.getOrNull(1)?.toInt())?.takeIf { it > 0 } ?: 16

    override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { t ->
            melSession.run(mapOf(melIn to t)).use { r ->
                val out = r[0] as OnnxTensor
                val shape = out.info.shape // [1,1,frames,32]
                val frames = shape[shape.size - 2].toInt()
                val bins = shape.last().toInt()
                val buf = out.floatBuffer
                return Array(frames) { f -> FloatArray(bins) { b -> buf.get(f * bins + b) / 10f + 2f } }
            }
        }
    }

    override fun embed(mel: Array<FloatArray>): FloatArray {
        val flat = FloatArray(mel.size * 32)
        mel.forEachIndexed { i, row -> System.arraycopy(row, 0, flat, i * 32, 32) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, mel.size.toLong(), 32, 1)).use { t ->
            embSession.run(mapOf(embIn to t)).use { r ->
                val buf = (r[0] as OnnxTensor).floatBuffer
                return FloatArray(buf.remaining()) { buf.get(it) }
            }
        }
    }

    override fun score(features: Array<FloatArray>): Float {
        val dim = features.first().size
        val flat = FloatArray(features.size * dim)
        features.forEachIndexed { i, row -> System.arraycopy(row, 0, flat, i * dim, dim) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, features.size.toLong(), dim.toLong())).use { t ->
            wakeSession.run(mapOf(wakeIn to t)).use { r ->
                return (r[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    override fun close() {
        melSession.close(); embSession.close(); wakeSession.close(); opts.close()
    }

    companion object {
        /**
         * Memuat model. [wakeModel] = nama file di assets/wakeword/ ATAU path absolut ke file .onnx
         * (mis. model custom yang disalin ke /sdcard/Android/data/com.coactive.companion/files/).
         */
        fun load(context: Context, wakeModel: String): OnnxWakeModels {
            fun asset(name: String) = context.assets.open("wakeword/$name").use { it.readBytes() }
            val wakeBytes = File(wakeModel).takeIf { it.isAbsolute && it.exists() }?.readBytes()
                ?: File(context.getExternalFilesDir(null), wakeModel).takeIf { it.exists() }?.readBytes()
                ?: asset(wakeModel)
            return OnnxWakeModels(asset("melspectrogram.onnx"), asset("embedding_model.onnx"), wakeBytes)
        }
    }
}
