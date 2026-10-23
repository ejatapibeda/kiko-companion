package com.coactive.companion.assistant

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.coactive.companion.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coactive.companion.audio.Earcon
import com.coactive.companion.audio.EndpointDetector
import com.coactive.companion.audio.Level
import com.coactive.companion.audio.MicRecorder
import com.coactive.companion.audio.Wav
import com.coactive.companion.llm.OpenAiChatLlm
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.settings.SettingsRepository
import com.coactive.companion.settings.TtsProvider
import com.coactive.companion.spotify.LocalSpotifyController
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.NowPlayingPoller
import com.coactive.companion.spotify.ObservedSpotifyController
import com.coactive.companion.spotify.OfficialSpotifyController
import com.coactive.companion.spotify.PollVisibility
import com.coactive.companion.spotify.SessionCookies
import com.coactive.companion.spotify.SessionStatus
import com.coactive.companion.spotify.SpotapiClient
import com.coactive.companion.spotify.SpotapiSpotifyController
import com.coactive.companion.spotify.SpotifyAction
import com.coactive.companion.spotify.SpotifyAuth
import com.coactive.companion.spotify.SpotifyController
import com.coactive.companion.spotify.SpotifyControllerFactory
import com.coactive.companion.spotify.SpotifyResult
import com.coactive.companion.stt.OpenAiSpeechToText
import com.coactive.companion.audio.MicGain
import com.coactive.companion.audio.MicSensitivity
import com.coactive.companion.tools.DefaultTools
import com.coactive.companion.tts.AndroidSystemTts
import com.coactive.companion.tts.AudioPlayback
import com.coactive.companion.tts.AudioTtsEngine
import com.coactive.companion.tts.ElevenLabsTts
import com.coactive.companion.tts.FallbackTtsEngine
import com.coactive.companion.tts.FocusedTtsEngine
import com.coactive.companion.tts.OpenAiCompatTts
import com.coactive.companion.tts.TikTokTts
import com.coactive.companion.tts.TtsAudioCache
import com.coactive.companion.tts.TtsEngine
import com.coactive.companion.ui.face.Expression
import com.coactive.companion.wakeword.OnnxWakeModels
import com.coactive.companion.wakeword.WakeWordDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class UiState(
    val phase: Phase = Phase.IDLE,
    val emotion: Expression = Expression.NEUTRAL,
    val talkLevel: Float = 0f,
    val micLevel: Float = 0f,
    val caption: String = "",
    val userText: String = "",
    val error: String? = null,
    val wakeWordActive: Boolean = false,
    val needsSetup: Boolean = false,
    /** Reaksi sentuhan/goyangan terakhir (untuk animasi + haptik). */
    val reaction: com.coactive.companion.ui.face.ReactionEvent? = null,
    /** Tombol bantu Spotify saat tidak ada perangkat aktif. */
    val spotifyPrompt: SpotifyPrompt? = null,
    /** Reaksi cuaca sesekali (bersin/kedinginan/kepanasan). */
    val weatherEvent: com.coactive.companion.ui.face.WeatherEvent? = null,
) {
    val expression get() = FaceLogic.expressionFor(phase, emotion)
}

enum class SpotifyPrompt { OPEN_SPOTIFY, RETRY }

/** Status koneksi Spotify untuk layar Pengaturan. */
data class SpotifyStatus(
    val officialConnected: Boolean = false,
    val spotapiSession: SessionStatus? = null,
    val spotapiBusy: Boolean = false,
    val spotapiMessage: String? = null,
)

private const val WEATHER_REFRESH_MS = 20 * 60_000L

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    val repo = SettingsRepository(app)
    val settings: StateFlow<AppSettings> = repo.settings
    private val s get() = settings.value

    private val _ui = MutableStateFlow(UiState(needsSetup = s.llmApiKey.isBlank()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val memory = ConversationMemory()

    /** String resource sesuai bahasa aplikasi (dipakai teks yang dibuat di ViewModel). */
    private var strCtx: Pair<com.coactive.companion.settings.AppLanguage, android.content.Context>? = null
    /** Teks dari resource sesuai bahasa aplikasi yang dipilih (bukan locale sistem). */
    fun str(id: Int, vararg args: Any): String {
        val l = s.appLanguage
        val ctx = strCtx?.takeIf { it.first == l }?.second
            ?: com.coactive.companion.settings.AppLocale.localized(getApplication(), l).also { strCtx = l to it }
        return ctx.getString(id, *args)
    }

    /** Mode chat teks (riwayat permanen + lampiran), memakai persona & tool yang sama. */
    val chat by lazy {
        com.coactive.companion.chat.ChatController(getApplication(), viewModelScope, { s },
            { DefaultTools.create(getApplication(), { s }, ::spotify, ::setDance) }, { id, args -> str(id, *args) })
    }
    val spotifyAuth = SpotifyAuth(repo)
    private val localSpotify = LocalSpotifyController(app)
    private val officialSpotify = OfficialSpotifyController({ spotifyAuth.accessToken(s.spotifyClientId) })
    val spotapi = SpotapiClient({ s.spotapiBaseUrl }, { s.spotapiToken })
    private val spotapiSpotify = SpotapiSpotifyController(spotapi)
    private var pendingSpotifyRetry: (suspend () -> SpotifyResult<SpotifyAction>)? = null

    /** Implementasi sesuai Pengaturan (dipilih ulang tiap pemanggilan → ikut perubahan mode). */
    private fun rawSpotify(): SpotifyController = SpotifyControllerFactory.create(
        s, spotifyAuth.isAuthorized, spotapiReady = s.spotapiBaseUrl.isNotBlank() && s.spotapiToken.isNotBlank(),
        local = { localSpotify }, official = { officialSpotify }, spotapi = { spotapiSpotify },
    )

    fun spotify(): SpotifyController = ObservedSpotifyController(rawSpotify(),
        onActionDone = { intent ->
            poller.onActionPerformed(); _ui.update { it.copy(spotifyPrompt = null) }
            danceMode.onPlaybackAction(intent, rawSpotify().source); updateDance()
        },
        onNoActiveDevice = { retry -> pendingSpotifyRetry = retry; _ui.update { it.copy(spotifyPrompt = SpotifyPrompt.OPEN_SPOTIFY) } },
    )

    /** now-playing: 5 dtk di layar pemutar, 20 dtk di foreground, berhenti di background. */
    private val poller = NowPlayingPoller(viewModelScope, { rawSpotify().nowPlaying() })
    val nowPlaying: StateFlow<NowPlaying?> = poller.state
    private var foreground = false
    private var playerVisible = false
    private fun updatePollVisibility() = poller.setVisibility(when {
        !foreground -> PollVisibility.BACKGROUND
        playerVisible -> PollVisibility.PLAYER
        else -> PollVisibility.FOREGROUND
    })
    fun setPlayerVisible(v: Boolean) { playerVisible = v; updatePollVisibility() }

    // ----- mode joget (lihat DanceMode untuk pemicu per sumber Spotify) -----
    private val danceMode = DanceMode()
    private val _dancing = MutableStateFlow(false)
    /** true → wajah joget (sudah memperhitungkan toggle Pengaturan & fase asisten). */
    val dancing: StateFlow<Boolean> = _dancing.asStateFlow()
    private fun musicReason() = danceMode.reason(s.danceToMusic, rawSpotify().source, nowPlaying.value)
    private fun updateDance() { _manualDance.value = danceMode.manual; _dancing.value = !debugNoDance && danceMode.shouldDance(s.danceToMusic, rawSpotify().source, nowPlaying.value, _ui.value.phase) }

    /** Joget manual (menu / tool face_dance). @return false bila joget dimatikan di Pengaturan. */
    fun setDance(on: Boolean): Boolean {
        if (on && !s.danceToMusic) return false
        danceMode.setManual(on); updateDance(); return true
    }
    private val _manualDance = MutableStateFlow(false)
    /** Joget manual sedang aktif (label menu "Joget!" / "Berhenti joget"). */
    val manualDance: StateFlow<Boolean> = _manualDance.asStateFlow()
    fun toggleDance() {
        if (!setDance(!danceMode.manual)) _ui.update { it.copy(caption = str(R.string.vm_dance_disabled)) }
    }

    private val _spotifyStatus = MutableStateFlow(SpotifyStatus(officialConnected = spotifyAuth.isAuthorized))
    val spotifyStatus: StateFlow<SpotifyStatus> = _spotifyStatus.asStateFlow()
    private val player = AudioPlayback(app.cacheDir) { s.ttsVolume }
    init { TtsAudioCache.diskDir = java.io.File(app.cacheDir, "tts_cache") }
    private val androidTts = AndroidSystemTts(app, player, { Locale.forLanguageTag(s.language) }, { s.ttsVolume })

    private var lastInteraction = System.currentTimeMillis()
    private var turnJob: Job? = null
    private var micJob: Job? = null
    private var detector: WakeWordDetector? = null
    private var detectorModel: String? = null
    private var utterance = ArrayList<ShortArray>()
    private var endpoint: EndpointDetector? = null
    @Volatile private var listenStartedAt = 0L

    init {
        viewModelScope.launch {
            combine(ui.map { it.phase }.distinctUntilChanged(), nowPlaying, settings) { _, _, _ -> }.collect { updateDance() }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val st = _ui.value
                updateDance() // sesi musik Lokal bisa habis
                // sedang joget/ada musik → jangan tertidur
                if (musicReason() == null && FaceLogic.shouldSleep(st.phase, lastInteraction, System.currentTimeMillis(), s.sleepAfterSeconds)) {
                    _ui.update { it.copy(phase = Phase.SLEEPING, caption = "") }
                }
            }
        }
    }

    // ---------- interaksi UI ----------

    private val reactions = com.coactive.companion.ui.face.ReactionPicker()
    private var reactionSeq = 0L

    private fun react(r: com.coactive.companion.ui.face.Reaction?) {
        r ?: return
        _ui.update { it.copy(reaction = com.coactive.companion.ui.face.ReactionEvent(++reactionSeq, r)) }
    }

    private enum class Gesture { TAP, DOUBLE_TAP, LONG_PRESS }

    private fun onFaceGesture(g: Gesture) {
        val phase = _ui.value.phase
        touch()
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.SLEEPING -> _ui.update { it.copy(phase = Phase.IDLE, emotion = Expression.HAPPY) }.also { relaxEmotionLater() }
            Phase.TALKING, Phase.THINKING -> cancelTurn()
            Phase.IDLE -> react(when (g) {
                Gesture.TAP -> reactions.onTap(now)
                Gesture.DOUBLE_TAP -> reactions.onDoubleTap(now)
                Gesture.LONG_PRESS -> reactions.onLongPress(now)
            })
            else -> Unit
        }
    }

    /** Ketuk wajah: bangun dari tidur, memotong jawaban, atau reaksi lucu saat idle. */
    fun onFaceTapped() = onFaceGesture(Gesture.TAP)
    fun onFaceDoubleTapped() = onFaceGesture(Gesture.DOUBLE_TAP)
    fun onFaceLongPressed() = onFaceGesture(Gesture.LONG_PRESS)

    /** HP digoyang: pusing (juga membangunkan dari tidur). Diabaikan saat mendengarkan/berpikir/bicara. */
    fun onShake() {
        val phase = _ui.value.phase
        if (phase != Phase.IDLE && phase != Phase.SLEEPING) return
        val r = reactions.onShake(System.currentTimeMillis()) ?: return
        touch()
        react(r)
    }

    /** Tombol mic: mulai / selesai mendengarkan, atau memotong saat asisten bicara. */
    fun onMicPressed() {
        touch()
        when (_ui.value.phase) {
            Phase.LISTENING -> finishListening()
            Phase.TALKING, Phase.THINKING -> { cancelTurn(); startListening() }
            else -> startListening()
        }
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        touch()
        cancelTurn()
        turnJob = viewModelScope.launch { runTurn(t) }
    }

    fun onSettingsChanged() {
        _ui.update { it.copy(needsSetup = s.llmApiKey.isBlank()) }
        detector = null // model/threshold mungkin berubah
        if (micJob?.isActive == true && micEnhance != s.noiseSuppression) stopMic() // buka ulang mic dengan pra-pemrosesan baru
        refreshMic()
        poller.clear(); poller.refreshNow()
        if (s.weatherReactions) { if (foreground) startWeather() } else stopWeather()
    }

    fun onResume() { refreshMic(); foreground = true; updatePollVisibility(); startWeather() }
    fun onPause() { stopMic(); cancelTurn(); foreground = false; updatePollVisibility(); stopWeather() }

    // ---------- cuaca → ekspresi lucu sesekali ----------
    // Lokasi (izin COARSE, cadangan IP) → Open-Meteo tiap ~20 menit selama di foreground.
    // Saat idle, tiap 20–60 dtk ada peluang kecil memutar reaksi sesuai cuaca (hujan → bersin, dll.).

    private val weatherService = com.coactive.companion.weather.WeatherService(app)
    private val _weather = MutableStateFlow<com.coactive.companion.weather.WeatherNow?>(null)
    /** Cuaca terakhir (cache; tetap tersimpan saat aplikasi di background). */
    val weather: StateFlow<com.coactive.companion.weather.WeatherNow?> = _weather.asStateFlow()
    /** Debug saja: paksa suasana latar cuaca (null = ikuti cuaca asli). */
    private val _debugScene = MutableStateFlow<com.coactive.companion.weather.WeatherMood?>(null)
    /** Suasana cuaca aktif untuk efek latar ambient (null = tidak ada / dimatikan di Pengaturan). */
    val weatherScene: StateFlow<com.coactive.companion.weather.WeatherMood?> =
        combine(_weather, settings, _debugScene) { w, st, dbg -> if (!st.weatherReactions) null else dbg ?: w?.mood }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    private var weatherJob: Job? = null
    private var weatherSeq = 0L
    /** Debug saja: matikan joget sementara supaya reaksi cuaca bisa dilihat walau musik sedang diputar. */
    private var debugNoDance = false

    private fun startWeather() {
        if (!s.weatherReactions || weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            launch {
                while (isActive) {
                    val cached = _weather.value
                    if (cached == null || System.currentTimeMillis() - cached.at >= WEATHER_REFRESH_MS) {
                        weatherService.fetch()?.let { _weather.value = it }
                    }
                    delay(if (_weather.value == null) 2 * 60_000L else WEATHER_REFRESH_MS)
                }
            }
            while (isActive) {
                delay(kotlin.random.Random.nextLong(20_000, 60_001))
                val mood = _weather.value?.mood ?: continue
                if (kotlin.random.Random.nextFloat() > 0.55f) continue // tidak setiap kali → terasa "kadang-kadang"
                if (!weatherIdle()) continue
                Log.d(com.coactive.companion.weather.WeatherService.TAG, "reaksi cuaca: $mood")
                playWeather(mood)
            }
        }
    }

    private fun stopWeather() { weatherJob?.cancel(); weatherJob = null }

    /** Hanya saat benar-benar santai: idle, tidak joget, tidak di layar pemutar, aplikasi di depan. */
    private fun weatherIdle() = foreground && !playerVisible && !_dancing.value && _ui.value.phase == Phase.IDLE &&
        _ui.value.emotion == Expression.NEUTRAL

    private fun playWeather(mood: com.coactive.companion.weather.WeatherMood, holdAt: Float? = null) {
        val act = when (mood) {
            com.coactive.companion.weather.WeatherMood.RAIN -> com.coactive.companion.ui.face.WeatherAct.SNEEZE
            com.coactive.companion.weather.WeatherMood.COLD -> com.coactive.companion.ui.face.WeatherAct.SHIVER
            com.coactive.companion.weather.WeatherMood.HOT -> com.coactive.companion.ui.face.WeatherAct.OVERHEAT
        }
        _ui.update { it.copy(weatherEvent = com.coactive.companion.ui.face.WeatherEvent(++weatherSeq, act, holdAt)) }
    }

    /** Dipanggil setelah izin lokasi dijawab / pengaturan berubah → ambil ulang dengan lokasi terbaru. */
    fun refreshWeather() {
        _weather.value = null
        stopWeather()
        if (foreground) startWeather()
    }

    /** Khusus build debug (adb): paksa satu reaksi cuaca, opsional dibekukan di titik [holdAt] 0..1. */
    fun debugWeather(mood: com.coactive.companion.weather.WeatherMood, holdAt: Float?, noDance: Boolean) {
        Log.d(com.coactive.companion.weather.WeatherService.TAG, "debug reaksi cuaca: $mood hold=$holdAt noDance=$noDance")
        debugNoDance = noDance; updateDance()
        _debugScene.value = if (noDance) mood else null
        playWeather(mood, holdAt)
    }

    fun clearConversation() { memory.clear(); _ui.update { it.copy(caption = str(R.string.vm_conversation_cleared), userText = "") } }

    fun spotifyLoginUrl(): String? = s.spotifyClientId.takeIf { it.isNotBlank() }?.let { spotifyAuth.authorizeUrl(it) }

    fun handleSpotifyRedirect(uri: Uri) = viewModelScope.launch {
        val code = uri.getQueryParameter("code")
        val ok = code != null && runCatching { spotifyAuth.exchangeCode(code, uri.getQueryParameter("state"), s.spotifyClientId) }
            .onFailure { Log.w(TAG, "spotify token exchange failed", it) }.getOrDefault(false)
        _spotifyStatus.update { it.copy(officialConnected = spotifyAuth.isAuthorized) }
        _ui.update { it.copy(caption = if (ok) str(R.string.vm_spotify_connected) else str(R.string.vm_spotify_login_failed), emotion = if (ok) Expression.HAPPY else Expression.SAD) }
        relaxEmotionLater()
        if (ok) poller.refreshNow()
    }

    fun spotifyLogout() { spotifyAuth.logout(); _spotifyStatus.update { it.copy(officialConnected = false) }; poller.clear() }

    // ----- spotapi: auth app (pairing / token statis) + sesi Spotify (WebView) -----

    private suspend fun spotapiCall(msgOk: (SessionStatus) -> String?, block: suspend () -> SessionStatus?) {
        _spotifyStatus.update { it.copy(spotapiBusy = true, spotapiMessage = null) }
        val r = runCatching { block() }
        _spotifyStatus.update { st ->
            r.fold(
                { ss -> st.copy(spotapiBusy = false, spotapiSession = ss, spotapiMessage = ss?.let(msgOk)) },
                { e -> st.copy(spotapiBusy = false, spotapiMessage = spotapiErrorText(e)) },
            )
        }
        poller.refreshNow()
    }

    private fun spotapiErrorText(e: Throwable) = when (e) {
        is com.coactive.companion.spotify.SpotapiException -> SpotapiClient.toFailure(e).message
        is com.coactive.companion.spotify.SpotapiNotConfigured -> str(R.string.vm_spotapi_no_base)
        else -> str(R.string.vm_spotapi_unreachable, e.message.orEmpty())
    }

    /** Pairing: POST /auth/pair → app_token disimpan terenkripsi. Mengembalikan token untuk draf Pengaturan. */
    suspend fun pairSpotapi(draft: AppSettings, code: String): String? {
        repo.update { draft }
        _spotifyStatus.update { it.copy(spotapiBusy = true, spotapiMessage = null) }
        return runCatching { spotapi.pair(code, android.os.Build.MODEL ?: "Android") }.fold(
            { res ->
                repo.update { it.copy(spotapiToken = res.appToken) }
                _spotifyStatus.update { it.copy(spotapiBusy = false, spotapiMessage = str(R.string.vm_device_paired)) }
                refreshSpotapiSession()
                res.appToken
            },
            { e -> _spotifyStatus.update { it.copy(spotapiBusy = false, spotapiMessage = spotapiErrorText(e)) }; null },
        )
    }

    fun refreshSpotapiSession(draft: AppSettings? = null) = viewModelScope.launch {
        draft?.let { d -> repo.update { d } }
        spotapiCall({ null }) { spotapi.sessionStatus() }
    }

    /** Hasil login WebView → kirim SEKALI ke POST /spotify/session; cookie tidak disimpan. */
    fun connectSpotapiSession(cookies: SessionCookies) = viewModelScope.launch {
        spotapiCall({ if (it.connected) str(R.string.vm_spotify_account_connected) else str(R.string.vm_spotify_session_rejected) }) { spotapi.createSession(cookies) }
    }

    fun disconnectSpotapiSession() = viewModelScope.launch {
        spotapiCall({ str(R.string.vm_spotify_account_disconnected) }) { spotapi.deleteSession(); SessionStatus(connected = false) }
    }

    // ----- bantuan "tidak ada perangkat aktif" & kontrol layar pemutar -----

    fun openSpotifyApp() {
        LocalSpotifyController.openSpotifyApp(getApplication())
        _ui.update { it.copy(spotifyPrompt = if (pendingSpotifyRetry != null) SpotifyPrompt.RETRY else null) }
    }

    fun retrySpotify() {
        val retry = pendingSpotifyRetry ?: return
        pendingSpotifyRetry = null
        _ui.update { it.copy(spotifyPrompt = null) }
        viewModelScope.launch { showSpotifyResult(retry()) }
    }

    fun dismissSpotifyPrompt() { pendingSpotifyRetry = null; _ui.update { it.copy(spotifyPrompt = null) } }

    /** Lirik untuk layar pemutar (tanpa efek samping now-playing/dansa, jadi pakai controller mentah). */
    suspend fun lyrics(uri: String): com.coactive.companion.spotify.SpotifyResult<com.coactive.companion.spotify.LyricsResponse> =
        rawSpotify().lyrics(uri)

    fun playerAction(action: String) = viewModelScope.launch {
        touch()
        val c = spotify()
        val r = when (action) {
            "pause" -> c.pause(); "resume" -> c.resume(); "next" -> c.next(); "previous" -> c.previous()
            else -> return@launch
        }
        if (r is SpotifyResult.Failure) showSpotifyResult(r)
        else { delay(400); poller.refreshNow() } // ikon putar/jeda (pemutar & island) segera ikut status baru
    }

    private fun showSpotifyResult(r: SpotifyResult<SpotifyAction>) {
        val text = when (r) {
            is SpotifyResult.Ok -> r.value.track?.let { t -> str(R.string.vm_playing, t.title + (if (t.artists.isNotEmpty()) " — ${t.artistText}" else "")) } ?: str(R.string.vm_ok)
            is SpotifyResult.Failure -> r.message
        }
        _ui.update { it.copy(caption = text, emotion = if (r is SpotifyResult.Ok) Expression.HAPPY else Expression.SAD) }
        relaxEmotionLater()
    }

    // ---------- pipeline ----------

    private suspend fun runTurn(userText: String) {
        _ui.update { it.copy(phase = Phase.THINKING, userText = userText, caption = userText, error = null, spotifyPrompt = null) }
        pendingSpotifyRetry = null
        try {
            val brain = AssistantBrain(
                OpenAiChatLlm(s.llmBaseUrl, s.llmApiKey, s.llmModel), DefaultTools.create(getApplication(), { s }, ::spotify, ::setDance), memory,
                { AssistantBrain.defaultSystemPrompt(s.assistantName, s.language, s.defaultCity, Locale.forLanguageTag(s.language)) },
                onToolCall = { name -> Log.i(TAG, "tool call: $name") },
            )
            val reply = brain.respond(userText)
            Log.i(TAG, "reply emotion=${reply.emotion} chars=${reply.text.length} sources=${reply.sources.size}")
            val spoken = com.coactive.companion.search.SourceExtractor.stripUrlsForSpeech(reply.text).ifBlank { reply.text }
            _ui.update { it.copy(phase = Phase.TALKING, emotion = reply.emotion, caption = spoken) }
            buildTts().speak(spoken) { lvl -> _ui.update { it.copy(talkLevel = lvl) } }
            _ui.update { it.copy(phase = Phase.IDLE, talkLevel = 0f) }
            relaxEmotionLater()
        } catch (e: CancellationException) {
            _ui.update { it.copy(phase = Phase.IDLE, talkLevel = 0f) }
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "turn failed", e)
            _ui.update { it.copy(phase = Phase.IDLE, emotion = Expression.SAD, talkLevel = 0f, caption = friendlyError(e), error = e.message) }
            relaxEmotionLater(6000)
        } finally {
            touch()
        }
    }

    fun buildTts(): TtsEngine {
        val a = s
        val primary: TtsEngine = when (a.ttsProvider) {
            TtsProvider.TIKTOK -> AudioTtsEngine(TikTokTts(a.tiktokBaseUrl, a.tiktokVoice, a.tiktokSessionId), player)
            TtsProvider.ELEVENLABS -> AudioTtsEngine(ElevenLabsTts(a.elevenApiKey, a.elevenVoiceId, a.elevenModel, a.languageCode), player)
            TtsProvider.OPENAI -> AudioTtsEngine(OpenAiCompatTts(a.effectiveTtsBaseUrl, a.effectiveTtsApiKey, a.openAiTtsModel, a.openAiTtsVoice), player)
            TtsProvider.ANDROID -> return FocusedTtsEngine(getApplication(), androidTts)
        }
        return FocusedTtsEngine(getApplication(), FallbackTtsEngine(primary, androidTts) { Log.w(TAG, "TTS ${a.ttsProvider} gagal, pakai Android TTS", it) })
    }

    private fun startListening() {
        if (!hasMicPermission()) {
            _ui.update { it.copy(caption = str(R.string.vm_mic_permission), emotion = Expression.SAD) }
            return
        }
        utterance = ArrayList()
        endpoint = MicSensitivity.endpointDetector(s.micSensitivity)
        listenStartedAt = System.currentTimeMillis()
        if (s.earconEnabled) Earcon.play(viewModelScope, Earcon.START)
        _ui.update { it.copy(phase = Phase.LISTENING, caption = str(R.string.vm_listening), emotion = Expression.NEUTRAL, error = null) }
        ensureMic()
    }

    private fun finishListening() {
        val frames = utterance
        utterance = ArrayList()
        val speech = endpoint?.speechDetected == true
        if (com.coactive.companion.BuildConfig.DEBUG) {
            // DIAG sementara: level mikrofon per sesi dengar (tanpa data sensitif)
            val rmsList = frames.map { Level.rms(it) }
            val peakSample = frames.maxOfOrNull { f -> f.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0 } ?: 0
            Log.i(TAG, "MICDIAG frames=${frames.size} zeroFrames=${frames.count { f -> f.all { it.toInt() == 0 } }} " +
                "maxRms=${rmsList.maxOrNull()} avgRms=${if (rmsList.isEmpty()) 0f else rmsList.average()} peakSample=$peakSample " +
                "thr=${endpoint?.threshold()} speech=$speech")
        }
        endpoint = null
        _ui.update { it.copy(micLevel = 0f) }
        if (s.earconEnabled) Earcon.play(viewModelScope, Earcon.END, gain = 0.35f)
        if (!speech || frames.isEmpty()) {
            _ui.update { it.copy(phase = Phase.IDLE, caption = str(R.string.vm_heard_nothing), emotion = Expression.CURIOUS) }
            relaxEmotionLater(); refreshMic(); return
        }
        _ui.update { it.copy(phase = Phase.THINKING, caption = "…") }
        refreshMic()
        turnJob = viewModelScope.launch {
            try {
                val pcm = ShortArray(frames.sumOf { it.size }).also { out -> var o = 0; frames.forEach { f -> f.copyInto(out, o); o += f.size } }
                val text = OpenAiSpeechToText(s.effectiveSttBaseUrl, s.effectiveSttApiKey, s.sttModel)
                    .transcribe(Wav.fromPcm16(pcm), s.languageCode)
                if (text.isBlank()) {
                    _ui.update { it.copy(phase = Phase.IDLE, caption = str(R.string.vm_unclear), emotion = Expression.CURIOUS) }
                    relaxEmotionLater()
                } else runTurn(text)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                Log.w(TAG, "stt failed", e)
                _ui.update { it.copy(phase = Phase.IDLE, emotion = Expression.SAD, caption = friendlyError(e)) }
                relaxEmotionLater(6000)
            }
        }
    }

    private fun cancelTurn() {
        turnJob?.cancel(); turnJob = null
        androidTts.stop()
        if (_ui.value.phase == Phase.TALKING || _ui.value.phase == Phase.THINKING) _ui.update { it.copy(phase = Phase.IDLE, talkLevel = 0f) }
    }

    // ---------- mikrofon & wake word ----------

    private fun wantMic() = hasMicPermission() && (s.wakeWordEnabled || _ui.value.phase == Phase.LISTENING)

    private fun refreshMic() { if (wantMic()) ensureMic() else stopMic() }

    private fun ensureMic() {
        if (micJob?.isActive == true) return
        val enhance = s.noiseSuppression
        micEnhance = enhance
        micJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                MicRecorder.frames(enhance).collect { frame -> onMicFrame(frame) }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                Log.e(TAG, "mic error", e)
                _ui.update { it.copy(caption = str(R.string.vm_mic_error, e.message.orEmpty()), wakeWordActive = false) }
            }
        }
    }

    private var micEnhance = true

    private fun stopMic() { micJob?.cancel(); micJob = null; _ui.update { it.copy(wakeWordActive = false) } }

    private suspend fun onMicFrame(raw: ShortArray) {
        val phase = _ui.value.phase
        if (phase != Phase.LISTENING && phase != Phase.IDLE && phase != Phase.SLEEPING) return // abaikan saat berpikir/bicara
        // gain perangkat lunak sesuai "Sensitivitas mic" (dengan pelindung clipping); 0.5 = tanpa perubahan
        val frame = MicGain.apply(raw, MicSensitivity.gain(s.micSensitivity))
        when (phase) {
            Phase.LISTENING -> {
                // abaikan audio selama earcon berbunyi agar tidak terdeteksi sebagai ucapan
                if (s.earconEnabled && System.currentTimeMillis() - listenStartedAt < Earcon.durationMs(Earcon.START) + 120) return
                utterance.add(frame)
                val rms = Level.rms(frame)
                _ui.update { it.copy(micLevel = Level.mouthOpenness(rms)) }
                if (endpoint?.feed(rms) == true) withContext(Dispatchers.Main) { finishListening() }
            }
            Phase.IDLE, Phase.SLEEPING -> {
                if (!s.wakeWordEnabled) return
                val d = wakeDetector() ?: return
                if (d.process(frame)) {
                    Log.i(TAG, "wake word! score=${d.lastScore}")
                    withContext(Dispatchers.Main) { touch(); startListening() }
                }
            }
            else -> Unit // abaikan saat berpikir/bicara agar tidak memicu diri sendiri
        }
    }

    private fun wakeDetector(): WakeWordDetector? {
        val model = s.wakeWordModel
        if (detector == null || detectorModel != model) {
            detector = runCatching { WakeWordDetector(OnnxWakeModels.load(getApplication(), model), MicSensitivity.wakeThreshold(s.wakeWordThreshold, s.micSensitivity)) }
                .onFailure { Log.e(TAG, "gagal memuat wake word", it); _ui.update { st -> st.copy(caption = str(R.string.vm_wake_model_failed, it.message.orEmpty())) } }
                .getOrNull()
            detectorModel = model
            _ui.update { it.copy(wakeWordActive = detector != null) }
        }
        return detector
    }

    // ---------- util ----------

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun touch() {
        lastInteraction = System.currentTimeMillis()
        if (_ui.value.phase == Phase.SLEEPING) _ui.update { it.copy(phase = Phase.IDLE) }
    }

    private var relaxJob: Job? = null
    private fun relaxEmotionLater(ms: Long = 4000) {
        relaxJob?.cancel()
        relaxJob = viewModelScope.launch {
            delay(ms)
            if (_ui.value.phase == Phase.IDLE) _ui.update { it.copy(emotion = Expression.NEUTRAL) }
        }
    }

    private fun friendlyError(e: Throwable): String = when {
        e.message?.contains("API key") == true -> e.message!!
        e is com.coactive.companion.net.ApiException && e.code == 401 -> str(R.string.vm_key_rejected)
        e is java.net.UnknownHostException -> str(R.string.vm_offline)
        else -> str(R.string.vm_error_generic, e.message?.take(120).orEmpty())
    }

    override fun onCleared() { stopMic(); androidTts.shutdown() }

    companion object { const val TAG = "Companion" }
}
