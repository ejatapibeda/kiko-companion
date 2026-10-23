package com.coactive.companion.spotify

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import com.coactive.companion.settings.SpotifySource

/**
 * Mode LOKAL (default): tanpa login. Cari & putar via intent MEDIA_PLAY_FROM_SEARCH ke aplikasi Spotify
 * (fallback: buka pencarian Spotify), kontrol via tombol media sistem.
 */
class LocalSpotifyController(private val context: Context) : SpotifyController {
    override val source = SpotifySource.LOCAL

    override suspend fun pause() = mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    override suspend fun resume() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    override suspend fun next() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    override suspend fun previous() = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    override suspend fun nowPlaying(): SpotifyResult<NowPlaying?> =
        SpotifyResult.Failure(SpotifyError.NOT_SUPPORTED, "Mode Lokal tidak bisa membaca lagu yang sedang diputar.")

    private fun mediaKey(key: Int): SpotifyResult<SpotifyAction> {
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return SpotifyResult.Ok(SpotifyAction("media_key"))
    }

    override suspend fun playSearch(query: String, type: String): SpotifyResult<SpotifyAction> {
        val focus = when (type) {
            "artist" -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
            "album" -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
            "playlist" -> "vnd.android.cursor.item/playlist"
            else -> "vnd.android.cursor.item/*"
        }
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY_PKG)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)
            .putExtra(android.app.SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (type == "artist") search.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
        return try {
            context.startActivity(search)
            SpotifyResult.Ok(SpotifyAction("spotify_intent", SpotifyTrack(query, emptyList())))
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SpotifyResult.Ok(SpotifyAction("spotify_search", note = "membuka pencarian Spotify, pengguna perlu menekan play"))
            } catch (e2: Exception) {
                SpotifyResult.Failure(SpotifyError.NOT_SUPPORTED, "Aplikasi Spotify tidak terpasang.")
            }
        }
    }

    companion object {
        const val SPOTIFY_PKG = "com.spotify.music"

        /** Buka aplikasi Spotify (untuk mengaktifkan perangkat). */
        fun openSpotifyApp(context: Context): Boolean {
            val i = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("spotify:")).takeIf { true }
            return runCatching { context.startActivity(i!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrElse {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                }.getOrDefault(false)
            }
        }
    }
}
