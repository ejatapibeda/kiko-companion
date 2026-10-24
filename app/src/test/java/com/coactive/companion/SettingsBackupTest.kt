package com.coactive.companion

import com.coactive.companion.search.WebSearchProvider
import com.coactive.companion.settings.AppLanguage
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.settings.ListeningStyle
import com.coactive.companion.settings.SettingsBackup
import com.coactive.companion.settings.SpotapiAuthMode
import com.coactive.companion.settings.SpotifySource
import com.coactive.companion.settings.ThemeMode
import com.coactive.companion.settings.TtsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class SettingsBackupTest {
    private val custom = AppSettings(
        llmBaseUrl = "https://llm.example/v1", llmApiKey = "k-llm", llmModel = "m1", visionModel = "v1",
        sttBaseUrl = "https://stt.example", sttApiKey = "k-stt", sttModel = "s1", ttsProvider = TtsProvider.OPENAI,
        tiktokBaseUrl = "https://tt.example", tiktokVoice = "id_001", tiktokSessionId = "sess", elevenApiKey = "k-el",
        elevenVoiceId = "voice", elevenModel = "em", openAiTtsBaseUrl = "https://tts.example", openAiTtsApiKey = "k-oai",
        openAiTtsModel = "tm", openAiTtsVoice = "nova", ttsVolume = 0.7f, language = "id-ID", appLanguage = AppLanguage.ID,
        languageChosen = true, assistantName = "Kiki", defaultCity = "Bandung", wakeWordEnabled = false,
        wakeWordModel = "x.onnx", wakeWordThreshold = 0.35f, listeningStyle = ListeningStyle.RIPPLE, earconEnabled = false,
        noiseSuppression = false, micSensitivity = 0.8f, motionReactions = false, danceToMusic = false, weatherReactions = false,
        themeMode = ThemeMode.DARK, showTextInput = false, sleepAfterSeconds = 45, exaApiKey = "k-exa", exaBaseUrl = "https://exa.example",
        webSearchProvider = WebSearchProvider.SEARXNG, tavilyApiKey = "k-tav", braveApiKey = "k-br", langsearchApiKey = "k-ls",
        serperApiKey = "k-se", searxngUrl = "http://searx.example:8888", spotifySource = SpotifySource.SPOTAPI,
        spotifyClientId = "cid", spotapiBaseUrl = "https://sp.example/api/v1", spotapiAuthMode = SpotapiAuthMode.STATIC_TOKEN,
        spotapiToken = "app-token",
    )

    @Test fun everyAppSettingsFieldIsSerialized() {
        val declared = AppSettings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(declared, SettingsBackup.FIELD_NAMES)
    }

    @Test fun customValuesDifferFromDefaults() {
        // memastikan round-trip benar-benar menguji tiap field (bukan kebetulan sama dengan default)
        val d = SettingsBackup.fields(AppSettings()); val c = SettingsBackup.fields(custom)
        assertEquals(emptyList<String>(), d.keys.filter { d[it] == c[it] })
    }

    @Test fun roundTripKeepsEverything() {
        val b = SettingsBackup.Backup(custom, "id", SettingsBackup.SpotifyTokens("acc", "ref", 123L), "1.0.0")
        val text = SettingsBackup.encode(b, exportedAtMs = 42L)
        val back = SettingsBackup.decode(text)
        assertEquals(custom, back.settings)
        assertEquals("id", back.localeTag)
        assertEquals(SettingsBackup.SpotifyTokens("acc", "ref", 123L), back.spotify)
        assertEquals("1.0.0", back.appVersion)
        assertEquals(SettingsBackup.FIELD_NAMES.size, back.fieldCount)
        assertTrue(text.contains("\"format\": \"kiko-settings\"") && text.contains("\"version\": 2"))
    }

    @Test fun defaultsRoundTripAndEmptySpotifyIsOmitted() {
        val text = SettingsBackup.encode(SettingsBackup.Backup(AppSettings(), null, SettingsBackup.SpotifyTokens("", "", 0)))
        assertFalse(text.contains("\"spotify\""))
        val back = SettingsBackup.decode(text)
        assertEquals(AppSettings(), back.settings); assertNull(back.localeTag); assertNull(back.spotify)
    }

    @Test fun missingAndInvalidFieldsFallBackToDefaults() {
        val text = """{"format":"kiko-settings","version":2,"settings":{"llmModel":"x","ttsProvider":"NOPE","micSensitivity":"loud","unknown":1}}"""
        val s = SettingsBackup.decode(text).settings
        assertEquals("x", s.llmModel); assertEquals(AppSettings().ttsProvider, s.ttsProvider)
        assertEquals(AppSettings().micSensitivity, s.micSensitivity, 0f)
        assertEquals(3, SettingsBackup.decode(text).fieldCount)
    }

    @Test fun legacyV1PrefsFormatIsAccepted() {
        val text = """{"v":1,"prefs":{"llmModel":{"t":"s","v":"old"},"micSensitivity":{"t":"f","v":0.25},"wakeWordEnabled":{"t":"b","v":false},
            "sleepAfterSeconds":{"t":"i","v":30},"language":{"t":"s","v":"id-ID"}}}"""
        val s = SettingsBackup.decode(text).settings
        assertEquals("old", s.llmModel); assertEquals(0.25f, s.micSensitivity, 1e-6f); assertFalse(s.wakeWordEnabled)
        assertEquals(30, s.sleepAfterSeconds); assertEquals(AppLanguage.ID, s.appLanguage); assertTrue(s.languageChosen)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsForeignJson() { SettingsBackup.decode("""{"hello":"world"}""") }
    @Test(expected = IllegalArgumentException::class) fun rejectsGarbage() { SettingsBackup.decode("not json") }
    @Test(expected = IllegalArgumentException::class) fun rejectsFutureVersion() {
        SettingsBackup.decode("""{"format":"kiko-settings","version":99,"settings":{}}""")
    }

    @Test fun summaryNeverContainsSecrets() {
        val b = SettingsBackup.Backup(custom, "id", SettingsBackup.SpotifyTokens("acc", "ref", 1))
        val sum = SettingsBackup.summaryJson(b)
        for (k in SettingsBackup.SECRET_FIELDS) assertFalse(k, sum.contains("\"" + SettingsBackup.fields(custom)[k]!!.content + "\""))
        assertFalse(sum.contains("\"acc\"") || sum.contains("\"ref\""))
        assertEquals(SettingsBackup.hashOf("k-llm"), SettingsBackup.summary(b)["llmApiKey"])
        assertEquals("m1", SettingsBackup.summary(b)["llmModel"])
        assertEquals("empty", SettingsBackup.hashOf(""))
    }
}
