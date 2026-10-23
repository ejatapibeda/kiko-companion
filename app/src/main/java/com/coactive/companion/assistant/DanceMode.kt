package com.coactive.companion.assistant

import com.coactive.companion.settings.SpotifySource
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.PlaybackIntent

/** Kenapa wajah sedang joget. */
enum class DanceReason { PLAYBACK, LOCAL_SESSION, MANUAL }

/**
 * Mesin status mode joget (murni, tanpa Android → mudah diuji).
 *
 * Pemicu per sumber Spotify (sumber EFEKTIF dari SpotifyControllerFactory, jadi "resmi" yang belum login = Lokal):
 *  - Spotify resmi / spotapi: joget selama now-playing `is_playing == true`; berhenti saat jeda/berhenti/null.
 *  - Lokal: tidak ada data now-playing (tanpa izin baru, tanpa Visualizer). Proksi ringan: aksi putar/lanjut/
 *    berikut/sebelumnya yang berhasil memulai "sesi musik" selama [LOCAL_SESSION_MS]; aksi jeda mengakhirinya.
 *    (Jeda dari aplikasi Spotify sendiri tidak terlihat → sesi habis sendiri.)
 *  - Manual (semua mode): perintah "joget dong" (tool face_dance) atau menu ⋯ → "Joget!". Berhenti lewat menu,
 *    perintah "berhenti joget", atau aksi jeda.
 * Selalu mengalah pada interaksi asisten: hanya joget saat [Phase.IDLE] (tidak saat mendengarkan/berpikir/bicara/tidur),
 * lalu otomatis lanjut lagi. Toggle Pengaturan "Animasi joget saat musik" mati → tidak pernah joget.
 */
class DanceMode(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var localSessionUntil = 0L
    @Volatile var manual = false
        private set

    /** Aksi pemutaran yang berhasil (dari ObservedSpotifyController). [source] = sumber efektif saat aksi. */
    fun onPlaybackAction(intent: PlaybackIntent, source: SpotifySource) {
        when (intent) {
            PlaybackIntent.PLAY -> if (source == SpotifySource.LOCAL) localSessionUntil = clock() + LOCAL_SESSION_MS
            PlaybackIntent.PAUSE -> { localSessionUntil = 0L; manual = false }
        }
    }

    fun setManual(on: Boolean) { manual = on }

    /** Ada musik/joget yang "aktif" (terlepas dari fase asisten), atau null. */
    fun reason(enabled: Boolean, source: SpotifySource, nowPlaying: NowPlaying?): DanceReason? {
        if (!enabled) return null
        if (manual) return DanceReason.MANUAL
        return when (source) {
            SpotifySource.LOCAL -> if (clock() < localSessionUntil) DanceReason.LOCAL_SESSION else null
            SpotifySource.OFFICIAL, SpotifySource.SPOTAPI -> if (nowPlaying?.isPlaying == true) DanceReason.PLAYBACK else null
        }
    }

    /** Wajah benar-benar joget sekarang? (interaksi asisten didahulukan). */
    fun shouldDance(enabled: Boolean, source: SpotifySource, nowPlaying: NowPlaying?, phase: Phase): Boolean =
        phase == Phase.IDLE && reason(enabled, source, nowPlaying) != null

    companion object {
        /** Lama "sesi musik" mode Lokal setelah aksi putar terakhir (~1–2 lagu). */
        const val LOCAL_SESSION_MS = 5 * 60_000L
    }
}
