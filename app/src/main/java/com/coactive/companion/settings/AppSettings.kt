package com.coactive.companion.settings

import androidx.annotation.StringRes
import com.coactive.companion.R

/** Pilihan penyedia TTS. */
enum class TtsProvider(@StringRes val labelRes: Int) {
    TIKTOK(R.string.tts_tiktok),
    ELEVENLABS(R.string.tts_eleven),
    OPENAI(R.string.tts_openai),
    ANDROID(R.string.tts_android),
}

enum class ListeningStyle(@StringRes val labelRes: Int) { BARS(R.string.listen_bars), RIPPLE(R.string.listen_ripple), RIPPLE_WIDE(R.string.listen_ripple_wide) }

/** Sumber kontrol Spotify. */
enum class SpotifySource(@StringRes val labelRes: Int) { LOCAL(R.string.spotify_local), OFFICIAL(R.string.spotify_official), SPOTAPI(R.string.spotify_spotapi) }

/** Cara aplikasi mendapat app_token untuk backend spotapi. */
enum class SpotapiAuthMode(@StringRes val labelRes: Int) { PAIRING(R.string.settings_pairing_code), STATIC_TOKEN(R.string.spotapi_static_token) }

enum class ThemeMode(@StringRes val labelRes: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark) }

/** Semua konfigurasi aplikasi. Kunci API disimpan terenkripsi (lihat [SettingsRepository]). */
data class AppSettings(
    // LLM default: Groq (OpenAI-compatible: OpenAI, xAI, Gemini OpenAI endpoint, Groq, dll.)
    val llmBaseUrl: String = "https://api.groq.com/openai/v1",
    val llmApiKey: String = "",
    val llmModel: String = "openai/gpt-oss-120b",
    /** Model khusus pesan bergambar (chat); kosong = pakai [llmModel]. Mis. Groq: qwen/qwen3.8-27b. */
    val visionModel: String = "",
    // STT (default sama dengan LLM; kosong = pakai base URL / key LLM)
    val sttBaseUrl: String = "",
    val sttApiKey: String = "",
    val sttModel: String = "whisper-large-v3-turbo",
    // TTS
    val ttsProvider: TtsProvider = TtsProvider.TIKTOK,
    val tiktokBaseUrl: String = "https://lazypy.ro", // proxy gratis lazypy.ro (Weilbyte lama sudah mati)
    val tiktokVoice: String = "en_us_001",
    val tiktokSessionId: String = "", // opsional: jika diisi, pakai API resmi TikTok dengan cookie sessionid
    val elevenApiKey: String = "",
    val elevenVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
    val elevenModel: String = "eleven_multilingual_v2",
    val openAiTtsBaseUrl: String = "https://api.openai.com/v1",
    val openAiTtsApiKey: String = "",
    val openAiTtsModel: String = "gpt-4o-mini-tts",
    val openAiTtsVoice: String = "alloy",
    val ttsVolume: Float = 1f, // gain output aplikasi 0..1 (default maksimum; tidak mengubah volume sistem)
    // Asisten
    val language: String = "en-US",
    /** Bahasa aplikasi (UI + jawaban). Default English; instalasi lama yang sudah punya "language" tetap ikut nilai itu. */
    val appLanguage: AppLanguage = AppLanguage.EN,
    /** false → tampilkan dialog pilih bahasa sekali saat pertama kali dibuka. */
    val languageChosen: Boolean = false,
    val assistantName: String = "Kiko",
    val defaultCity: String = "Jakarta",
    val wakeWordEnabled: Boolean = true,
    val wakeWordModel: String = "hey_jarvis_v0.1.onnx", // nama file di assets/wakeword atau path absolut
    val wakeWordThreshold: Float = 0.5f,
    val listeningStyle: ListeningStyle = ListeningStyle.BARS,
    val earconEnabled: Boolean = true,
    /** Peredam bising + peredam gema (agar suara TTS sendiri tidak ikut terekam) + AGC pada mikrofon. */
    val noiseSuppression: Boolean = true,
    /** Sensitivitas mic 0..1 (0.5 = perilaku lama). Mengatur gain input + ambang VAD/wake word; lihat audio/MicSensitivity. */
    val micSensitivity: Float = com.coactive.companion.audio.MicSensitivity.DEFAULT,
    val motionReactions: Boolean = true, // reaksi goyang/miring HP + getar halus saat disentuh // bunyi "ting" saat mulai/selesai mendengarkan
    /** Wajah "joget" saat lagu Spotify sedang diputar (lihat assistant/DanceMode.kt). */
    val danceToMusic: Boolean = true,
    /** Ekspresi lucu sesekali sesuai cuaca di lokasi (hujan → bersin, dingin → menggigil, panas → kepanasan). */
    val weatherReactions: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showTextInput: Boolean = true,
    val sleepAfterSeconds: Int = 90,
    // Exa (opsional, pencarian/browsing web segar)
    val exaApiKey: String = "",
    val exaBaseUrl: String = "https://api.exa.ai",
    // Pencarian web: default AUTO = gratis tanpa key (Tavily keyless → SearXNG sendiri → DuckDuckGo → Wikipedia)
    val webSearchProvider: com.coactive.companion.search.WebSearchProvider = com.coactive.companion.search.WebSearchProvider.AUTO,
    val tavilyApiKey: String = "",
    val braveApiKey: String = "",
    val langsearchApiKey: String = "",
    val serperApiKey: String = "",
    val searxngUrl: String = "", // e.g. http://my-server:8888 (JSON format must be enabled)
    // Spotify: sumber kontrol (Lokal default / Web API resmi / backend spotapi)
    val spotifySource: SpotifySource = SpotifySource.LOCAL,
    val spotifyClientId: String = "", // untuk mode resmi (token OAuth disimpan terpisah, terenkripsi, di SettingsRepository)
    val spotapiBaseUrl: String = "", // mis. https://host/api/v1
    val spotapiAuthMode: SpotapiAuthMode = SpotapiAuthMode.PAIRING,
    val spotapiToken: String = "", // app_token (hasil /auth/pair atau ditempel manual) — terenkripsi
) {
    val effectiveSttBaseUrl get() = sttBaseUrl.ifBlank { llmBaseUrl }
    val effectiveSttApiKey get() = sttApiKey.ifBlank { llmApiKey }
    val effectiveTtsBaseUrl get() = openAiTtsBaseUrl.ifBlank { llmBaseUrl }
    val effectiveTtsApiKey get() = openAiTtsApiKey.ifBlank { llmApiKey }
    val languageCode get() = language.substringBefore('-')

    fun webSearchConfig() = com.coactive.companion.search.WebSearchConfig(
        provider = webSearchProvider, tavilyKey = tavilyApiKey.trim(), exaKey = exaApiKey.trim(), exaBaseUrl = exaBaseUrl,
        braveKey = braveApiKey.trim(), langsearchKey = langsearchApiKey.trim(), serperKey = serperApiKey.trim(), searxngUrl = searxngUrl.trim(),
        llmBaseUrl = llmBaseUrl, llmKey = llmApiKey, llmModel = llmModel,
    )

    /** Ganti bahasa aplikasi: ikut set kode bahasa (STT/TTS/prompt) & tukar suara TikTok bila masih suara default bahasa lain. */
    fun withAppLanguage(l: AppLanguage): AppSettings = copy(
        appLanguage = l,
        language = l.speechTag,
        tiktokVoice = if (AppLanguage.entries.any { it != l && it.defaultTiktokVoice == tiktokVoice }) l.defaultTiktokVoice else tiktokVoice,
    )
}

/** Bahasa aplikasi. [tag] = BCP-47 untuk locale UI, [speechTag] = kode bahasa untuk STT/TTS/prompt. */
enum class AppLanguage(val tag: String, val speechTag: String, val defaultTiktokVoice: String, @StringRes val labelRes: Int) {
    EN("en", "en-US", "en_us_001", R.string.lang_english),
    ID("id", "id-ID", "id_001", R.string.lang_indonesian);

    companion object {
        /** Cocokkan dari tag locale/kode bahasa ("in", "id", "id-ID", "en-US", …). */
        fun fromTag(tag: String?): AppLanguage? {
            val lang = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
            return when (lang) { "id", "in" -> ID; "en" -> EN; else -> null }
        }
    }
}
