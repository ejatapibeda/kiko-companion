package com.coactive.companion.tools

import com.coactive.companion.spotify.SpotifyController
import com.coactive.companion.spotify.toToolJson
import kotlinx.serialization.json.JsonObject

/** Tool LLM → [SpotifyController] (mode Lokal / Spotify resmi / spotapi dipilih di Pengaturan). */
class SpotifyPlayTool(private val spotify: () -> SpotifyController) : AssistantTool {
    override val name = "spotify_play"
    override val description = "Search Spotify and play a song, artist, album or playlist."
    override val parameters = schema {
        string("query", "What to play, e.g. 'Bohemian Rhapsody Queen' or 'Tulus'", required = true)
        string("type", "Kind of item", enum = listOf("track", "artist", "album", "playlist"))
    }
    override suspend fun execute(args: JsonObject) =
        spotify().playSearch(args.str("query").orEmpty(), args.str("type") ?: "track").toToolJson()
}

class SpotifyControlTool(private val spotify: () -> SpotifyController) : AssistantTool {
    override val name = "spotify_control"
    override val description = "Control music playback: pause, resume, next or previous track."
    override val parameters = schema {
        string("action", "Playback action", required = true, enum = listOf("pause", "resume", "next", "previous"))
    }
    override suspend fun execute(args: JsonObject): String {
        val s = spotify()
        return when (args.str("action")) {
            "pause" -> s.pause()
            "resume", "play" -> s.resume()
            "next" -> s.next()
            "previous", "prev" -> s.previous()
            else -> return """{"ok":false,"error":"BAD_REQUEST","message":"aksi tidak dikenal"}"""
        }.toToolJson()
    }
}

class SpotifyNowPlayingTool(private val spotify: () -> SpotifyController) : AssistantTool {
    override val name = "spotify_now_playing"
    override val description = "Get the song currently playing on Spotify (title, artist, device)."
    override val parameters = schema { }
    override suspend fun execute(args: JsonObject) = spotify().nowPlaying().toToolJson()
}

/**
 * "Joget dong!" → wajah joget (juga di mode Lokal, yang tidak bisa membaca status pemutaran).
 * [setDance] mengembalikan false bila animasi joget dimatikan di Pengaturan.
 */
class FaceDanceTool(private val setDance: (Boolean) -> Boolean) : AssistantTool {
    override val name = "face_dance"
    override val description = "Make your animated face dance to music (start) or stop dancing. " +
        "Use when the user asks you to dance / joget / goyang. The face also dances automatically while Spotify is playing."
    override val parameters = schema {
        string("action", "start or stop dancing", required = true, enum = listOf("start", "stop"))
    }
    override suspend fun execute(args: JsonObject): String {
        val on = args.str("action") != "stop"
        val ok = setDance(on)
        return if (ok || !on) """{"ok":true,"dancing":$on}"""
        else """{"ok":false,"message":"Animasi joget dimatikan di Pengaturan (Tampilan → Animasi joget saat musik)."}"""
    }
}
