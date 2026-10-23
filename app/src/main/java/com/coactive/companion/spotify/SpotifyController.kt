package com.coactive.companion.spotify

import com.coactive.companion.settings.AppSettings
import com.coactive.companion.settings.SpotifySource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Kategori kesalahan yang seragam untuk semua sumber Spotify (Lokal / Web API resmi / spotapi). */
enum class SpotifyError(val defaultMessage: String) {
    NOT_CONFIGURED("Sumber Spotify belum diatur. Buka ⚙ Pengaturan → Spotify."),
    NOT_CONNECTED("Akun Spotify belum terhubung. Buka ⚙ Pengaturan → Spotify untuk menghubungkan."),
    UNAUTHORIZED("Akses ke Spotify ditolak. Hubungkan ulang di Pengaturan."),
    SESSION_INVALID("Sesi Spotify kedaluwarsa. Hubungkan ulang akun Spotify di Pengaturan."),
    SESSION_MISSING("Akun Spotify belum dihubungkan ke server. Tekan 'Hubungkan akun Spotify' di Pengaturan."),
    NO_RESULTS("Lagu tidak ditemukan di Spotify."),
    NO_ACTIVE_DEVICE("Tidak ada perangkat Spotify yang aktif. Buka aplikasi Spotify dulu, lalu coba lagi."),
    PREMIUM_REQUIRED("Fitur ini butuh Spotify Premium."),
    RATE_LIMITED("Terlalu banyak permintaan ke Spotify. Coba lagi sebentar lagi."),
    COMMAND_FAILED("Perintah ke Spotify gagal. Coba lagi."),
    UPSTREAM("Spotify sedang bermasalah. Coba lagi sebentar lagi."),
    BAD_REQUEST("Permintaan ke Spotify tidak valid."),
    NETWORK("Tidak bisa menghubungi server Spotify. Periksa koneksi internet."),
    NOT_SUPPORTED("Fitur ini tidak tersedia di mode Spotify yang dipilih."),
    UNKNOWN("Terjadi kesalahan pada Spotify."),
}

/** Hasil operasi Spotify. */
sealed interface SpotifyResult<out T> {
    data class Ok<T>(val value: T) : SpotifyResult<T>
    data class Failure(
        val error: SpotifyError,
        val message: String = error.defaultMessage,
        val retryable: Boolean = false,
        /** Detik tunggu dari header Retry-After (429), bila ada. */
        val retryAfterSec: Long? = null,
    ) : SpotifyResult<Nothing>
}

data class SpotifyTrack(val title: String, val artists: List<String>, val uri: String? = null, val album: String? = null,
                        val durationMs: Long? = null, val imageUrl: String? = null) {
    val artistText get() = artists.joinToString(", ")
}

/** Hasil aksi (putar/jeda/…): [track] diisi untuk putar-cari; [via] = jalur yang dipakai. */
data class SpotifyAction(val via: String, val track: SpotifyTrack? = null, val note: String? = null)

data class NowPlaying(
    val isPlaying: Boolean,
    val isPaused: Boolean? = null,
    val track: SpotifyTrack?,
    val positionMs: Long? = null,
    val activeDeviceId: String? = null,
    val deviceName: String? = null,
)

/** Satu antarmuka untuk semua sumber kontrol Spotify. Tool LLM hanya memanggil ini. */
interface SpotifyController {
    val source: SpotifySource
    suspend fun play(): SpotifyResult<SpotifyAction> = resume()
    suspend fun pause(): SpotifyResult<SpotifyAction>
    suspend fun resume(): SpotifyResult<SpotifyAction>
    suspend fun next(): SpotifyResult<SpotifyAction>
    suspend fun previous(): SpotifyResult<SpotifyAction>
    /** Cari lalu putar. [type]: track | artist | album | playlist (sumber yang tak mendukung memperlakukannya sebagai track). */
    suspend fun playSearch(query: String, type: String = "track"): SpotifyResult<SpotifyAction>
    /** Status pemutaran; Ok(null) = tidak ada yang diputar / tidak ada perangkat aktif. */
    suspend fun nowPlaying(): SpotifyResult<NowPlaying?>
    /** Lirik lagu [uri]. Hanya backend spotapi yang mendukung; sumber lain → NO_RESULTS ("Lirik tidak tersedia"). */
    suspend fun lyrics(uri: String): SpotifyResult<LyricsResponse> = SpotifyResult.Failure(SpotifyError.NO_RESULTS, "Lirik tidak tersedia.")
}

/** JSON ringkas untuk dikirim balik ke LLM. */
fun SpotifyResult<*>.toToolJson(): String = when (this) {
    is SpotifyResult.Failure -> buildJsonObject {
        put("ok", false); put("error", error.name); put("message", message)
        retryAfterSec?.let { put("retry_after_sec", it) }
    }.toString()
    is SpotifyResult.Ok -> when (val v = value) {
        is SpotifyAction -> buildJsonObject {
            put("ok", true); put("via", v.via)
            v.track?.let { put("playing", it.title); putJsonArray("artists") { it.artists.forEach { a -> add(JsonPrimitive(a)) } } }
            v.note?.let { put("note", it) }
        }.toString()
        is NowPlaying -> buildJsonObject {
            put("ok", true); put("is_playing", v.isPlaying)
            v.track?.let { put("title", it.title); put("artists", it.artistText); it.album?.let { a -> put("album", a) } }
            v.deviceName?.let { put("device", it) }
        }.toString()
        null -> """{"ok":true,"is_playing":false,"note":"tidak ada yang diputar / tidak ada perangkat aktif"}"""
        else -> """{"ok":true}"""
    }
}

/** Arah aksi pemutaran yang berhasil (dipakai mode joget di Lokal: tak ada data now-playing). */
enum class PlaybackIntent { PLAY, PAUSE }

/**
 * Dekorator: memberi tahu UI saat aksi berhasil (→ satu kali refresh now-playing) dan saat
 * NO_ACTIVE_DEVICE (→ tombol "Buka Spotify" lalu "Coba lagi" dengan [retry]).
 */
class ObservedSpotifyController(
    private val inner: SpotifyController,
    private val onActionDone: (PlaybackIntent) -> Unit,
    private val onNoActiveDevice: (retry: suspend () -> SpotifyResult<SpotifyAction>) -> Unit,
) : SpotifyController by inner {
    private suspend fun observe(intent: PlaybackIntent = PlaybackIntent.PLAY, block: suspend () -> SpotifyResult<SpotifyAction>): SpotifyResult<SpotifyAction> {
        val r = block()
        when {
            r is SpotifyResult.Ok -> onActionDone(intent)
            r is SpotifyResult.Failure && r.error == SpotifyError.NO_ACTIVE_DEVICE -> onNoActiveDevice { observe(intent, block) }
        }
        return r
    }
    override suspend fun play() = observe { inner.play() }
    override suspend fun pause() = observe(PlaybackIntent.PAUSE) { inner.pause() }
    override suspend fun resume() = observe { inner.resume() }
    override suspend fun next() = observe { inner.next() }
    override suspend fun previous() = observe { inner.previous() }
    override suspend fun playSearch(query: String, type: String) = observe { inner.playSearch(query, type) }
}

/**
 * Dipakai bila sumber utama menolak karena autentikasi/sesi (aksi PASTI belum dijalankan),
 * mencoba sumber cadangan (Web API resmi direkomendasikan sebagai cadangan).
 */
class FallbackSpotifyController(private val primary: SpotifyController, private val fallback: SpotifyController) : SpotifyController {
    override val source get() = primary.source
    private suspend fun <T> go(block: suspend SpotifyController.() -> SpotifyResult<T>): SpotifyResult<T> {
        val r = primary.block()
        return if (r is SpotifyResult.Failure && r.error in FALLBACK_ON) fallback.block() else r
    }
    override suspend fun pause() = go { pause() }
    override suspend fun resume() = go { resume() }
    override suspend fun next() = go { next() }
    override suspend fun previous() = go { previous() }
    override suspend fun playSearch(query: String, type: String) = go { playSearch(query, type) }
    override suspend fun nowPlaying() = go { nowPlaying() }
    // lirik hanya dari sumber utama (cadangan resmi/lokal tak punya lirik)
    override suspend fun lyrics(uri: String) = primary.lyrics(uri)

    companion object {
        val FALLBACK_ON = setOf(SpotifyError.NOT_CONFIGURED, SpotifyError.NOT_CONNECTED, SpotifyError.UNAUTHORIZED,
            SpotifyError.SESSION_INVALID, SpotifyError.SESSION_MISSING)
    }
}

/** Memilih implementasi dari [AppSettings.spotifySource]. Implementasi dibuat lazily (lambda). */
object SpotifyControllerFactory {
    fun create(
        settings: AppSettings,
        officialConnected: Boolean,
        spotapiReady: Boolean,
        local: () -> SpotifyController,
        official: () -> SpotifyController,
        spotapi: () -> SpotifyController,
    ): SpotifyController = when (settings.spotifySource) {
        SpotifySource.LOCAL -> local()
        // resmi belum login → tetap bisa pakai jalur lokal
        SpotifySource.OFFICIAL -> if (officialConnected && settings.spotifyClientId.isNotBlank()) official() else local()
        SpotifySource.SPOTAPI -> {
            val officialOk = officialConnected && settings.spotifyClientId.isNotBlank()
            when {
                // cadangan hanya Web API resmi (jika login); tanpa itu, error spotapi (mis. sesi kedaluwarsa) ditampilkan apa adanya
                spotapiReady && officialOk -> FallbackSpotifyController(spotapi(), official())
                spotapiReady -> spotapi()
                officialOk -> official()
                else -> local()
            }
        }
    }
}
