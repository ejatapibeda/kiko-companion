package com.coactive.companion.settings

import com.coactive.companion.search.WebSearchProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Cadangan pengaturan (ekspor/impor) sebagai JSON berversi, untuk pindah HP.
 *
 * File prefs terenkripsi tidak bisa disalin mentah (kuncinya terikat Android Keystore tiap perangkat), jadi
 * semua field [AppSettings] ditulis eksplisit (nama field = kunci JSON, aman terhadap R8). File ini BERISI API key
 * & token: jangan pernah dicetak/di-log. Format:
 * ```
 * { "format": "kiko-settings", "version": 2, "appVersion": "1.0.0", "exportedAt": 0,
 *   "settings": { "llmModel": "...", ... }, "locale": "id", "spotify": { "access": "", "refresh": "", "expiresAt": 0 } }
 * ```
 * Impor juga menerima format lama v1 (`{"v":1,"prefs":{k:{"t":..,"v":..}}}`) dari hook debug sebelumnya.
 */
object SettingsBackup {
    const val FORMAT = "kiko-settings"
    const val VERSION = 2
    const val FILE_NAME = "kiko-settings.json"

    data class SpotifyTokens(val access: String, val refresh: String, val expiresAt: Long) {
        val isEmpty get() = access.isBlank() && refresh.isBlank()
    }

    data class Backup(
        val settings: AppSettings,
        /** Tag bahasa UI tersimpan (AppLocale), null bila belum pernah dipilih. */
        val localeTag: String? = null,
        val spotify: SpotifyTokens? = null,
        val appVersion: String = "",
        /** Jumlah field pengaturan yang benar-benar ada di file (sisanya default). */
        val fieldCount: Int = 0,
    )

    /** Field berisi rahasia (API key, cookie, app_token). */
    val SECRET_FIELDS = setOf(
        "llmApiKey", "sttApiKey", "tiktokSessionId", "elevenApiKey", "openAiTtsApiKey", "exaApiKey",
        "tavilyApiKey", "braveApiKey", "langsearchApiKey", "serperApiKey", "spotapiToken",
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Semua field sebagai nilai JSON primitif (urutan = urutan di data class). */
    fun fields(a: AppSettings): LinkedHashMap<String, JsonPrimitive> = linkedMapOf(
        "llmBaseUrl" to p(a.llmBaseUrl), "llmApiKey" to p(a.llmApiKey), "llmModel" to p(a.llmModel), "visionModel" to p(a.visionModel),
        "sttBaseUrl" to p(a.sttBaseUrl), "sttApiKey" to p(a.sttApiKey), "sttModel" to p(a.sttModel),
        "ttsProvider" to p(a.ttsProvider.name), "tiktokBaseUrl" to p(a.tiktokBaseUrl), "tiktokVoice" to p(a.tiktokVoice),
        "tiktokSessionId" to p(a.tiktokSessionId), "elevenApiKey" to p(a.elevenApiKey), "elevenVoiceId" to p(a.elevenVoiceId),
        "elevenModel" to p(a.elevenModel), "openAiTtsBaseUrl" to p(a.openAiTtsBaseUrl), "openAiTtsApiKey" to p(a.openAiTtsApiKey),
        "openAiTtsModel" to p(a.openAiTtsModel), "openAiTtsVoice" to p(a.openAiTtsVoice), "ttsVolume" to JsonPrimitive(a.ttsVolume),
        "language" to p(a.language), "appLanguage" to p(a.appLanguage.name), "languageChosen" to JsonPrimitive(a.languageChosen),
        "assistantName" to p(a.assistantName), "defaultCity" to p(a.defaultCity),
        "wakeWordEnabled" to JsonPrimitive(a.wakeWordEnabled), "wakeWordModel" to p(a.wakeWordModel),
        "wakeWordThreshold" to JsonPrimitive(a.wakeWordThreshold), "listeningStyle" to p(a.listeningStyle.name),
        "earconEnabled" to JsonPrimitive(a.earconEnabled), "noiseSuppression" to JsonPrimitive(a.noiseSuppression),
        "micSensitivity" to JsonPrimitive(a.micSensitivity), "motionReactions" to JsonPrimitive(a.motionReactions),
        "danceToMusic" to JsonPrimitive(a.danceToMusic), "weatherReactions" to JsonPrimitive(a.weatherReactions),
        "themeMode" to p(a.themeMode.name), "showTextInput" to JsonPrimitive(a.showTextInput),
        "sleepAfterSeconds" to JsonPrimitive(a.sleepAfterSeconds),
        "exaApiKey" to p(a.exaApiKey), "exaBaseUrl" to p(a.exaBaseUrl), "webSearchProvider" to p(a.webSearchProvider.name),
        "tavilyApiKey" to p(a.tavilyApiKey), "braveApiKey" to p(a.braveApiKey), "langsearchApiKey" to p(a.langsearchApiKey),
        "serperApiKey" to p(a.serperApiKey), "searxngUrl" to p(a.searxngUrl),
        "spotifySource" to p(a.spotifySource.name), "spotifyClientId" to p(a.spotifyClientId), "spotapiBaseUrl" to p(a.spotapiBaseUrl),
        "spotapiAuthMode" to p(a.spotapiAuthMode.name), "spotapiToken" to p(a.spotapiToken),
    )

    val FIELD_NAMES: Set<String> get() = fields(AppSettings()).keys

    fun encode(b: Backup, exportedAtMs: Long = System.currentTimeMillis()): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", FORMAT); put("version", VERSION); put("appVersion", b.appVersion); put("exportedAt", exportedAtMs)
        put("settings", JsonObject(fields(b.settings)))
        b.localeTag?.let { put("locale", it) }
        b.spotify?.takeUnless { it.isEmpty }?.let { t ->
            put("spotify", buildJsonObject { put("access", t.access); put("refresh", t.refresh); put("expiresAt", t.expiresAt) })
        }
    })

    /** Baca cadangan. Melempar [IllegalArgumentException] bila bukan file pengaturan Kiko. */
    fun decode(text: String): Backup {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IllegalArgumentException("not JSON") }
        val m: Map<String, JsonPrimitive> = when {
            root["format"]?.jsonPrimitive?.contentOrNull == FORMAT -> {
                val v = root["version"]?.jsonPrimitive?.intOrNull ?: 0
                require(v in 2..VERSION) { "unsupported version $v" }
                (root["settings"] as? JsonObject ?: throw IllegalArgumentException("no settings")).primitives()
            }
            root["prefs"] is JsonObject -> (root["prefs"] as JsonObject).mapNotNull { (k, item) ->
                ((item as? JsonObject)?.get("v") as? JsonPrimitive)?.let { k to it }
            }.toMap()
            else -> throw IllegalArgumentException("not a Kiko settings file")
        }
        val spotify = (root["spotify"] as? JsonObject)?.let { o ->
            SpotifyTokens(o.str("access") ?: "", o.str("refresh") ?: "", (o["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
        return Backup(
            settings = fromFields(m), localeTag = root.str("locale"), spotify = spotify,
            appVersion = root.str("appVersion") ?: "", fieldCount = m.keys.count { it in FIELD_NAMES },
        )
    }

    /** Bangun [AppSettings] dari peta field; field yang hilang/tidak valid memakai default. */
    fun fromFields(m: Map<String, JsonPrimitive>): AppSettings {
        val d = AppSettings()
        fun s(k: String, def: String) = m[k]?.takeIf { it.isString }?.content ?: def
        fun b(k: String, def: Boolean) = m[k]?.booleanOrNull ?: def
        fun f(k: String, def: Float) = m[k]?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() } ?: def
        fun i(k: String, def: Int) = m[k]?.intOrNull ?: def
        fun <E : Enum<E>> e(k: String, def: E, values: Array<E>) = m[k]?.contentOrNull?.let { n -> values.firstOrNull { it.name == n } } ?: def
        return AppSettings(
            llmBaseUrl = s("llmBaseUrl", d.llmBaseUrl), llmApiKey = s("llmApiKey", d.llmApiKey), llmModel = s("llmModel", d.llmModel),
            visionModel = s("visionModel", d.visionModel), sttBaseUrl = s("sttBaseUrl", d.sttBaseUrl), sttApiKey = s("sttApiKey", d.sttApiKey),
            sttModel = s("sttModel", d.sttModel), ttsProvider = e("ttsProvider", d.ttsProvider, TtsProvider.values()),
            tiktokBaseUrl = s("tiktokBaseUrl", d.tiktokBaseUrl), tiktokVoice = s("tiktokVoice", d.tiktokVoice),
            tiktokSessionId = s("tiktokSessionId", d.tiktokSessionId), elevenApiKey = s("elevenApiKey", d.elevenApiKey),
            elevenVoiceId = s("elevenVoiceId", d.elevenVoiceId), elevenModel = s("elevenModel", d.elevenModel),
            openAiTtsBaseUrl = s("openAiTtsBaseUrl", d.openAiTtsBaseUrl), openAiTtsApiKey = s("openAiTtsApiKey", d.openAiTtsApiKey),
            openAiTtsModel = s("openAiTtsModel", d.openAiTtsModel), openAiTtsVoice = s("openAiTtsVoice", d.openAiTtsVoice),
            ttsVolume = f("ttsVolume", d.ttsVolume).coerceIn(0f, 1f), language = s("language", d.language),
            appLanguage = e("appLanguage", AppLanguage.fromTag(m["language"]?.contentOrNull) ?: d.appLanguage, AppLanguage.values()),
            languageChosen = b("languageChosen", m.containsKey("language") || d.languageChosen),
            assistantName = s("assistantName", d.assistantName), defaultCity = s("defaultCity", d.defaultCity),
            wakeWordEnabled = b("wakeWordEnabled", d.wakeWordEnabled), wakeWordModel = s("wakeWordModel", d.wakeWordModel),
            wakeWordThreshold = f("wakeWordThreshold", d.wakeWordThreshold), listeningStyle = e("listeningStyle", d.listeningStyle, ListeningStyle.values()),
            earconEnabled = b("earconEnabled", d.earconEnabled), noiseSuppression = b("noiseSuppression", d.noiseSuppression),
            micSensitivity = com.coactive.companion.audio.MicSensitivity.sanitize(f("micSensitivity", d.micSensitivity)),
            motionReactions = b("motionReactions", d.motionReactions), danceToMusic = b("danceToMusic", d.danceToMusic),
            weatherReactions = b("weatherReactions", d.weatherReactions), themeMode = e("themeMode", d.themeMode, ThemeMode.values()),
            showTextInput = b("showTextInput", d.showTextInput), sleepAfterSeconds = i("sleepAfterSeconds", d.sleepAfterSeconds),
            exaApiKey = s("exaApiKey", d.exaApiKey), exaBaseUrl = s("exaBaseUrl", d.exaBaseUrl),
            webSearchProvider = e("webSearchProvider", d.webSearchProvider, WebSearchProvider.values()),
            tavilyApiKey = s("tavilyApiKey", d.tavilyApiKey), braveApiKey = s("braveApiKey", d.braveApiKey),
            langsearchApiKey = s("langsearchApiKey", d.langsearchApiKey), serperApiKey = s("serperApiKey", d.serperApiKey),
            searxngUrl = s("searxngUrl", d.searxngUrl), spotifySource = e("spotifySource", d.spotifySource, SpotifySource.values()),
            spotifyClientId = s("spotifyClientId", d.spotifyClientId), spotapiBaseUrl = s("spotapiBaseUrl", d.spotapiBaseUrl),
            spotapiAuthMode = e("spotapiAuthMode", d.spotapiAuthMode, SpotapiAuthMode.values()), spotapiToken = s("spotapiToken", d.spotapiToken),
        )
    }

    /**
     * Ringkasan aman untuk membandingkan dua perangkat: nilai non-rahasia apa adanya, rahasia hanya sebagai
     * `sha256:<hex>` (atau `empty`). Dipakai hook debug; tidak pernah berisi nilai rahasia.
     */
    fun summary(b: Backup): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in fields(b.settings)) out[k] = if (k in SECRET_FIELDS) hashOf(v.content) else v.content
        out["_locale"] = b.localeTag ?: ""
        out["_spotifyAccess"] = hashOf(b.spotify?.access ?: "")
        out["_spotifyRefresh"] = hashOf(b.spotify?.refresh ?: "")
        return out
    }

    fun summaryJson(b: Backup): String = json.encodeToString(JsonObject.serializer(), JsonObject(summary(b).mapValues { JsonPrimitive(it.value) }))

    fun hashOf(secret: String): String = if (secret.isEmpty()) "empty" else
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun p(s: String) = JsonPrimitive(s)
    private fun JsonObject.primitives(): Map<String, JsonPrimitive> = mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it } }.toMap()
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
