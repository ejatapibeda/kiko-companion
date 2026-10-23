package com.coactive.companion.spotify

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.settings.SpotifySource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Mode SPOTIFY RESMI: Spotify Web API (https://api.spotify.com/v1/me/player…) dengan token OAuth PKCE.
 * Kontrol pemutaran butuh akun Premium (batasan Spotify).
 */
class OfficialSpotifyController(
    private val token: suspend () -> String?,
    private val client: OkHttpClient = Http.client,
    private val apiBase: String = "https://api.spotify.com/v1",
) : SpotifyController {
    override val source = SpotifySource.OFFICIAL

    private class HttpError(val code: Int, val body: String, val retryAfter: Long?) : IOException("HTTP $code")

    override suspend fun pause() = action { call(it, "PUT", "me/player/pause") }
    override suspend fun resume() = action { call(it, "PUT", "me/player/play") }
    override suspend fun next() = action { call(it, "POST", "me/player/next") }
    override suspend fun previous() = action { call(it, "POST", "me/player/previous") }

    override suspend fun playSearch(query: String, type: String): SpotifyResult<SpotifyAction> = guarded { tok ->
        val t = if (type in listOf("track", "artist", "album", "playlist")) type else "track"
        val url = "${apiBase.trimEnd('/')}/search".toHttpUrl().newBuilder().addQueryParameter("q", query)
            .addQueryParameter("type", t).addQueryParameter("limit", "1").build()
        val res = Http.json.parseToJsonElement(call(tok, "GET", url.toString(), absolute = true)).jsonObject
        val item = res["${t}s"]?.jsonObject?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@guarded SpotifyResult.Failure(SpotifyError.NO_RESULTS, "Tidak ditemukan di Spotify: $query")
        val uri = item["uri"]!!.jsonPrimitive.content
        val track = SpotifyTrack(
            title = item["name"]?.jsonPrimitive?.contentOrNull ?: query,
            artists = item["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty(),
            uri = uri,
        )
        val body = playBody(t, uri)
        try {
            call(tok, "PUT", "me/player/play", body)
        } catch (e: HttpError) {
            if (e.code != 404) throw e
            // 404 = tidak ada perangkat aktif → coba perangkat yang tersedia (mis. HP ini dengan Spotify terbuka)
            val id = pickDevice(call(tok, "GET", "me/player/devices")) ?: throw e
            call(tok, "PUT", "me/player/play?device_id=$id", body)
        }
        SpotifyResult.Ok(SpotifyAction("web_api", track))
    }

    override suspend fun nowPlaying(): SpotifyResult<NowPlaying?> = guarded { tok ->
        val text = call(tok, "GET", "me/player")
        if (text.isBlank()) return@guarded SpotifyResult.Ok(null) // 204: tidak ada perangkat aktif
        SpotifyResult.Ok(parsePlayer(Http.json.parseToJsonElement(text).jsonObject))
    }

    private suspend fun action(block: suspend (String) -> Unit): SpotifyResult<SpotifyAction> =
        guarded { block(it); SpotifyResult.Ok(SpotifyAction("web_api")) }

    private suspend fun <T> guarded(block: suspend (String) -> SpotifyResult<T>): SpotifyResult<T> {
        val tok = try { token() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ?: return SpotifyResult.Failure(SpotifyError.NOT_CONNECTED)
        return try {
            block(tok)
        } catch (e: CancellationException) { throw e
        } catch (e: HttpError) { mapError(e)
        } catch (e: IOException) { SpotifyResult.Failure(SpotifyError.NETWORK, retryable = true)
        } catch (e: Exception) { SpotifyResult.Failure(SpotifyError.UNKNOWN, "Kesalahan Spotify: ${e.message}") }
    }

    private suspend fun call(token: String, method: String, path: String, json: String? = null, absolute: Boolean = false): String {
        val body = when {
            json != null -> json.toRequestBody("application/json".toMediaType())
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val url = if (absolute) path else "${apiBase.trimEnd('/')}/$path"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, body).build()
        return client.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpError(r.code, text, r.header("Retry-After")?.toLongOrNull())
            text
        }
    }

    companion object {
        fun playBody(type: String, uri: String): String = buildJsonObject {
            if (type == "track") putJsonArray("uris") { add(JsonPrimitive(uri)) } else put("context_uri", uri)
        }.toString()

        /** Pilih perangkat: aktif → Smartphone → pertama. */
        fun pickDevice(devicesJson: String): String? {
            val list = Http.json.parseToJsonElement(devicesJson).jsonObject["devices"]?.jsonArray?.map { it.jsonObject } ?: return null
            fun id(o: JsonObject) = o["id"]?.jsonPrimitive?.contentOrNull
            return (list.firstOrNull { it["is_active"]?.jsonPrimitive?.booleanOrNull == true }
                ?: list.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "Smartphone" }
                ?: list.firstOrNull())?.let(::id)
        }

        fun parsePlayer(o: JsonObject): NowPlaying {
            val item = o["item"]?.takeIf { it is JsonObject }?.jsonObject
            val device = o["device"]?.takeIf { it is JsonObject }?.jsonObject
            val playing = o["is_playing"]?.jsonPrimitive?.booleanOrNull ?: false
            return NowPlaying(
                isPlaying = playing, isPaused = item != null && !playing,
                track = item?.let {
                    SpotifyTrack(
                        title = it["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        artists = it["artists"]?.jsonArray?.mapNotNull { a -> a.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty(),
                        uri = it["uri"]?.jsonPrimitive?.contentOrNull,
                        album = it["album"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
                        durationMs = it["duration_ms"]?.jsonPrimitive?.longOrNull,
                        imageUrl = it["album"]?.jsonObject?.get("images")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
                    )
                },
                positionMs = o["progress_ms"]?.jsonPrimitive?.longOrNull,
                activeDeviceId = device?.get("id")?.jsonPrimitive?.contentOrNull,
                deviceName = device?.get("name")?.jsonPrimitive?.contentOrNull,
            )
        }

        private fun mapError(e: HttpError): SpotifyResult.Failure {
            val reason = runCatching {
                Http.json.parseToJsonElement(e.body).jsonObject["error"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return when {
                e.code == 404 || reason == "NO_ACTIVE_DEVICE" -> SpotifyResult.Failure(SpotifyError.NO_ACTIVE_DEVICE)
                e.code == 401 -> SpotifyResult.Failure(SpotifyError.UNAUTHORIZED, "Login Spotify kedaluwarsa. Hubungkan ulang di Pengaturan.")
                e.code == 403 && reason == "PREMIUM_REQUIRED" -> SpotifyResult.Failure(SpotifyError.PREMIUM_REQUIRED)
                e.code == 403 -> SpotifyResult.Failure(SpotifyError.COMMAND_FAILED, "Spotify menolak perintah ini (butuh Premium atau tidak diizinkan).")
                e.code == 429 -> SpotifyResult.Failure(SpotifyError.RATE_LIMITED, retryable = true, retryAfterSec = e.retryAfter)
                e.code >= 500 -> SpotifyResult.Failure(SpotifyError.UPSTREAM, retryable = true)
                else -> SpotifyResult.Failure(SpotifyError.BAD_REQUEST, "Spotify menolak permintaan (HTTP ${e.code}).")
            }
        }
    }
}
