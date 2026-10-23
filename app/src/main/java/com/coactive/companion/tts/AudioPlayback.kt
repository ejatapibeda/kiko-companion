package com.coactive.companion.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.coactive.companion.audio.Level
import com.coactive.companion.audio.Smoother
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Memutar audio TTS lewat AudioTrack dan melaporkan level mulut yang sinkron dengan posisi playback
 * (bukan saat buffer ditulis), sehingga gerak mulut pas dengan suara.
 */
class AudioPlayback(private val cacheDir: File, private val volume: () -> Float = { 1f }) {

    suspend fun play(audio: TtsAudio, onLevel: (Float) -> Unit) = withContext(Dispatchers.IO) {
        when (audio) {
            is TtsAudio.PcmStream -> playPcmStream(audio, onLevel)
            is TtsAudio.Encoded -> playEncoded(audio, onLevel)
        }
    }

    private suspend fun playPcmStream(audio: TtsAudio.PcmStream, onLevel: (Float) -> Unit) = coroutineScope {
        val sink = PcmSink(audio.sampleRate, 1, volume())
        val poll = launch { sink.pollLevels(onLevel) }
        try {
            audio.stream.use { input ->
                val buf = ByteArray(4096)
                var carry = -1 // byte ganjil yang tersisa
                while (true) {
                    ensureActive()
                    var n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
                    if (n < 0) break
                    if (carry >= 0) { buf[0] = carry.toByte(); n += 1; carry = -1 }
                    if (n % 2 == 1) { carry = buf[n - 1].toInt() and 0xff; n -= 1 }
                    sink.write(buf, n)
                }
            }
            sink.drain()
        } finally {
            poll.cancel(); sink.release(); onLevel(0f)
        }
    }

    private suspend fun playEncoded(audio: TtsAudio.Encoded, onLevel: (Float) -> Unit) = coroutineScope {
        val file = File.createTempFile("tts", ".${audio.extension}", cacheDir)
        file.writeBytes(audio.bytes)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var sink: PcmSink? = null
        val poller = launch {
            while (sink == null) delay(10)
            sink!!.pollLevels(onLevel)
        }
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcm = ByteArray(0)
            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sink?.release()
                        sink = PcmSink(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT), volume())
                    }
                    outIdx >= 0 -> {
                        if (sink == null) {
                            sink = PcmSink(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), volume())
                        }
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            if (pcm.size < info.size) pcm = ByteArray(info.size)
                            ob.position(info.offset); ob.get(pcm, 0, info.size)
                            sink!!.write(pcm, info.size)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            sink?.drain()
        } finally {
            poller.cancel()
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            extractor.release(); sink?.release(); file.delete(); onLevel(0f)
        }
    }
}

/** AudioTrack streaming + tabel level per 20 ms untuk sinkron mulut. */
internal class PcmSink(private val sampleRate: Int, private val channels: Int, private val volume: Float = 1f) {
    private val windowFrames = sampleRate / 50
    private val levels = ArrayList<Float>()
    private var framesWritten = 0L
    private var windowAcc = 0.0
    private var windowCount = 0
    private val track: AudioTrack = AudioTrack.Builder()
        // USAGE_MEDIA = STREAM_MUSIC (volume media), lebih keras dari stream asisten/notifikasi
        .setAudioAttributes(TtsAudioAttributes.media)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
            .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate,
            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), sampleRate))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.setVolume(volume.coerceIn(0f, 1f)); it.play() } // gain track penuh (tidak mengubah volume sistem)

    fun write(bytes: ByteArray, len: Int) {
        // hitung level per jendela 20 ms (pakai kanal pertama)
        val frameBytes = 2 * channels
        var i = 0
        while (i + 1 < len) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort() / 32768.0
            windowAcc += s * s; windowCount++
            if (windowCount >= windowFrames) {
                synchronized(levels) { levels += Math.sqrt(windowAcc / windowCount).toFloat() }
                windowAcc = 0.0; windowCount = 0
            }
            i += frameBytes
        }
        var off = 0
        while (off < len) {
            val w = track.write(bytes, off, len - off)
            if (w <= 0) break
            off += w
        }
        framesWritten += len / frameBytes
    }

    suspend fun drain() {
        val target = framesWritten
        var stalls = 0
        var last = -1L
        while (true) {
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (head >= target - 1) break
            if (head == last) { if (++stalls > 100) break } else stalls = 0 // ~3 dtk macet -> berhenti
            last = head
            delay(30)
        }
    }

    suspend fun pollLevels(onLevel: (Float) -> Unit) = coroutineScope {
        val smoother = Smoother()
        while (isActive) {
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            val idx = (head / windowFrames).toInt()
            val rms = synchronized(levels) { levels.getOrNull(idx) ?: 0f }
            onLevel(smoother.next(Level.mouthOpenness(rms)))
            delay(33)
        }
    }

    fun release() {
        runCatching { track.pause(); track.flush() }
        track.release()
    }
}
