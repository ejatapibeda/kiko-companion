package com.coactive.companion.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.coactive.companion.tts.TikTokTts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Menyimpan pengaturan di EncryptedSharedPreferences (AES-256, kunci di Android Keystore),
 * jadi API key tidak pernah tersimpan dalam teks biasa.
 */
class SettingsRepository(context: Context) : com.coactive.companion.spotify.SpotifyTokenStore {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Token Spotify (juga terenkripsi). */
    override var spotifyAccessToken: String
        get() = prefs.getString("spotify_access", "") ?: ""
        set(v) = prefs.edit().putString("spotify_access", v).apply()
    override var spotifyRefreshToken: String
        get() = prefs.getString("spotify_refresh", "") ?: ""
        set(v) = prefs.edit().putString("spotify_refresh", v).apply()
    override var spotifyExpiresAt: Long
        get() = prefs.getLong("spotify_exp", 0)
        set(v) = prefs.edit().putLong("spotify_exp", v).apply()
    override var spotifyOAuthState: String
        get() = prefs.getString("spotify_state", "") ?: ""
        set(v) = prefs.edit().putString("spotify_state", v).apply()
    override var spotifyPkceVerifier: String
        get() = prefs.getString("spotify_verifier", "") ?: ""
        set(v) = prefs.edit().putString("spotify_verifier", v).apply()

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        save(s)
        _settings.value = s
    }

    /** Baca ulang dari prefs (dipakai setelah impor). */
    fun reload() { _settings.value = load() }

    /**
     * DEBUG-only: ekspor semua pengaturan (termasuk API key) sebagai JSON bertipe, untuk sinkron antar perangkat.
     * File prefs terenkripsi tidak bisa disalin mentah — kuncinya terikat Keystore tiap perangkat.
     * Token OAuth Spotify sengaja TIDAK ikut (refresh token berotasi → satu perangkat bisa logout).
     */
    fun exportJson(): String {
        val o = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (k in SYNC_EXCLUDED || v == null) continue
            val t = when (v) { is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> continue }
            o.put(k, org.json.JSONObject().put("t", t).put("v", if (v is Float) v.toDouble() else v))
        }
        return org.json.JSONObject().put("v", 1).put("prefs", o).toString()
    }

    /** DEBUG-only: kebalikan dari [exportJson]. Mengembalikan jumlah kunci yang diimpor. */
    fun importJson(json: String): Int {
        val o = org.json.JSONObject(json).getJSONObject("prefs")
        val e = prefs.edit()
        var n = 0
        for (k in o.keys()) {
            if (k in SYNC_EXCLUDED) continue
            val item = o.getJSONObject(k)
            when (item.getString("t")) {
                "s" -> e.putString(k, item.getString("v")); "b" -> e.putBoolean(k, item.getBoolean("v"))
                "i" -> e.putInt(k, item.getInt("v")); "l" -> e.putLong(k, item.getLong("v"))
                "f" -> e.putFloat(k, item.getDouble("v").toFloat()); else -> continue
            }
            n++
        }
        e.commit()
        reload()
        return n
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        return AppSettings(
            llmBaseUrl = s("llmBaseUrl", d.llmBaseUrl), llmApiKey = s("llmApiKey", ""), llmModel = s("llmModel", d.llmModel), visionModel = s("visionModel", d.visionModel),
            sttBaseUrl = s("sttBaseUrl", ""), sttApiKey = s("sttApiKey", ""), sttModel = s("sttModel", d.sttModel),
            ttsProvider = runCatching { TtsProvider.valueOf(s("ttsProvider", d.ttsProvider.name)) }.getOrDefault(d.ttsProvider),
            // migrasi: proxy Weilbyte lama sudah mati → pindah ke default (lazypy)
            tiktokBaseUrl = s("tiktokBaseUrl", d.tiktokBaseUrl).let { if (it.trim().trimEnd('/') in TikTokTts.DEAD_URLS) d.tiktokBaseUrl else it }, tiktokVoice = s("tiktokVoice", d.tiktokVoice),
            tiktokSessionId = s("tiktokSessionId", ""),
            elevenApiKey = s("elevenApiKey", ""), elevenVoiceId = s("elevenVoiceId", d.elevenVoiceId), elevenModel = s("elevenModel", d.elevenModel),
            openAiTtsBaseUrl = s("openAiTtsBaseUrl", ""), openAiTtsApiKey = s("openAiTtsApiKey", ""),
            openAiTtsModel = s("openAiTtsModel", d.openAiTtsModel), openAiTtsVoice = s("openAiTtsVoice", d.openAiTtsVoice),
            ttsVolume = prefs.getFloat("ttsVolume", d.ttsVolume),
            language = s("language", d.language),
            // migrasi: instalasi lama (sudah ada "language" tapi belum ada "appLanguage") → ikut bahasa lama, tanpa dialog
            appLanguage = prefs.getString("appLanguage", null)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.fromTag(prefs.getString("language", null)) ?: d.appLanguage,
            languageChosen = prefs.getBoolean("languageChosen", prefs.contains("language")), assistantName = s("assistantName", d.assistantName),
            defaultCity = s("defaultCity", d.defaultCity),
            wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", d.wakeWordEnabled),
            wakeWordModel = s("wakeWordModel", d.wakeWordModel),
            wakeWordThreshold = prefs.getFloat("wakeWordThreshold", d.wakeWordThreshold),
            listeningStyle = runCatching { ListeningStyle.valueOf(s("listeningStyle", d.listeningStyle.name)) }.getOrDefault(d.listeningStyle),
            themeMode = runCatching { ThemeMode.valueOf(s("themeMode", d.themeMode.name)) }.getOrDefault(d.themeMode),
            showTextInput = prefs.getBoolean("showTextInput", d.showTextInput),
            earconEnabled = prefs.getBoolean("earconEnabled", d.earconEnabled),
            noiseSuppression = prefs.getBoolean("noiseSuppression", d.noiseSuppression),
            micSensitivity = com.coactive.companion.audio.MicSensitivity.sanitize(prefs.getFloat("micSensitivity", d.micSensitivity)),
            motionReactions = prefs.getBoolean("motionReactions", d.motionReactions),
            danceToMusic = prefs.getBoolean("danceToMusic", d.danceToMusic),
            weatherReactions = prefs.getBoolean("weatherReactions", d.weatherReactions),
            sleepAfterSeconds = prefs.getInt("sleepAfterSeconds", d.sleepAfterSeconds),
            spotifyClientId = s("spotifyClientId", ""),
            spotifySource = runCatching { SpotifySource.valueOf(s("spotifySource", d.spotifySource.name)) }.getOrDefault(d.spotifySource),
            spotapiBaseUrl = s("spotapiBaseUrl", ""),
            spotapiAuthMode = runCatching { SpotapiAuthMode.valueOf(s("spotapiAuthMode", d.spotapiAuthMode.name)) }.getOrDefault(d.spotapiAuthMode),
            spotapiToken = s("spotapiToken", ""),
            exaApiKey = s("exaApiKey", ""), exaBaseUrl = s("exaBaseUrl", d.exaBaseUrl),
            // Migrasi: pengguna lama yang sudah mengisi key Exa tetap memakai Exa (dengan fallback gratis).
            webSearchProvider = runCatching { com.coactive.companion.search.WebSearchProvider.valueOf(s("webSearchProvider",
                if (s("exaApiKey", "").isNotBlank()) "EXA" else d.webSearchProvider.name)) }.getOrDefault(d.webSearchProvider),
            tavilyApiKey = s("tavilyApiKey", ""), braveApiKey = s("braveApiKey", ""), langsearchApiKey = s("langsearchApiKey", ""),
            serperApiKey = s("serperApiKey", ""), searxngUrl = s("searxngUrl", ""),
        )
    }

    private fun save(a: AppSettings) {
        prefs.edit()
            .putString("llmBaseUrl", a.llmBaseUrl).putString("llmApiKey", a.llmApiKey).putString("llmModel", a.llmModel).putString("visionModel", a.visionModel)
            .putString("sttBaseUrl", a.sttBaseUrl).putString("sttApiKey", a.sttApiKey).putString("sttModel", a.sttModel)
            .putString("ttsProvider", a.ttsProvider.name)
            .putString("tiktokBaseUrl", a.tiktokBaseUrl).putString("tiktokVoice", a.tiktokVoice).putString("tiktokSessionId", a.tiktokSessionId)
            .putString("elevenApiKey", a.elevenApiKey).putString("elevenVoiceId", a.elevenVoiceId).putString("elevenModel", a.elevenModel)
            .putString("openAiTtsBaseUrl", a.openAiTtsBaseUrl).putString("openAiTtsApiKey", a.openAiTtsApiKey)
            .putString("openAiTtsModel", a.openAiTtsModel).putString("openAiTtsVoice", a.openAiTtsVoice)
            .putFloat("ttsVolume", a.ttsVolume)
            .putString("language", a.language).putString("appLanguage", a.appLanguage.name).putBoolean("languageChosen", a.languageChosen).putString("assistantName", a.assistantName).putString("defaultCity", a.defaultCity)
            .putBoolean("wakeWordEnabled", a.wakeWordEnabled).putString("wakeWordModel", a.wakeWordModel)
            .putFloat("wakeWordThreshold", a.wakeWordThreshold)
            .putString("listeningStyle", a.listeningStyle.name).putString("themeMode", a.themeMode.name)
            .putBoolean("showTextInput", a.showTextInput).putBoolean("earconEnabled", a.earconEnabled).putBoolean("noiseSuppression", a.noiseSuppression).putFloat("micSensitivity", com.coactive.companion.audio.MicSensitivity.sanitize(a.micSensitivity)).putBoolean("motionReactions", a.motionReactions).putBoolean("danceToMusic", a.danceToMusic).putBoolean("weatherReactions", a.weatherReactions).putInt("sleepAfterSeconds", a.sleepAfterSeconds)
            .putString("spotifyClientId", a.spotifyClientId).putString("spotifySource", a.spotifySource.name)
            .putString("spotapiBaseUrl", a.spotapiBaseUrl).putString("spotapiAuthMode", a.spotapiAuthMode.name).putString("spotapiToken", a.spotapiToken)
            .putString("exaApiKey", a.exaApiKey).putString("exaBaseUrl", a.exaBaseUrl)
            .putString("webSearchProvider", a.webSearchProvider.name).putString("tavilyApiKey", a.tavilyApiKey)
            .putString("braveApiKey", a.braveApiKey).putString("langsearchApiKey", a.langsearchApiKey)
            .putString("serperApiKey", a.serperApiKey).putString("searxngUrl", a.searxngUrl)
            .apply()
    }

    private companion object {
        val SYNC_EXCLUDED = setOf("spotify_access", "spotify_refresh", "spotify_exp", "spotify_state", "spotify_verifier")
        fun createPrefs(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, "companion_secure_prefs", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
