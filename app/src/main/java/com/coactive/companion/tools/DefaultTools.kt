package com.coactive.companion.tools

import android.content.Context
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.spotify.SpotifyController
import java.util.Locale

object DefaultTools {
    /** Daftar tool bawaan. Tambahkan tool baru di sini. Dibuat ulang tiap giliran agar ikut perubahan Pengaturan. */
    fun create(
        context: Context, settings: () -> AppSettings, spotify: () -> SpotifyController,
        dance: (Boolean) -> Boolean = { false },
    ): ToolRegistry {
        val locale = { Locale.forLanguageTag(settings().language) }
        return ToolRegistry(
            listOf(
                DateTimeTool(locale),
                TimerTool(context),
                AlarmTool(context),
                OpenAppTool(context),
                WeatherTool({ settings().defaultCity }, { settings().languageCode }),
                SpotifyPlayTool(spotify),
                SpotifyControlTool(spotify),
                SpotifyNowPlayingTool(spotify),
                FaceDanceTool(dance),
            ) + webTools(settings),
        )
    }

    /** web_search (penyedia sesuai Pengaturan + fallback gratis) dan browse_web (Exa bila key diisi, lalu pembaca gratis). */
    fun webTools(settings: () -> AppSettings): List<AssistantTool> {
        val cfg = { settings().webSearchConfig() }
        val exa = {
            settings().exaApiKey.takeIf { it.isNotBlank() }?.let { ExaClient({ settings().exaApiKey }, { settings().exaBaseUrl.ifBlank { "https://api.exa.ai" } }) }
        }
        return listOf(WebSearchTool(cfg, { settings().languageCode }), ReaderBrowseTool(cfg, exa = exa))
    }
}
