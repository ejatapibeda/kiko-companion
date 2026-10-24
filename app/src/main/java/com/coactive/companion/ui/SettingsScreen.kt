package com.coactive.companion.ui

import androidx.compose.material.icons.filled.Translate
import com.coactive.companion.settings.AppLanguage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.coactive.companion.search.WebSearchProvider as P
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.coactive.companion.R
import com.coactive.companion.assistant.SpotifyStatus
import com.coactive.companion.settings.AppSettings
import com.coactive.companion.settings.ListeningStyle
import com.coactive.companion.settings.SpotapiAuthMode
import com.coactive.companion.settings.SpotifySource
import com.coactive.companion.settings.ThemeMode
import com.coactive.companion.settings.TtsProvider
import com.coactive.companion.spotify.SpotifyAuth
import kotlinx.coroutines.launch

/**
 * Layar Pengaturan: satu kartu per bagian (ikon + judul), kolom teknis di balik "Lanjutan" (terbuka otomatis
 * bila nilainya sudah diubah dari default), sakelar sebagai baris rapi dengan pemisah.
 * Semua warna dari MaterialTheme.colorScheme → aman untuk tema terang & gelap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    initial: AppSettings,
    spotifyStatus: SpotifyStatus,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onTestVoice: (AppSettings) -> Unit,
    onSpotifyLogin: (AppSettings) -> Unit,
    onSpotifyLogout: () -> Unit,
    onSpotapiPair: suspend (AppSettings, String) -> String? = { _, _ -> null },
    onSpotapiConnect: (AppSettings) -> Unit = {},
    onSpotapiDisconnect: () -> Unit = {},
    onSpotapiCheck: (AppSettings) -> Unit = {},
    /** Aksi cepat (dulu di menu "..." layar utama). */
    manualDance: Boolean = false,
    onToggleDance: () -> Unit = {},
    onClearConversation: () -> Unit = {},
    /** Cadangkan & pulihkan (SAF): tulis/baca file JSON pengaturan. */
    onExportSettings: () -> Unit = {},
    /** Dipanggil setelah pengguna mengonfirmasi impor (lalu MainActivity membuka pemilih file). */
    onImportSettings: () -> Unit = {},
) {
    var d by remember { mutableStateOf(initial) }
    val def = remember { AppSettings() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val cs = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    TextButton(onClick = { onSave(d) }, modifier = Modifier.padding(end = 4.dp).testTag("save")) {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background, scrolledContainerColor = cs.surface),
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsCard(stringResource(R.string.settings_quick_title), Icons.Filled.Bolt) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("quickActions")) {
                        Button(onClick = onToggleDance, enabled = initial.danceToMusic, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("quickDance")) {
                            Text(stringResource(if (manualDance) R.string.menu_stop_dance else R.string.menu_dance))
                        }
                        OutlinedButton(onClick = onClearConversation, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("quickClearConversation")) {
                            Text(stringResource(R.string.menu_clear_conversation))
                        }
                    }
                    Text(stringResource(if (initial.danceToMusic) R.string.settings_quick_hint else R.string.settings_quick_hint_dance_off),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }

                SettingsCard(stringResource(R.string.settings_language_title), Icons.Filled.Translate) {
                    Box(Modifier.testTag("languagePicker")) {
                        Chips(AppLanguage.entries, d.appLanguage, { stringResource(it.labelRes) }) { d = d.withAppLanguage(it).copy(languageChosen = true) }
                    }
                    Hint(stringResource(R.string.settings_language_hint))
                }

                SettingsCard(stringResource(R.string.settings_llm_title), Icons.Filled.Memory) {
                    Hint(stringResource(R.string.settings_llm_hint))
                    Field("Base URL", d.llmBaseUrl, "llmBaseUrl") { d = d.copy(llmBaseUrl = it) }
                    Field("API key", d.llmApiKey, "llmApiKey", secret = true) { d = d.copy(llmApiKey = it) }
                    Field(stringResource(R.string.settings_model), d.llmModel, "llmModel") { d = d.copy(llmModel = it) }
                    Hint(stringResource(R.string.settings_vision_hint))
                    Advanced(initiallyOpen = d.visionModel.isNotBlank()) {
                        Field(stringResource(R.string.settings_vision_model), d.visionModel, "visionModel") { d = d.copy(visionModel = it.trim()) }
                    }
                }

                SettingsCard(stringResource(R.string.settings_stt_title), Icons.Filled.Mic) {
                    Field(stringResource(R.string.settings_stt_model), d.sttModel, "sttModel") { d = d.copy(sttModel = it) }
                    CardDivider()
                    Toggle(stringResource(R.string.settings_noise_suppression), d.noiseSuppression, "noiseSuppressionToggle") { d = d.copy(noiseSuppression = it) }
                    Hint(stringResource(R.string.settings_noise_suppression_hint))
                    CardDivider()
                    SensitivitySlider(d.micSensitivity, "micSensitivitySlider") { d = d.copy(micSensitivity = it) }
                    Hint(stringResource(R.string.settings_mic_sensitivity_hint))
                    Advanced(initiallyOpen = d.sttBaseUrl.isNotBlank() || d.sttApiKey.isNotBlank()) {
                        Hint(stringResource(R.string.settings_stt_hint))
                        Field(stringResource(R.string.settings_stt_base), d.sttBaseUrl, "sttBaseUrl") { d = d.copy(sttBaseUrl = it) }
                        Field(stringResource(R.string.settings_stt_key), d.sttApiKey, "sttApiKey", secret = true) { d = d.copy(sttApiKey = it) }
                    }
                }

                SettingsCard(stringResource(R.string.settings_tts_title), Icons.AutoMirrored.Filled.VolumeUp) {
                    Chips(TtsProvider.entries, d.ttsProvider, { stringResource(it.labelRes) }) { d = d.copy(ttsProvider = it) }
                    when (d.ttsProvider) {
                        TtsProvider.TIKTOK -> {
                            Field(stringResource(R.string.settings_tiktok_voice), d.tiktokVoice, "tiktokVoice") { d = d.copy(tiktokVoice = it) }
                            Hint(stringResource(R.string.settings_tiktok_hint))
                            Advanced(initiallyOpen = d.tiktokBaseUrl != def.tiktokBaseUrl || d.tiktokSessionId.isNotBlank()) {
                                Field(stringResource(R.string.settings_tiktok_base), d.tiktokBaseUrl, "tiktokBaseUrl") { d = d.copy(tiktokBaseUrl = it) }
                                Field(stringResource(R.string.settings_tiktok_session), d.tiktokSessionId, "tiktokSessionId", secret = true) { d = d.copy(tiktokSessionId = it) }
                            }
                        }
                        TtsProvider.ELEVENLABS -> {
                            Field(stringResource(R.string.settings_eleven_key), d.elevenApiKey, "elevenApiKey", secret = true) { d = d.copy(elevenApiKey = it) }
                            Field("Voice ID", d.elevenVoiceId, "elevenVoiceId") { d = d.copy(elevenVoiceId = it) }
                            Field(stringResource(R.string.settings_model), d.elevenModel, "elevenModel") { d = d.copy(elevenModel = it) }
                            Hint(stringResource(R.string.settings_eleven_hint))
                        }
                        TtsProvider.OPENAI -> {
                            Field(stringResource(R.string.settings_tts_base), d.openAiTtsBaseUrl, "openAiTtsBaseUrl") { d = d.copy(openAiTtsBaseUrl = it) }
                            Field(stringResource(R.string.settings_tts_key), d.openAiTtsApiKey, "openAiTtsApiKey", secret = true) { d = d.copy(openAiTtsApiKey = it) }
                            Field(stringResource(R.string.settings_tts_model), d.openAiTtsModel, "openAiTtsModel") { d = d.copy(openAiTtsModel = it) }
                            Field("Voice", d.openAiTtsVoice, "openAiTtsVoice") { d = d.copy(openAiTtsVoice = it) }
                        }
                        TtsProvider.ANDROID -> Hint(stringResource(R.string.settings_android_tts_hint))
                    }
                    CardDivider()
                    Field(stringResource(R.string.settings_tts_volume), d.ttsVolume.toString(), "ttsVolume", number = true) {
                        d = d.copy(ttsVolume = it.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: d.ttsVolume)
                    }
                    Hint(stringResource(R.string.settings_tts_volume_hint))
                    Hint(stringResource(R.string.settings_tts_fallback_hint))
                    OutlinedButton(onClick = { onTestVoice(d) }, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("testVoice")) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.settings_test_voice))
                    }
                }

                SettingsCard(stringResource(R.string.settings_assistant_title), Icons.Filled.Person) {
                    Field(stringResource(R.string.settings_assistant_name), d.assistantName, "assistantName") { d = d.copy(assistantName = it) }
                    Field(stringResource(R.string.settings_language_code), d.language, "language") { d = d.copy(language = it) }
                    Field(stringResource(R.string.settings_default_city), d.defaultCity, "defaultCity") { d = d.copy(defaultCity = it) }
                    Field(stringResource(R.string.settings_sleep_after), d.sleepAfterSeconds.toString(), "sleepAfter", number = true) {
                        d = d.copy(sleepAfterSeconds = it.filter(Char::isDigit).toIntOrNull() ?: 0)
                    }
                }

                SettingsCard("Wake word (on-device, openWakeWord)", Icons.Filled.Hearing) {
                    Toggle(stringResource(R.string.settings_wake_enable), d.wakeWordEnabled, "wakeToggle") { d = d.copy(wakeWordEnabled = it) }
                    Advanced(initiallyOpen = d.wakeWordModel != def.wakeWordModel || d.wakeWordThreshold != def.wakeWordThreshold) {
                        Field(stringResource(R.string.settings_wake_model), d.wakeWordModel, "wakeWordModel") { d = d.copy(wakeWordModel = it) }
                        Hint(stringResource(R.string.settings_wake_hint))
                        Field(stringResource(R.string.settings_wake_threshold), d.wakeWordThreshold.toString(), "wakeThreshold", number = true) {
                            d = d.copy(wakeWordThreshold = it.toFloatOrNull()?.coerceIn(0.05f, 0.99f) ?: d.wakeWordThreshold)
                        }
                    }
                }

                SettingsCard(stringResource(R.string.settings_display_title), Icons.Filled.Palette) {
                    Chips(ThemeMode.entries, d.themeMode, { stringResource(it.labelRes) }) { d = d.copy(themeMode = it) }
                    Chips(ListeningStyle.entries, d.listeningStyle, { stringResource(it.labelRes) }) { d = d.copy(listeningStyle = it) }
                    CardDivider()
                    Toggle(stringResource(R.string.settings_show_input), d.showTextInput, "inputToggle") { d = d.copy(showTextInput = it) }
                    CardDivider()
                    Toggle(stringResource(R.string.settings_earcon), d.earconEnabled, "earconToggle") { d = d.copy(earconEnabled = it) }
                    CardDivider()
                    Toggle(stringResource(R.string.settings_motion), d.motionReactions, "motionToggle") { d = d.copy(motionReactions = it) }
                    CardDivider()
                    Toggle(stringResource(R.string.settings_dance), d.danceToMusic, "danceToggle") { d = d.copy(danceToMusic = it) }
                    CardDivider()
                    Toggle(stringResource(R.string.settings_weather), d.weatherReactions, "weatherToggle") { d = d.copy(weatherReactions = it) }
                }

                SettingsCard(stringResource(R.string.settings_search_title), Icons.Filled.Search) {
                    WebSearchSettingsSection(d, def) { d = it }
                }

                SpotifySettingsSection(d, { d = it }, spotifyStatus, onSpotifyLogin, onSpotifyLogout, onSpotapiPair, onSpotapiConnect, onSpotapiDisconnect, onSpotapiCheck)

                BackupCard(onExportSettings, onImportSettings)

                AboutCard()

                Button(onClick = { onSave(d) }, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("saveBottom")) {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.navigationBarsPadding().height(16.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Komponen kecil                                                     */
/* ------------------------------------------------------------------ */

/** Ekspor/impor semua pengaturan ke file JSON (pindah HP). File berisi API key → peringatan jelas. */
@Composable
private fun BackupCard(onExport: () -> Unit, onImport: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    SettingsCard(stringResource(R.string.backup_title), Icons.Filled.SettingsBackupRestore) {
        Text(stringResource(R.string.backup_desc), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).testTag("backupExport")) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.weight(1f).testTag("backupImport")) { Text(stringResource(R.string.backup_import)) }
        }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.backup_import_confirm_title)) },
        text = { Text(stringResource(R.string.backup_import_confirm_body)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; onImport() }) { Text(stringResource(R.string.backup_import)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Versi aplikasi + tautan ke kode sumber & halaman rilis (unduh APK). */
@Composable
private fun AboutCard() {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    SettingsCard(stringResource(R.string.settings_about_title), Icons.Filled.Info) {
        val build = if (com.coactive.companion.BuildConfig.DEBUG) " · " + stringResource(R.string.settings_about_debug) else ""
        Text(stringResource(R.string.settings_about_version, com.coactive.companion.BuildConfig.VERSION_NAME, com.coactive.companion.BuildConfig.VERSION_CODE) + build,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("appVersion"))
        Text(stringResource(R.string.settings_about_releases), style = MaterialTheme.typography.labelLarge, color = cs.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(RELEASES_URL) } }.padding(vertical = 4.dp))
    }
}

private const val RELEASES_URL = "https://github.com/ejatapibeda/kiko-companion/releases"

/** Kartu satu bagian: ikon dalam lingkaran berwarna aksen + judul, lalu isi dengan jarak seragam. */
@Composable
private fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface, contentColor = cs.onSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/** Baris "Lanjutan" yang bisa dibuka-tutup (chevron berputar) untuk kolom teknis. */
@Composable
private fun Advanced(initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_advanced), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            Icon(Icons.Filled.ExpandMore, if (open) stringResource(R.string.collapse) else stringResource(R.string.expand), tint = cs.primary, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable private fun CardDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

@Composable private fun SubLabel(t: String) =
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun StatusText(ok: Boolean, yes: String, no: String) =
    Text(if (ok) "$yes ✓" else no, style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (ok) FontWeight.Medium else FontWeight.Normal,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun Hint(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Field(label: String, value: String, tag: String, secret: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { secret -> KeyboardType.Password; number -> KeyboardType.Decimal; else -> KeyboardType.Uri }),
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (reveal) stringResource(R.string.hide) else stringResource(R.string.show))
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(value, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun SensitivitySlider(value: Float, tag: String, onChange: (Float) -> Unit) {
    val v = com.coactive.companion.audio.MicSensitivity.sanitize(value)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_mic_sensitivity), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${com.coactive.companion.audio.MicSensitivity.percent(v)}%", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = v, onValueChange = { onChange((it * 20f).let(Math::round) / 20f) }, valueRange = 0f..1f, steps = 19,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_mic_sensitivity_low), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_mic_sensitivity_high), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(items: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        items.forEach {
            FilterChip(
                selected = it == selected, onClick = { onSelect(it) }, label = { Text(label(it)) },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = cs.primary.copy(alpha = 0.16f), selectedLabelColor = cs.primary,
                    selectedLeadingIconColor = cs.primary,
                ),
            )
        }
    }
}

/** Bagian "Pencarian web": pilih penyedia; hanya kolom yang relevan yang tampil. Key tersimpan terenkripsi. */
@Composable
fun WebSearchSettingsSection(d: AppSettings, def: AppSettings, onChange: (AppSettings) -> Unit) {

    Hint(stringResource(R.string.settings_search_hint))
    Box(Modifier.testTag("searchProviderChips")) {
        Chips(P.entries, d.webSearchProvider, { stringResource(it.labelRes) }) { onChange(d.copy(webSearchProvider = it)) }
    }
    val cfg = d.webSearchConfig()
    when (d.webSearchProvider) {
        P.AUTO -> Hint(stringResource(R.string.settings_search_auto_hint))
        P.TAVILY -> {
            Hint(stringResource(R.string.settings_search_tavily_hint))
            Field(stringResource(R.string.settings_search_key, "Tavily"), d.tavilyApiKey, "tavilyApiKey", secret = true) { onChange(d.copy(tavilyApiKey = it)) }
        }
        P.EXA -> {
            Hint(stringResource(R.string.settings_exa_hint))
            Field(stringResource(R.string.settings_exa_key), d.exaApiKey, "exaApiKey", secret = true) { onChange(d.copy(exaApiKey = it)) }
            Advanced(initiallyOpen = d.exaBaseUrl != def.exaBaseUrl) {
                Field(stringResource(R.string.settings_exa_base), d.exaBaseUrl, "exaBaseUrl") { onChange(d.copy(exaBaseUrl = it)) }
            }
        }
        P.BRAVE -> {
            Hint(stringResource(R.string.settings_search_brave_hint))
            Field(stringResource(R.string.settings_search_key, "Brave"), d.braveApiKey, "braveApiKey", secret = true) { onChange(d.copy(braveApiKey = it)) }
        }
        P.LANGSEARCH -> {
            Hint(stringResource(R.string.settings_search_langsearch_hint))
            Field(stringResource(R.string.settings_search_key, "LangSearch"), d.langsearchApiKey, "langsearchApiKey", secret = true) { onChange(d.copy(langsearchApiKey = it)) }
        }
        P.SERPER -> {
            Hint(stringResource(R.string.settings_search_serper_hint))
            Field(stringResource(R.string.settings_search_key, "Serper"), d.serperApiKey, "serperApiKey", secret = true) { onChange(d.copy(serperApiKey = it)) }
        }
        P.SEARXNG -> Hint(stringResource(R.string.settings_search_searxng_hint))
        P.GROQ -> Hint(stringResource(if (cfg.groqAvailable) R.string.settings_search_groq_hint else R.string.settings_search_groq_unavailable))
    }
    if (d.webSearchProvider.needsKey && !cfg.configured()) Hint(stringResource(R.string.settings_search_fallback_note))
    Advanced(initiallyOpen = d.webSearchProvider == P.SEARXNG || d.searxngUrl.isNotBlank()) {
        Field(stringResource(R.string.settings_search_searxng_url), d.searxngUrl, "searxngUrl") { onChange(d.copy(searxngUrl = it)) }
        Hint(stringResource(R.string.settings_search_searxng_url_hint))
    }
}

/** Bagian "Spotify" di Pengaturan: pilih sumber & tampilkan hanya kolom yang relevan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpotifySettingsSection(
    d: AppSettings,
    onChange: (AppSettings) -> Unit,
    spotifyStatus: SpotifyStatus,
    onSpotifyLogin: (AppSettings) -> Unit,
    onSpotifyLogout: () -> Unit,
    onSpotapiPair: suspend (AppSettings, String) -> String?,
    onSpotapiConnect: (AppSettings) -> Unit,
    onSpotapiDisconnect: () -> Unit,
    onSpotapiCheck: (AppSettings) -> Unit,
) {
    var pairingCode by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    SettingsCard("Spotify", Icons.Filled.MusicNote) {
        SubLabel(stringResource(R.string.settings_spotify_source))
        Chips(SpotifySource.entries, d.spotifySource, { stringResource(it.labelRes) }) { onChange(d.copy(spotifySource = it)) }
        when (d.spotifySource) {
            SpotifySource.LOCAL -> Hint(stringResource(R.string.settings_spotify_local_hint))
            SpotifySource.OFFICIAL -> {
                Hint(stringResource(R.string.settings_spotify_official_hint, SpotifyAuth.REDIRECT_URI))
                Field("Spotify Client ID", d.spotifyClientId, "spotifyClientId") { onChange(d.copy(spotifyClientId = it)) }
                CardDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StatusText(spotifyStatus.officialConnected, stringResource(R.string.settings_connected), stringResource(R.string.settings_not_connected)) }
                    if (spotifyStatus.officialConnected) OutlinedButton(onClick = onSpotifyLogout) { Text(stringResource(R.string.settings_disconnect)) }
                    else Button(onClick = { onSpotifyLogin(d) }, enabled = d.spotifyClientId.isNotBlank(), modifier = Modifier.testTag("spotifyConnect")) { Text(stringResource(R.string.settings_spotify_connect)) }
                }
            }
            SpotifySource.SPOTAPI -> {
                Hint(stringResource(R.string.settings_spotapi_hint))
                Field("spotapi Base URL", d.spotapiBaseUrl, "spotapiBaseUrl") { onChange(d.copy(spotapiBaseUrl = it)) }
                CardDivider()
                SubLabel(stringResource(R.string.settings_app_auth))
                Chips(SpotapiAuthMode.entries, d.spotapiAuthMode, { stringResource(it.labelRes) }) { onChange(d.copy(spotapiAuthMode = it)) }
                when (d.spotapiAuthMode) {
                    SpotapiAuthMode.PAIRING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(pairingCode, { pairingCode = it }, label = { Text(stringResource(R.string.settings_pairing_code)) }, singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                placeholder = { Text("483-219") }, modifier = Modifier.weight(1f).testTag("pairingCode"))
                            Button(
                                onClick = { scope.launch { onSpotapiPair(d, pairingCode)?.let { t -> onChange(d.copy(spotapiToken = t)); pairingCode = "" } } },
                                enabled = !spotifyStatus.spotapiBusy && pairingCode.isNotBlank() && d.spotapiBaseUrl.isNotBlank(),
                                modifier = Modifier.testTag("pairButton"),
                            ) { Text(stringResource(R.string.settings_pair)) }
                        }
                        StatusText(d.spotapiToken.isNotBlank(), stringResource(R.string.settings_device_paired), stringResource(R.string.settings_not_paired))
                    }
                    SpotapiAuthMode.STATIC_TOKEN -> {
                        Hint(stringResource(R.string.settings_static_token_hint))
                        Field("spotapi Token", d.spotapiToken, "spotapiToken", secret = true) { onChange(d.copy(spotapiToken = it.trim())) }
                    }
                }
                CardDivider()
                val session = spotifyStatus.spotapiSession
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val who = session?.let { it.displayName ?: it.username }?.let { stringResource(R.string.settings_spotify_as, it) }.orEmpty() +
                        (session?.product?.let { " ($it)" } ?: "")
                    StatusText(session?.connected == true, stringResource(R.string.settings_spotify_account_yes, who), stringResource(R.string.settings_spotify_account_no))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ready = d.spotapiBaseUrl.isNotBlank() && d.spotapiToken.isNotBlank() && !spotifyStatus.spotapiBusy
                    Button(onClick = { onSpotapiConnect(d) }, enabled = ready, modifier = Modifier.testTag("spotapiConnect")) { Text(stringResource(R.string.settings_spotify_account_connect)) }
                    OutlinedButton(onClick = { onSpotapiCheck(d) }, enabled = ready) { Text(stringResource(R.string.settings_check)) }
                    if (session?.connected == true) OutlinedButton(onClick = onSpotapiDisconnect) { Text(stringResource(R.string.settings_disconnect)) }
                }
                spotifyStatus.spotapiMessage?.let { Hint(it) }
            }
        }
    }
}
