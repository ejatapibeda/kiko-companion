package com.coactive.companion

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.coactive.companion.assistant.Phase
import com.coactive.companion.assistant.UiState
import com.coactive.companion.settings.ListeningStyle
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.SpotifyTrack
import com.coactive.companion.ui.CompanionTheme
import com.coactive.companion.ui.MainScreen
import com.coactive.companion.ui.face.LocalWeatherPreviewTime
import com.coactive.companion.ui.face.WeatherScene
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Rule
import org.junit.Test

/** Efek cuaca ambient (salju dingin) di tema terang & gelap. Rekam: ./gradlew recordPaparazziDebug */
class WeatherScreenshotTest {
    private val portrait = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2220)
    private val landscape = DeviceConfig.PIXEL_5.copy(screenWidth = 2220, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE)

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = portrait, showSystemUi = false, maxPercentDifference = 0.5)

    private fun home(scene: WeatherScene, dark: Boolean, land: Boolean = false, np: NowPlaying? = null) {
        val base = if (land) landscape else portrait
        paparazzi.unsafeUpdateConfig(if (dark) base.copy(nightMode = NightMode.NIGHT) else base)
        paparazzi.snapshot {
            CompanionTheme(dark) { CompositionLocalProvider(LocalWeatherPreviewTime provides 7.3f) {
                MainScreen(UiState(phase = Phase.IDLE, caption = "Brr, dingin banget"), dark, true, ListeningStyle.BARS,
                    {}, {}, {}, {}, {}, {}, nowPlaying = np, weatherScene = scene)
            } }
        }
    }

    private val np = NowPlaying(isPlaying = true, track = SpotifyTrack("Hati-Hati di Jalan", listOf("Tulus")))

    @Test fun snowLight() = home(WeatherScene.COLD, false)
    @Test fun snowDark() = home(WeatherScene.COLD, true)
    @Test fun snowLandscapeIslandLight() = home(WeatherScene.COLD, false, land = true, np = np)
    @Test fun snowLandscapeIslandDark() = home(WeatherScene.COLD, true, land = true, np = np)
}
