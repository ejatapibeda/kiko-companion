package com.coactive.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.coactive.companion.assistant.AssistantViewModel
import com.coactive.companion.settings.ThemeMode
import com.coactive.companion.ui.CompanionTheme
import com.coactive.companion.ui.MainScreen
import com.coactive.companion.ui.PlayerScreen
import com.coactive.companion.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object { var localeSyncedOnce = false }
    private val vm: AssistantViewModel by viewModels()
    private val motion by lazy {
        com.coactive.companion.motion.DeviceMotion(this, rotation = {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }, onShake = { runOnUiThread { vm.onShake() } })
    }
    /** Login WebView (mode spotapi) → cookie hanya diteruskan sekali ke backend, tidak disimpan. */
    private val spotifyWebLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cookies = com.coactive.companion.spotify.SpotifyWebLoginActivity.fromResult(res.data)
        if (cookies != null) vm.connectSpotapiSession(cookies)
        else Toast.makeText(this, getString(R.string.toast_spotify_login_cancelled), Toast.LENGTH_SHORT).show()
    }
    /** Mic + lokasi (kasar, untuk cuaca) diminta sekaligus — dua dialog terpisah bisa saling membatalkan. */
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onResume()
        if (res.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)) vm.refreshWeather()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Minta izin yang belum ada; lokasi hanya bila reaksi cuaca aktif (ditolak pun tetap jalan via perkiraan IP). */
    private fun requestNeededPermissions() {
        val need = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (vm.settings.value.weatherReactions && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (need.isNotEmpty()) permissions.launch(need.toTypedArray())
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.coactive.companion.settings.AppLocale.wrap(newBase))
    }

    /** Selaraskan bahasa UI dengan pengaturan. Bila user mengganti lewat Pengaturan sistem → Bahasa aplikasi (API 33+), itu yang menang. */
    private fun syncLocale() {
        val s = vm.settings.value
        val sys = com.coactive.companion.settings.AppLocale.systemAppLanguage(this)
        if (sys != null && sys != s.appLanguage && s.languageChosen && localeSyncedOnce) {
            vm.repo.update { it.withAppLanguage(sys) }; vm.onSettingsChanged(); return
        }
        localeSyncedOnce = true
        com.coactive.companion.settings.AppLocale.apply(this, s.appLanguage)
    }

    private fun chooseLanguage(l: com.coactive.companion.settings.AppLanguage) {
        val old = vm.settings.value
        vm.repo.update { it.withAppLanguage(l).copy(languageChosen = true) }
        if (old.language != vm.settings.value.language) vm.onSettingsChanged()
        com.coactive.companion.settings.AppLocale.apply(this, l)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncLocale()
        enableEdgeToEdge()
        // seperti companion di dudukan: layar tetap menyala & immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestNeededPermissions()
        handleIntent(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val lean by motion.lean.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false
            }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showPlayer by rememberSaveable { mutableStateOf(false) }
            var showChat by rememberSaveable { mutableStateOf(false) }
            val spotifyStatus by vm.spotifyStatus.collectAsStateWithLifecycle()
            val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
            val dancing by vm.dancing.collectAsStateWithLifecycle()
            val manualDance by vm.manualDance.collectAsStateWithLifecycle()
            val weatherMood by vm.weatherScene.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(showPlayer && !showSettings && !showChat) { vm.setPlayerVisible(showPlayer && !showSettings && !showChat) }
            CompanionTheme(dark) {
                if (!settings.languageChosen) com.coactive.companion.ui.LanguageDialog(onChoose = ::chooseLanguage)
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        initial = settings,
                        spotifyStatus = spotifyStatus,
                        onSave = { new ->
                            val langChanged = new.appLanguage != settings.appLanguage
                            vm.repo.update { new }; vm.onSettingsChanged(); updateMotion(); showSettings = false
                            if (langChanged) com.coactive.companion.settings.AppLocale.apply(this, new.appLanguage)
                            if (new.weatherReactions && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) requestNeededPermissions()
                            Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
                        },
                        onBack = { showSettings = false },
                        onTestVoice = { draft ->
                            vm.repo.update { draft }
                            lifecycleScope.launch {
                                runCatching { vm.buildTts().speak(vm.str(R.string.tts_test_phrase)) {} }
                                    .onFailure { Toast.makeText(this@MainActivity, getString(R.string.toast_tts_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
                            }
                        },
                        onSpotifyLogin = { draft ->
                            vm.repo.update { draft }
                            vm.spotifyLoginUrl()?.let { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(it)) }
                        },
                        onSpotifyLogout = { vm.spotifyLogout() },
                        onSpotapiPair = { draft, code -> vm.pairSpotapi(draft, code) },
                        onSpotapiConnect = { draft ->
                            vm.repo.update { draft }
                            spotifyWebLogin.launch(Intent(this, com.coactive.companion.spotify.SpotifyWebLoginActivity::class.java))
                        },
                        onSpotapiDisconnect = { vm.disconnectSpotapiSession() },
                        manualDance = manualDance,
                        onToggleDance = vm::toggleDance,
                        onClearConversation = {
                            vm.clearConversation()
                            Toast.makeText(this, getString(R.string.vm_conversation_cleared), Toast.LENGTH_SHORT).show()
                        },
                        onSpotapiCheck = { draft -> vm.refreshSpotapiSession(draft) },
                    )
                } else if (showChat) {
                    BackHandler { showChat = false }
                    val msgs by vm.chat.messages.collectAsStateWithLifecycle()
                    val sending by vm.chat.sending.collectAsStateWithLifecycle()
                    val pending by vm.chat.pending.collectAsStateWithLifecycle()
                    val notice by vm.chat.notice.collectAsStateWithLifecycle()
                    com.coactive.companion.ui.ChatScreen(
                        messages = msgs, sending = sending, pending = pending, assistantName = settings.assistantName, dark = dark,
                        onSend = vm.chat::send, onPickImage = { vm.chat.attachImage(it) }, onPickText = { vm.chat.attachText(it) },
                        onRemovePending = vm.chat::removePending, onClear = { vm.chat.clearHistory() }, onBack = { showChat = false },
                        notice = notice, onNoticeShown = vm.chat::clearNotice,
                    )
                } else if (showPlayer) {
                    BackHandler { showPlayer = false }
                    PlayerScreen(
                        np = nowPlaying, dark = dark, message = ui.caption.takeIf { ui.emotion == com.coactive.companion.ui.face.Expression.SAD },
                        onAction = { vm.playerAction(it) }, onOpenSpotify = vm::openSpotifyApp, onBack = { showPlayer = false },
                        loadLyrics = vm::lyrics,
                    )
                } else {
                    MainScreen(
                        state = ui, dark = dark, showInput = settings.showTextInput, listeningStyle = settings.listeningStyle,
                        onSettings = { showSettings = true }, onMic = vm::onMicPressed, onSend = vm::sendText,
                        onFaceTap = vm::onFaceTapped, onFaceDoubleTap = vm::onFaceDoubleTapped, onFaceLongPress = vm::onFaceLongPressed,
                        haptics = settings.motionReactions, lean = if (settings.motionReactions) lean else 0f,
                        onClearChat = vm::clearConversation,
                        onToggleInput = { vm.repo.update { it.copy(showTextInput = !it.showTextInput) } },
                        nowPlaying = nowPlaying, onOpenPlayer = { showPlayer = true }, onPlayerAction = { vm.playerAction(it) }, onOpenChat = { showChat = true },
                        onOpenSpotify = vm::openSpotifyApp, onRetrySpotify = vm::retrySpotify, onDismissSpotify = vm::dismissSpotifyPrompt,
                        dancing = dancing, danceEnabled = settings.danceToMusic, manualDance = manualDance, onToggleDance = vm::toggleDance,
                        weatherScene = if (dancing) null else when (weatherMood) {
                            com.coactive.companion.weather.WeatherMood.RAIN -> com.coactive.companion.ui.face.WeatherScene.RAIN
                            com.coactive.companion.weather.WeatherMood.COLD -> com.coactive.companion.ui.face.WeatherScene.COLD
                            com.coactive.companion.weather.WeatherMood.HOT -> com.coactive.companion.ui.face.WeatherScene.HOT
                            null -> null
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // debug (adb): am start -n com.coactive.companion/.MainActivity --es debugWeather RAIN|COLD|HOT [--ef weatherHold 0.6] [--ez noDance true]
        if (BuildConfig.DEBUG) intent?.getStringExtra("debugWeather")?.let { name ->
            runCatching { com.coactive.companion.weather.WeatherMood.valueOf(name.uppercase()) }.getOrNull()?.let { mood ->
                val hold = if (intent.hasExtra("weatherHold")) intent.getFloatExtra("weatherHold", 0.5f) else null
                val noDance = intent.getBooleanExtra("noDance", false)
                window.decorView.postDelayed({ vm.debugWeather(mood, hold, noDance) }, 600)
            }
            intent.removeExtra("debugWeather")
        }
        // debug (adb) sinkron pengaturan antar perangkat — file di filesDir privat, tidak pernah dicetak ke log:
        //   am start -n com.coactive.companion/.MainActivity --ez debugExportSettings true  → files/settings_sync.json
        //   am start -n com.coactive.companion/.MainActivity --ez debugImportSettings true  → baca lalu HAPUS file itu
        if (BuildConfig.DEBUG && intent != null) {
            val f = java.io.File(filesDir, "settings_sync.json")
            if (intent.getBooleanExtra("debugExportSettings", false)) {
                intent.removeExtra("debugExportSettings")
                runCatching { f.writeText(vm.repo.exportJson()); f.setReadable(false, false); f.setReadable(true, true) }
                    .onSuccess { android.util.Log.i("SettingsSync", "export ok") }.onFailure { android.util.Log.w("SettingsSync", "export failed: ${it.javaClass.simpleName}") }
            }
            if (intent.getBooleanExtra("debugImportSettings", false)) {
                intent.removeExtra("debugImportSettings")
                runCatching { vm.repo.importJson(f.readText()) }
                    .onSuccess { n -> android.util.Log.i("SettingsSync", "import ok: $n keys") }.onFailure { android.util.Log.w("SettingsSync", "import failed: ${it.javaClass.simpleName}") }
                f.delete()
                window.decorView.post { recreate() }
                return
            }
        }
        val data = intent?.data ?: return
        if (data.scheme == com.coactive.companion.spotify.SpotifyAuth.REDIRECT_SCHEME && data.host == com.coactive.companion.spotify.SpotifyAuth.REDIRECT_HOST) vm.handleSpotifyRedirect(data)
    }

    /** Sensor hanya aktif di foreground & bila diaktifkan di Pengaturan. */
    private fun updateMotion() {
        if (vm.settings.value.motionReactions && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) motion.start()
        else motion.stop()
    }

    override fun onResume() { super.onResume(); vm.onResume(); updateMotion() }
    override fun onPause() { super.onPause(); motion.stop(); vm.onPause() }
}
