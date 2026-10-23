package com.coactive.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.coactive.companion.assistant.Phase
import com.coactive.companion.assistant.UiState
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.settings.ListeningStyle
import com.coactive.companion.ui.CompanionTheme
import com.coactive.companion.ui.MainScreen
import com.coactive.companion.ui.Palette
import com.coactive.companion.ui.SettingsScreen
import com.coactive.companion.ui.face.Expression
import com.coactive.companion.ui.face.FaceCanvas
import com.coactive.companion.ui.face.FaceColors
import com.coactive.companion.ui.face.FaceParams
import com.coactive.companion.ui.face.MouthShape
import com.coactive.companion.ui.face.Catchlight
import com.coactive.companion.ui.face.DanceMotion
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Rule
import org.junit.Test

/** Render pratinjau wajah (Paparazzi, JVM). Jalankan: ./gradlew recordPaparazziDebug */
class FaceScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2220), // Galaxy S22
        showSystemUi = false, maxPercentDifference = 0.5)

    private val landscape = DeviceConfig.PIXEL_5.copy(screenWidth = 2220, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE) // Galaxy S22

    private fun main(state: UiState = UiState(), dark: Boolean = false, input: Boolean = true, style: ListeningStyle = ListeningStyle.BARS) {
        paparazzi.snapshot {
            CompanionTheme(dark) { MainScreen(state, dark, input, style, {}, {}, {}, {}, {}, {}) }
        }
    }

    @Test fun portraitLight() = main()
    /** Tampilan referensi "imut": landscape terang + kotak ketik + pil now-playing. */
    @Test fun landscapeCuteNowPlaying() {
        paparazzi.unsafeUpdateConfig(landscape)
        val np = com.coactive.companion.spotify.NowPlaying(isPlaying = true,
            track = com.coactive.companion.spotify.SpotifyTrack("supernatural", listOf("Ariana Grande")))
        paparazzi.snapshot {
            CompanionTheme(false) { MainScreen(UiState(caption = " "), false, true, ListeningStyle.BARS, {}, {}, {}, {}, {}, {}, nowPlaying = np) }
        }
    }
    @Test fun portraitDark() { paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2220, nightMode = NightMode.NIGHT)); main(dark = true) }
    @Test fun landscapeLight() { paparazzi.unsafeUpdateConfig(landscape); main(input = false) }
    @Test fun landscapeDark() { paparazzi.unsafeUpdateConfig(landscape.copy(nightMode = NightMode.NIGHT)); main(dark = true, input = false) }
    @Test fun landscapeInputTalking() { paparazzi.unsafeUpdateConfig(landscape); main(UiState(phase = Phase.TALKING, talkLevel = 1f, caption = "Siap! Timer 5 menit sudah aku pasang. Ada lagi yang bisa aku bantu hari ini?")) }
    @Test fun landscapeListening() { paparazzi.unsafeUpdateConfig(landscape); main(UiState(phase = Phase.LISTENING, micLevel = 0.6f), input = false) }
    @Test fun landscapeSleepy() { paparazzi.unsafeUpdateConfig(landscape.copy(nightMode = NightMode.NIGHT)); main(UiState(phase = Phase.SLEEPING), dark = true, input = false) }
    @Test fun portraitListening() = main(UiState(phase = Phase.LISTENING, micLevel = 0.6f))
    @Test fun portraitThinking() = main(UiState(phase = Phase.THINKING, caption = "pasang timer 5 menit"))
    @Test fun portraitSleepy() = main(UiState(phase = Phase.SLEEPING))
    @Test fun portraitTalking() = main(UiState(phase = Phase.TALKING, talkLevel = 0.8f, caption = "Siap! Timer 5 menit sudah aku pasang."))

    @Test fun expressionSheet() = sheet(dark = false)
    @Test fun expressionSheetDark() = sheet(dark = true)

    private fun sheet(dark: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 2000, screenHeight = 820, orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot {
            val bg = if (dark) Palette.DarkBg else Color(0xFFF7F7FB)
            Column(Modifier.fillMaxSize().background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Expression.entries.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { e ->
                            Tile(e.label, dark) {
                                FaceCanvas(FaceParams.of(e), if (dark) FaceColors.Dark else FaceColors.Light, Modifier.fillMaxSize(), darkGlow = dark)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun poseSheet(poses: List<Pair<String, FaceParams>>) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 2000, screenHeight = 820, orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot {
            Column(Modifier.fillMaxSize().background(Color(0xFFF7F7FB)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                poses.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, p) -> Tile(label, false) { FaceCanvas(p, FaceColors.Light, Modifier.fillMaxSize()) } }
                    }
                }
            }
        }
    }

    /** Variasi mulut idle (puncak animasi). */
    @Test fun mouthVarietySheet() = poseSheet(
        listOf("netral" to FaceParams()) + com.coactive.companion.ui.face.IdleQuirk.entries.map {
            it.label to com.coactive.companion.ui.face.IdleMouthPlanner.apply(FaceParams(), it, 1f)
        }
    )

    /** Reaksi sentuhan (frame di tengah animasi). */
    @Test fun reactionSheet() = poseSheet(
        com.coactive.companion.ui.face.Reaction.entries.map { r ->
            val t = when (r) { com.coactive.companion.ui.face.Reaction.CURIOUS_TILT, com.coactive.companion.ui.face.Reaction.SHY -> 0.5f
                else -> 0.2f }
            r.name.lowercase().replace('_', ' ') to r.apply(FaceParams(), t)
        }
    )

    /** Pose lirikan (frame statis dari animasi gaze): tengah, kiri, kanan, atas, diagonal, mendengarkan, berpikir. */
    @Test fun gazeSheet() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 2000, screenHeight = 820, orientation = ScreenOrientation.LANDSCAPE))
        val n = FaceParams.of(Expression.NEUTRAL)
        fun look(x: Float, y: Float, base: FaceParams = n, widen: Float = 1f) =
            base.copy(lookX = base.lookX + x, lookY = base.lookY + y, scaleL = base.scaleL * widen, scaleR = base.scaleR * widen, glintX = Catchlight.glintFor(base.lookX + x), glintY = Catchlight.glintFor(base.lookY + y))
        val poses = listOf(
            "center" to look(0f, 0f), "look left" to look(-0.7f, 0f), "look right" to look(0.7f, 0f),
            "look up" to look(0f, -0.4f), "up-left" to look(-0.55f, -0.25f), "up-right" to look(0.55f, -0.25f),
            "down-right" to look(0.5f, 0.15f), "listening (widen)" to look(0.02f, -0.02f, widen = 1.07f),
            "thinking L" to look(-0.2f, -0.15f, FaceParams.of(Expression.THINKING)), "thinking R" to look(0.35f, -0.15f, FaceParams.of(Expression.THINKING)),
        )
        paparazzi.snapshot {
            Column(Modifier.fillMaxSize().background(Color(0xFFF7F7FB)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                poses.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, p) -> Tile(label, false) { FaceCanvas(p, FaceColors.Light, Modifier.fillMaxSize()) } }
                    }
                }
            }
        }
    }

    /** Frame joget (116 BPM) sepanjang satu birama: lompat/goyang/lirik per ketukan, mata ^ di ketukan 3–4, not musik. */
    private fun danceSheet(dark: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 2000, screenHeight = 820, orientation = ScreenOrientation.LANDSCAPE))
        val frames = listOf(0f, 0.5f, 1f, 1.5f, 1.85f, 2.5f, 3f, 3.5f, 4.5f, 5.25f)
        val colors = if (dark) FaceColors.Dark else FaceColors.Light
        paparazzi.snapshot {
            Column(Modifier.fillMaxSize().background(if (dark) Palette.DarkBg else Color(0xFFF7F7FB)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                frames.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { b ->
                            var p = DanceMotion.apply(FaceParams(), DanceMotion.frame(b, 1f))
                            p = p.copy(glintX = Catchlight.glintFor(p.lookX) + p.glintX, glintY = Catchlight.glintFor(p.lookY) + p.glintY)
                            Tile("ketukan ${"%.2f".format(b)}", dark) {
                                FaceCanvas(p, colors, Modifier.fillMaxSize(), darkGlow = dark, danceBeats = b, danceNotes = 1f)
                            }
                        }
                    }
                }
            }
        }
    }
    @Test fun danceSheet() = danceSheet(dark = false)
    @Test fun danceSheetDark() = danceSheet(dark = true)
    /** Satu frame joget layar penuh (Galaxy S22 landscape): tengah lompatan, lirik kanan, not ♪ naik. */
    @Test fun landscapeDancingFrame() {
        paparazzi.unsafeUpdateConfig(landscape)
        val b = 1.5f
        var p = DanceMotion.apply(FaceParams(), DanceMotion.frame(b, 1f))
        p = p.copy(glintX = Catchlight.glintFor(p.lookX) + p.glintX, glintY = Catchlight.glintFor(p.lookY) + p.glintY)
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(Palette.LightBg)) {
                FaceCanvas(p, FaceColors.Light, Modifier.fillMaxSize(), danceBeats = b, danceNotes = 1f)
            }
        }
    }

    /** Joget portrait (ponsel tegak): not naik dari atas mata (tidak menimpa mata), EQ di bawah mulut. */
    @Test fun portraitDancingFrame() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5)
        for (b in listOf(1.5f)) {
            var p = DanceMotion.apply(FaceParams(), DanceMotion.frame(b, 1f))
            p = p.copy(glintX = Catchlight.glintFor(p.lookX) + p.glintX, glintY = Catchlight.glintFor(p.lookY) + p.glintY)
            paparazzi.snapshot {
                Box(Modifier.fillMaxSize().background(Palette.LightBg)) {
                    FaceCanvas(p, FaceColors.Light, Modifier.fillMaxSize(), danceBeats = b, danceNotes = 1f)
                }
            }
        }
    }

    @Test fun listeningVariants() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 2000, screenHeight = 440, orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot {
            Row(Modifier.fillMaxSize().background(Color(0xFFF7F7FB)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ListeningStyle.entries.forEach { s ->
                    Tile(androidx.compose.ui.res.stringResource(s.labelRes), false, wide = true) {
                        FaceCanvas(FaceParams.of(Expression.NEUTRAL), FaceColors.Light, Modifier.fillMaxSize(),
                            listening = 1f, listeningStyle = s, phase = 0.3f, micLevel = 0.4f)
                    }
                }
            }
        }
    }

    /** Render nyata FaceCanvas dengan nilai overshoot/undershoot spring: tidak boleh crash (regresi S22). */
    @Test fun overshootValuesDoNotCrash() {
        val wild = FaceParams(openL = 1.00002f, openR = -0.0001f, scaleL = -0.01f, scaleR = 1.0001f, sparkle = 1.00001f, zzz = 1.0000131f,
            blush = 1.0000131f, mouth = MouthShape(1.0f, -0.3f, 0.6f, wave = -0.0000131f))
        val wavy = wild.copy(mouth = MouthShape(1.0f, 0f, 0f, wave = 1.0000131f), openR = 1f)
        paparazzi.snapshot {
            Row(Modifier.fillMaxSize()) {
                listOf(false, true).forEach { dark ->
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        FaceCanvas(wild, if (dark) FaceColors.Dark else FaceColors.Light, Modifier.fillMaxSize(),
                            listening = 1.0000131f, listeningStyle = ListeningStyle.RIPPLE, phase = 0.3f, micLevel = 1.00002f, darkGlow = dark)
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    FaceCanvas(wavy, FaceColors.Light, Modifier.fillMaxSize(), listening = -0.0001f, micLevel = -0.01f)
                }
                Box(Modifier.weight(1f).fillMaxSize()) { // ukuran nol → tidak boleh melempar dari RadialGradient
                    FaceCanvas(wild, FaceColors.Light, Modifier.size(0.dp))
                }
            }
        }
    }

    @Test fun settingsScreen() = paparazzi.snapshot {
        CompanionTheme(false) { SettingsScreen(AppSettings(), com.coactive.companion.assistant.SpotifyStatus(), {}, {}, {}, {}, {}) }
    }

    /** Halaman Pengaturan penuh (tinggi) untuk melihat bagian Spotify per mode. */
    private fun tallSettings(a: AppSettings, st: com.coactive.companion.assistant.SpotifyStatus) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2300))
        paparazzi.snapshot {
            CompanionTheme(false) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(16.dp)) {
                        com.coactive.companion.ui.SpotifySettingsSection(a, {}, st, {}, {}, { _, _ -> null }, {}, {}, {})
                    }
                }
            }
        }
    }
    @Test fun settingsSpotifyOfficial() = tallSettings(
        AppSettings(spotifySource = com.coactive.companion.settings.SpotifySource.OFFICIAL, spotifyClientId = "0123abcd"),
        com.coactive.companion.assistant.SpotifyStatus(officialConnected = true))
    @Test fun settingsSpotapiPairing() = tallSettings(
        AppSettings(spotifySource = com.coactive.companion.settings.SpotifySource.SPOTAPI, spotapiBaseUrl = "https://spot.example.com/api/v1", spotapiToken = "sk_live_x"),
        com.coactive.companion.assistant.SpotifyStatus(spotapiSession = com.coactive.companion.spotify.SessionStatus(true, "31n3nix", "Ejs", "FREE")))
    @Test fun settingsSpotapiStaticToken() = tallSettings(
        AppSettings(spotifySource = com.coactive.companion.settings.SpotifySource.SPOTAPI, spotapiAuthMode = com.coactive.companion.settings.SpotapiAuthMode.STATIC_TOKEN),
        com.coactive.companion.assistant.SpotifyStatus(spotapiMessage = "Isi Base URL spotapi dulu."))

    private val sampleNp = com.coactive.companion.spotify.NowPlaying(true, false,
        com.coactive.companion.spotify.SpotifyTrack("Monokrom", listOf("Tulus"), durationMs = 213000), positionMs = 80000, deviceName = "Galaxy S22")
    @Test fun playerScreen() = paparazzi.snapshot { CompanionTheme(false) { com.coactive.companion.ui.PlayerScreen(sampleNp, false, null, {}, {}, {}) } }

    /** Sampul palsu (gradasi senja + "matahari") untuk pratinjau pemutar tanpa jaringan. */
    private fun fakeArt(): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(400, 400, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 400f, 400f, intArrayOf(0xFFF6B6C8.toInt(), 0xFFB79CF0.toInt(), 0xFF4E5FC7.toInt()),
            null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 400f, 400f, paint)
        paint.shader = null; paint.color = 0xCCFFF1D6.toInt(); c.drawCircle(260f, 150f, 70f, paint)
        paint.color = 0x88283070.toInt(); c.drawRect(0f, 300f, 400f, 400f, paint)
        return bmp.asImageBitmap()
    }
    private val playingNp = com.coactive.companion.spotify.NowPlaying(true, false,
        com.coactive.companion.spotify.SpotifyTrack("supernatural", listOf("Ariana Grande"), durationMs = 162000), positionMs = 71000, deviceName = "Living Room PC")

    @Test fun playerPlayingWithArt() = paparazzi.snapshot {
        CompanionTheme(false) { com.coactive.companion.ui.PlayerScreen(playingNp, false, null, {}, {}, {}, artwork = fakeArt()) }
    }
    @Test fun playerLandscapeWithArt() {
        paparazzi.unsafeUpdateConfig(landscape)
        paparazzi.snapshot { CompanionTheme(false) { com.coactive.companion.ui.PlayerScreen(playingNp, false, null, {}, {}, {}, artwork = fakeArt()) } }
    }
    private val fakeLyrics = com.coactive.companion.spotify.LyricsResponse("6dGnYIeXmHdcikdzNNDMm2", true, "spotify", listOf(
        "Natural, but it feels supernatural", "Everytime you hold me tight", "", "Oh, baby, you're my lullaby",
        "Sing me to sleep, darling", "You're my dream come true", "I can't believe it's real", "Supernatural, oh",
    ).mapIndexed { i, t -> com.coactive.companion.spotify.LyricsLine(60000L + i * 4000L, t) })
    private val lyricsLoader: suspend (String) -> com.coactive.companion.spotify.SpotifyResult<com.coactive.companion.spotify.LyricsResponse> =
        { com.coactive.companion.spotify.SpotifyResult.Ok(fakeLyrics) }
    private val lyricNp = playingNp.copy(track = playingNp.track!!.copy(uri = "spotify:track:6dGnYIeXmHdcikdzNNDMm2"))

    /** Panel lirik terbuka; posisi 71 dtk → baris ke-3 (index 2, "" → ♪)… pakai 73 dtk agar baris "lullaby" aktif. */
    @Test fun playerLyricsSynced() = paparazzi.snapshot {
        CompanionTheme(false) { com.coactive.companion.ui.PlayerScreen(lyricNp.copy(positionMs = 73000), false, null, {}, {}, {},
            artwork = fakeArt(), loadLyrics = lyricsLoader, showLyricsInitially = true) }
    }
    @Test fun playerLyricsUnavailableDark() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2220, nightMode = NightMode.NIGHT))
        paparazzi.snapshot { CompanionTheme(true) { com.coactive.companion.ui.PlayerScreen(lyricNp, true, null, {}, {}, {},
            loadLyrics = { com.coactive.companion.spotify.SpotifyResult.Failure(com.coactive.companion.spotify.SpotifyError.NO_RESULTS) },
            showLyricsInitially = true) } }
    }
    @Test fun playerLyricsLandscape() {
        paparazzi.unsafeUpdateConfig(landscape)
        paparazzi.snapshot { CompanionTheme(false) { com.coactive.companion.ui.PlayerScreen(lyricNp.copy(positionMs = 77000), false, null, {}, {}, {},
            artwork = fakeArt(), loadLyrics = lyricsLoader, showLyricsInitially = true) } }
    }

    @Test fun playerPausedDark() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2220, nightMode = NightMode.NIGHT))
        paparazzi.snapshot { CompanionTheme(true) { com.coactive.companion.ui.PlayerScreen(playingNp.copy(isPlaying = false, isPaused = true), true, null, {}, {}, {}, artwork = fakeArt()) } }
    }
    @Test fun portraitNowPlayingAndNoDevicePrompt() = paparazzi.snapshot {
        CompanionTheme(false) {
            MainScreen(UiState(caption = "Tidak ada perangkat Spotify yang aktif. Buka aplikasi Spotify dulu, lalu coba lagi.",
                spotifyPrompt = com.coactive.companion.assistant.SpotifyPrompt.OPEN_SPOTIFY), false, true, ListeningStyle.BARS,
                {}, {}, {}, {}, {}, {}, nowPlaying = sampleNp)
        }
    }

    @Composable
    private fun Tile(label: String, dark: Boolean, wide: Boolean = false, content: @Composable () -> Unit) {
        Column(
            Modifier.size(if (wide) 220.dp else 110.dp, 115.dp)
                .background(if (dark) Color(0xFF151C30) else Color(0xFFEEF0F8), RoundedCornerShape(12.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) { content() }
            Text(label, fontSize = 11.sp, color = if (dark) Color(0xFFB8C4EE) else Color(0xFF2B3045), modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}
