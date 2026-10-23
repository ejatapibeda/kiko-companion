package com.coactive.companion.spotify

/*
 * ============================================================================
 *  spotapi backend — SEMUA detail kontrak HTTP ada di file ini.
 *  Sesuai OpenAPI 3.0.3 "SpotAPI Wrapper Backend" v1.0.0 (spotapi-backend/openapi.yaml).
 *  Base: https://<host>/api/v1  ·  JSON UTF-8  ·  Authorization: Bearer <app_token>
 *  Jika kontrak berubah, cukup ubah file ini (DTO, path, pemetaan error).
 * ============================================================================
 */

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.settings.SpotifySource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---------- Skema (components/schemas) ----------

@Serializable data class SpotapiHealth(val ok: Boolean, val version: String)

@Serializable data class PairRequest(@SerialName("pairing_code") val pairingCode: String, @SerialName("device_name") val deviceName: String)
@Serializable data class PairResponse(@SerialName("app_token") val appToken: String, @SerialName("expires_at") val expiresAt: String? = null)

@Serializable data class SessionCookies(
    @SerialName("sp_dc") val spDc: String,
    @SerialName("sp_key") val spKey: String? = null,
    @SerialName("sp_t") val spT: String? = null,
)
@Serializable data class SessionCreateRequest(val cookies: SessionCookies)
@Serializable data class SessionStatus(
    val connected: Boolean,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val product: String? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
)

@Serializable data class PlayRequest(
    val query: String? = null,
    val uri: String? = null,
    val index: Int = 0,
    @SerialName("device_id") val deviceId: String? = null,
)
@Serializable data class SpotapiTrack(
    val uri: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)
@Serializable data class PlayResponse(val ok: Boolean, val track: SpotapiTrack, @SerialName("device_id") val deviceId: String)
@Serializable data class DeviceCommandRequest(@SerialName("device_id") val deviceId: String? = null)
@Serializable data class OkResponse(val ok: Boolean)
@Serializable data class SpotapiNowPlaying(
    @SerialName("is_playing") val isPlaying: Boolean,
    @SerialName("is_paused") val isPaused: Boolean? = null,
    val track: SpotapiTrack? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("active_device_id") val activeDeviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
)
@Serializable data class SpotapiDevice(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("can_play") val canPlay: Boolean,
    @SerialName("volume_percent") val volumePercent: Int? = null,
)
@Serializable data class DevicesResponse(@SerialName("active_device_id") val activeDeviceId: String? = null, val devices: List<SpotapiDevice>)

/** GET /lyrics — lirik (tersinkron bila [synced]); baris diurutkan menurut start_ms, bisa berisi "" untuk jeda. */
@Serializable data class LyricsLine(@SerialName("start_ms") val startMs: Long = 0, val text: String = "")
@Serializable data class LyricsResponse(
    @SerialName("track_id") val trackId: String = "",
    val synced: Boolean = false,
    val source: String = "",
    val lines: List<LyricsLine> = emptyList(),
)

/** components/schemas/ErrorCode — persis sesuai spesifikasi. */
@Serializable enum class ErrorCode {
    BAD_REQUEST, UNAUTHORIZED, INVALID_COOKIES, SPOTIFY_SESSION_INVALID, SPOTIFY_SESSION_MISSING,
    NO_RESULTS, NO_ACTIVE_DEVICE, RATE_LIMITED, COMMAND_FAILED, SPOTIFY_UPSTREAM, TOKEN_UNAVAILABLE,
}
@Serializable data class ErrorBody(val code: String, val message: String = "", val retryable: Boolean = false)
@Serializable data class ErrorEnvelope(val ok: Boolean = false, val error: ErrorBody)

/** Error dari backend. [code] null = kode tidak dikenal / body bukan envelope. [retryAfterSec] dari header Retry-After (429). */
class SpotapiException(
    val httpStatus: Int,
    val code: ErrorCode?,
    val rawCode: String?,
    val serverMessage: String,
    val retryable: Boolean,
    val retryAfterSec: Long?,
) : IOException("spotapi HTTP $httpStatus ${rawCode ?: ""}: $serverMessage")

class SpotapiNotConfigured : IllegalStateException("Base URL spotapi kosong")

// ---------- Klien HTTP ----------

/**
 * Klien tipis untuk backend spotapi. Tidak ada retry otomatis (aksi /play, /next, /prev adalah aksi nyata);
 * penjadwalan ulang GET (now-playing) diatur oleh [NowPlayingPoller].
 */
class SpotapiClient(
    private val baseUrl: () -> String,
    private val appToken: () -> String,
    client: OkHttpClient = Http.client,
) {
    private val http = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }

    suspend fun health(): SpotapiHealth = get("health", auth = false)
    suspend fun pair(pairingCode: String, deviceName: String): PairResponse =
        post("auth/pair", json.encodeToString(PairRequest(pairingCode.trim(), deviceName)), auth = false)

    suspend fun sessionStatus(): SessionStatus = get("spotify/session")
    suspend fun createSession(cookies: SessionCookies): SessionStatus =
        post("spotify/session", json.encodeToString(SessionCreateRequest(cookies)))
    suspend fun deleteSession() { send("DELETE", "spotify/session", null, true) }

    suspend fun play(query: String? = null, uri: String? = null, index: Int = 0, deviceId: String? = null): PlayResponse =
        post("play", json.encodeToString(PlayRequest(query, uri, index, deviceId)))
    suspend fun pause(deviceId: String? = null): OkResponse = command("pause", deviceId)
    suspend fun resume(deviceId: String? = null): OkResponse = command("resume", deviceId)
    suspend fun next(deviceId: String? = null): OkResponse = command("next", deviceId)
    suspend fun prev(deviceId: String? = null): OkResponse = command("prev", deviceId)
    suspend fun nowPlaying(): SpotapiNowPlaying = get("now-playing")
    suspend fun devices(): DevicesResponse = get("devices")
    /** GET /lyrics?uri=spotify:track:ID — 404 NO_RESULTS (SpotapiException) bila lagu tak punya lirik. */
    suspend fun lyrics(uri: String): LyricsResponse = get("lyrics?uri=" + java.net.URLEncoder.encode(uri, "UTF-8"))

    private suspend fun command(path: String, deviceId: String?): OkResponse =
        post(path, if (deviceId == null) "{}" else json.encodeToString(DeviceCommandRequest(deviceId)))

    private suspend inline fun <reified T> get(path: String, auth: Boolean = true): T = json.decodeFromString(send("GET", path, null, auth))
    private suspend inline fun <reified T> post(path: String, body: String, auth: Boolean = true): T = json.decodeFromString(send("POST", path, body, auth))

    private suspend fun send(method: String, path: String, body: String?, auth: Boolean): String {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isBlank()) throw SpotapiNotConfigured()
        val b = Request.Builder().url("$base/$path").header("Accept", "application/json")
        if (auth) b.header("Authorization", "Bearer ${appToken()}")
        b.method(method, body?.toRequestBody(JSON_TYPE))
        return http.newCall(b.build()).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw parseError(r.code, text, r.header("Retry-After"))
            text
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val lenient = Json { ignoreUnknownKeys = true }

        /** Parsing envelope error seragam {"ok":false,"error":{code,message,retryable}}. */
        fun parseError(status: Int, body: String, retryAfter: String?): SpotapiException {
            val env = runCatching { lenient.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
            val code = env?.error?.code?.let { c -> ErrorCode.entries.firstOrNull { it.name == c } }
            return SpotapiException(
                httpStatus = status, code = code, rawCode = env?.error?.code,
                serverMessage = env?.error?.message.orEmpty(),
                retryable = env?.error?.retryable ?: (status == 502 || status == 503),
                retryAfterSec = retryAfter?.trim()?.toLongOrNull(),
            )
        }

        /** Pesan ramah (Indonesia): pakai `message` dari server bila ada, jika tidak pakai bawaan. */
        fun toFailure(e: SpotapiException): SpotifyResult.Failure {
            val err = when (e.code) {
                ErrorCode.BAD_REQUEST -> SpotifyError.BAD_REQUEST
                ErrorCode.UNAUTHORIZED -> SpotifyError.UNAUTHORIZED
                ErrorCode.INVALID_COOKIES, ErrorCode.SPOTIFY_SESSION_INVALID -> SpotifyError.SESSION_INVALID
                ErrorCode.SPOTIFY_SESSION_MISSING -> SpotifyError.SESSION_MISSING
                ErrorCode.NO_RESULTS -> SpotifyError.NO_RESULTS
                ErrorCode.NO_ACTIVE_DEVICE -> SpotifyError.NO_ACTIVE_DEVICE
                ErrorCode.RATE_LIMITED -> SpotifyError.RATE_LIMITED
                ErrorCode.COMMAND_FAILED -> SpotifyError.COMMAND_FAILED
                ErrorCode.SPOTIFY_UPSTREAM, ErrorCode.TOKEN_UNAVAILABLE -> SpotifyError.UPSTREAM
                null -> when (e.httpStatus) {
                    401 -> SpotifyError.UNAUTHORIZED; 403 -> SpotifyError.SESSION_MISSING; 404 -> SpotifyError.NO_RESULTS
                    409 -> SpotifyError.NO_ACTIVE_DEVICE; 429 -> SpotifyError.RATE_LIMITED
                    in 500..599 -> SpotifyError.UPSTREAM; else -> SpotifyError.UNKNOWN
                }
            }
            val fallback = when (e.code) {
                ErrorCode.UNAUTHORIZED -> "Token aplikasi ditolak server spotapi. Pasangkan ulang / periksa token di Pengaturan."
                ErrorCode.INVALID_COOKIES -> "Login Spotify tidak valid. Coba 'Hubungkan akun Spotify' lagi."
                ErrorCode.TOKEN_UNAVAILABLE -> "Server sedang menyiapkan akses Spotify. Coba lagi sebentar."
                ErrorCode.RATE_LIMITED -> e.retryAfterSec?.let { "Terlalu banyak permintaan. Coba lagi dalam $it detik." } ?: err.defaultMessage
                else -> err.defaultMessage
            }
            return SpotifyResult.Failure(err, e.serverMessage.ifBlank { fallback }, e.retryable, e.retryAfterSec)
        }
    }
}

// ---------- Adaptor ke antarmuka SpotifyController ----------

class SpotapiSpotifyController(private val api: SpotapiClient) : SpotifyController {
    override val source = SpotifySource.SPOTAPI

    override suspend fun pause() = cmd("pause") { api.pause() }
    override suspend fun resume() = cmd("resume") { api.resume() }
    override suspend fun next() = cmd("next") { api.next() }
    override suspend fun previous() = cmd("prev") { api.prev() }

    /** Backend hanya mencari lagu (track); kata kunci artis/album tetap dikirim sebagai query. */
    override suspend fun playSearch(query: String, type: String): SpotifyResult<SpotifyAction> = wrap {
        // Coba sekali lagi HANYA bila gagal di level jaringan/timeout (bukan SpotapiException = balasan HTTP nyata),
        // karena /play lambat saat dealer Spotify warm-up (~10 dtk) dan bisa lewat timeout pada percobaan pertama.
        val r = try {
            doPlay(query)
        } catch (e: SpotapiException) {
            throw e
        } catch (e: IOException) {
            doPlay(query)
        }
        SpotifyResult.Ok(SpotifyAction("spotapi", r.track.toTrack()))
    }

    private suspend fun doPlay(query: String): PlayResponse =
        if (query.startsWith("spotify:track:")) api.play(uri = query) else api.play(query = query)

    override suspend fun nowPlaying(): SpotifyResult<NowPlaying?> = wrap {
        val n = api.nowPlaying()
        // tanpa perangkat aktif: 200 dengan track & active_device_id null (bukan error)
        if (n.track == null && n.activeDeviceId == null) SpotifyResult.Ok(null)
        else SpotifyResult.Ok(NowPlaying(n.isPlaying, n.isPaused, n.track?.toTrack(), n.positionMs, n.activeDeviceId, n.deviceName))
    }

    override suspend fun lyrics(uri: String): SpotifyResult<LyricsResponse> = wrap { SpotifyResult.Ok(api.lyrics(uri)) }

    private suspend fun cmd(name: String, block: suspend () -> OkResponse) = wrap {
        block(); SpotifyResult.Ok(SpotifyAction("spotapi", note = name))
    }

    private suspend fun <T> wrap(block: suspend () -> SpotifyResult<T>): SpotifyResult<T> = try {
        block()
    } catch (e: CancellationException) { throw e
    } catch (e: SpotapiException) { SpotapiClient.toFailure(e)
    } catch (e: SpotapiNotConfigured) { SpotifyResult.Failure(SpotifyError.NOT_CONFIGURED, "Base URL spotapi belum diisi.")
    } catch (e: kotlinx.serialization.SerializationException) {
        SpotifyResult.Failure(SpotifyError.UNKNOWN, "Respons server spotapi tidak sesuai kontrak.")
    } catch (e: IOException) { SpotifyResult.Failure(SpotifyError.NETWORK, "Tidak bisa menghubungi server spotapi.", retryable = true)
    }

    private fun SpotapiTrack.toTrack() = SpotifyTrack(title, artists, uri, album, durationMs, imageUrl)
}
