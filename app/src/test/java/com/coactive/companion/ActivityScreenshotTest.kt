package com.coactive.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.coactive.companion.assistant.ActivityKind
import com.coactive.companion.assistant.ActivityStep
import com.coactive.companion.assistant.AgentActivity
import com.coactive.companion.assistant.Phase
import com.coactive.companion.assistant.UiState
import com.coactive.companion.chat.StoredChatMessage
import com.coactive.companion.search.WebSource
import com.coactive.companion.settings.ListeningStyle
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.SpotifyTrack
import com.coactive.companion.ui.ActivityPill
import com.coactive.companion.ui.ChatScreen
import com.coactive.companion.ui.CompanionTheme
import com.coactive.companion.ui.MainScreen
import com.coactive.companion.ui.Palette
import org.junit.Rule
import org.junit.Test

/** Indikator aktivitas (wajah + pil + chat) di tema terang & gelap. Rekam: ./gradlew recordPaparazziDebug */
class ActivityScreenshotTest {
    private val portrait = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2220) // Galaxy S22
    private val landscape = DeviceConfig.PIXEL_5.copy(screenWidth = 2220, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE)

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = portrait, showSystemUi = false, maxPercentDifference = 0.5)

    private fun home(a: AgentActivity, dark: Boolean, caption: String, land: Boolean = false, np: NowPlaying? = null, phase: Phase = Phase.THINKING) {
        val base = if (land) landscape else portrait
        paparazzi.unsafeUpdateConfig(if (dark) base.copy(nightMode = NightMode.NIGHT) else base)
        paparazzi.snapshot {
            CompanionTheme(dark) {
                MainScreen(UiState(phase = phase, caption = caption, activity = a), dark, true, ListeningStyle.BARS, {}, {}, {}, {}, {}, {}, nowPlaying = np)
            }
        }
    }

    private val np = NowPlaying(isPlaying = true, track = SpotifyTrack("Hati-Hati di Jalan", listOf("Tulus")))

    @Test fun thinkingLight() = home(AgentActivity(ActivityKind.THINKING), false, "kenapa langit biru?")
    @Test fun thinkingDark() = home(AgentActivity(ActivityKind.THINKING), true, "kenapa langit biru?")
    @Test fun webSearchLight() = home(AgentActivity(ActivityKind.WEB_SEARCH, "harga emas hari ini"), false, "cariin harga emas hari ini")
    @Test fun webSearchDark() = home(AgentActivity(ActivityKind.WEB_SEARCH, "harga emas hari ini"), true, "cariin harga emas hari ini")
    @Test fun spotifyLight() = home(AgentActivity(ActivityKind.SPOTIFY_PLAY, "Tulus"), false, "puterin lagu Tulus")
    @Test fun spotifyDark() = home(AgentActivity(ActivityKind.SPOTIFY_PLAY, "Tulus"), true, "puterin lagu Tulus")
    @Test fun weatherLight() = home(AgentActivity(ActivityKind.WEATHER, "Bandung"), false, "cuaca di Bandung gimana?")
    @Test fun browsingDark() = home(AgentActivity(ActivityKind.BROWSING, "kompas.com"), true, "baca beritanya dong")
    @Test fun failedLight() = home(AgentActivity(ActivityKind.SPOTIFY_PLAY, "Tulus", failed = true), false, "puterin lagu Tulus")
    @Test fun speakingPrepLight() = home(AgentActivity(ActivityKind.SPEAKING_PREP), false, "Harga emas hari ini sekitar…", phase = Phase.TALKING)
    /** Landscape + Dynamic Island aktif: pil di bawah, gelembung di samping → tidak bertabrakan dengan island. */
    @Test fun landscapeSearchWithIslandLight() = home(AgentActivity(ActivityKind.WEB_SEARCH, "jadwal timnas"), false, "jadwal timnas", land = true, np = np)
    @Test fun landscapeSpotifyWithIslandDark() = home(AgentActivity(ActivityKind.SPOTIFY_NEXT), true, "lagu berikutnya", land = true, np = np)
    @Test fun landscapeThinkingDark() = home(AgentActivity(ActivityKind.THINKING), true, "ceritain lelucon", land = true)

    private fun pillSheet(dark: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(screenWidth = 1500, screenHeight = 1500, nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT))
        val items = listOf(
            AgentActivity(ActivityKind.LISTENING), AgentActivity(ActivityKind.TRANSCRIBING), AgentActivity(ActivityKind.THINKING),
            AgentActivity(ActivityKind.VISION), AgentActivity(ActivityKind.WEB_SEARCH, "harga emas"), AgentActivity(ActivityKind.BROWSING, "kompas.com"),
            AgentActivity(ActivityKind.SPOTIFY_PLAY, "Tulus"), AgentActivity(ActivityKind.SPOTIFY_PAUSE), AgentActivity(ActivityKind.SPOTIFY_NEXT),
            AgentActivity(ActivityKind.SPOTIFY_NOW_PLAYING), AgentActivity(ActivityKind.WEATHER, "Bandung"), AgentActivity(ActivityKind.LYRICS),
            AgentActivity(ActivityKind.TIMER, "5m"), AgentActivity(ActivityKind.ALARM, "06:00"), AgentActivity(ActivityKind.OPEN_APP, "YouTube"),
            AgentActivity(ActivityKind.DATETIME), AgentActivity(ActivityKind.DANCE), AgentActivity(ActivityKind.TOOL, "custom_tool"),
            AgentActivity(ActivityKind.SPEAKING_PREP), AgentActivity(ActivityKind.WEB_SEARCH, "x", failed = true),
        )
        paparazzi.snapshot {
            CompanionTheme(dark) {
                Column(Modifier.fillMaxSize().background(if (dark) Palette.DarkBg else Color(0xFFF1F2FA)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { ActivityPill(it, dark, reducedMotion = true) } }
                    }
                }
            }
        }
    }
    @Test fun pillSheetLight() = pillSheet(false)
    @Test fun pillSheetDark() = pillSheet(true)

    private fun chat(dark: Boolean, activity: AgentActivity) {
        paparazzi.unsafeUpdateConfig(if (dark) portrait.copy(nightMode = NightMode.NIGHT) else portrait)
        val msgs = listOf(
            StoredChatMessage(1, "user", "cariin berita timnas terbaru", timestamp = 0),
            StoredChatMessage(2, "assistant", "Timnas menang 2-0 tadi malam, menurut Kompas.", timestamp = 0,
                sources = listOf(WebSource("Timnas menang 2-0", "https://www.kompas.com/a"), WebSource("Hasil laga", "https://detik.com/b")),
                steps = listOf(ActivityStep("WEB_SEARCH", "berita timnas terbaru"), ActivityStep("BROWSING", "kompas.com"))),
            StoredChatMessage(3, "user", "puterin lagu Tulus dong", timestamp = 0),
        )
        paparazzi.snapshot {
            CompanionTheme(dark) {
                val owner = object : androidx.activity.result.ActivityResultRegistryOwner {
                    override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                        override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                            options: androidx.core.app.ActivityOptionsCompat?) = Unit
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                    ChatScreen(msgs, true, null, "Kiko", dark, {}, {}, {}, {}, {}, {}, activity = activity)
                }
            }
        }
    }
    @Test fun chatStatusSpotifyLight() = chat(false, AgentActivity(ActivityKind.SPOTIFY_PLAY, "Tulus"))
    @Test fun chatStatusSearchDark() = chat(true, AgentActivity(ActivityKind.WEB_SEARCH, "lagu Tulus terbaru"))
}
