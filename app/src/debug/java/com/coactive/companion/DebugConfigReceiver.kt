package com.coactive.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.coactive.companion.settings.SettingsRepository
import com.coactive.companion.settings.TtsProvider

/**
 * Debug saja. Contoh:
 * adb shell am broadcast -n com.coactive.companion/.DebugConfigReceiver -a com.coactive.companion.DEBUG_CONFIG \
 *   --es llm_key "$KEY" --es llm_model openai/gpt-oss-120b --es tts TIKTOK
 * Nilai disimpan terenkripsi seperti dari layar Pengaturan; aplikasi perlu dibuka ulang agar terbaca.
 */
class DebugConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = SettingsRepository(context)
        repo.update { s ->
            s.copy(
                llmApiKey = intent.getStringExtra("llm_key") ?: s.llmApiKey,
                llmBaseUrl = intent.getStringExtra("llm_base") ?: s.llmBaseUrl,
                llmModel = intent.getStringExtra("llm_model") ?: s.llmModel,
                ttsProvider = intent.getStringExtra("tts")?.let { runCatching { TtsProvider.valueOf(it) }.getOrNull() } ?: s.ttsProvider,
                wakeWordEnabled = if (intent.hasExtra("wake")) intent.getBooleanExtra("wake", true) else s.wakeWordEnabled,
                sleepAfterSeconds = if (intent.hasExtra("sleep")) intent.getIntExtra("sleep", 90) else s.sleepAfterSeconds,
            )
        }
        Log.i("Companion", "debug config applied (keys not logged)")
    }
}
