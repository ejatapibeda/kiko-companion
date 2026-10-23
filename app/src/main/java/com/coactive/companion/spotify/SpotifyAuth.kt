package com.coactive.companion.spotify

import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth Authorization Code + PKCE (tanpa client secret) untuk Spotify Web API. */
object Pkce {
    fun verifier(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(48).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
}

/** Penyimpanan token (implementasi nyata: SettingsRepository → EncryptedSharedPreferences). */
interface SpotifyTokenStore {
    var spotifyAccessToken: String
    var spotifyRefreshToken: String
    var spotifyExpiresAt: Long
    var spotifyPkceVerifier: String
    var spotifyOAuthState: String
}

class SpotifyAuth(
    private val store: SpotifyTokenStore,
    private val client: OkHttpClient = Http.client,
    private val accountsBase: String = "https://accounts.spotify.com",
    private val now: () -> Long = System::currentTimeMillis,
) {
    val isAuthorized get() = store.spotifyRefreshToken.isNotBlank()

    /** URL login (dibuka di Custom Tab). Menyimpan verifier & state untuk langkah tukar kode. */
    fun authorizeUrl(clientId: String): String {
        val v = Pkce.verifier(); store.spotifyPkceVerifier = v
        val state = Pkce.verifier().take(16); store.spotifyOAuthState = state
        return buildAuthorizeUrl(accountsBase, clientId, Pkce.challenge(v), state)
    }

    /** Langkah 2: tukar `code` dari redirect menjadi token (dengan verifikasi `state`). */
    suspend fun exchangeCode(code: String, state: String?, clientId: String): Boolean {
        if (store.spotifyOAuthState.isNotBlank() && state != store.spotifyOAuthState) return false
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", REDIRECT_URI).add("client_id", clientId).add("code_verifier", store.spotifyPkceVerifier).build()
        storeTokens(post(body))
        store.spotifyPkceVerifier = ""; store.spotifyOAuthState = ""
        return true
    }

    /** Token akses yang masih berlaku (refresh otomatis 60 dtk sebelum kedaluwarsa), atau null jika belum login. */
    suspend fun accessToken(clientId: String): String? {
        if (!isAuthorized || clientId.isBlank()) return null
        if (now() < store.spotifyExpiresAt - 60_000) return store.spotifyAccessToken
        val body = FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", store.spotifyRefreshToken).add("client_id", clientId).build()
        storeTokens(post(body))
        return store.spotifyAccessToken
    }

    fun logout() { store.spotifyAccessToken = ""; store.spotifyRefreshToken = ""; store.spotifyExpiresAt = 0 }

    private suspend fun post(body: FormBody) =
        client.newCall(Request.Builder().url("${accountsBase.trimEnd('/')}/api/token").post(body).build()).await().bodyOrThrow()

    private fun storeTokens(json: String) {
        val o = Http.json.parseToJsonElement(json).jsonObject
        store.spotifyAccessToken = o["access_token"]!!.jsonPrimitive.content
        // refresh token bisa dirotasi; jika tidak dikirim, pakai yang lama
        o["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { store.spotifyRefreshToken = it }
        store.spotifyExpiresAt = now() + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) * 1000
    }

    companion object {
        const val REDIRECT_SCHEME = "com.coactive.companion"
        const val REDIRECT_HOST = "spotify-callback"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"
        const val SCOPES = "user-modify-playback-state user-read-playback-state streaming"

        fun buildAuthorizeUrl(accountsBase: String, clientId: String, challenge: String, state: String): String =
            "${accountsBase.trimEnd('/')}/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("state", state)
                .addQueryParameter("scope", SCOPES)
                .build().toString()
    }
}
