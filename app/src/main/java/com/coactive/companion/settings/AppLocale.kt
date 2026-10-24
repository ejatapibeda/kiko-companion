package com.coactive.companion.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Bahasa UI aplikasi (override locale sistem, tanpa reinstall).
 * - API 33+: LocaleManager.setApplicationLocales (sistem me-recreate activity & ingat pilihan; juga muncul di
 *   Pengaturan sistem → Bahasa aplikasi berkat res/xml/locales_config.xml).
 * - API < 33: bungkus context di attachBaseContext; pilihan dicerminkan ke prefs biasa (tak terenkripsi, hanya tag bahasa)
 *   supaya bisa dibaca sebelum EncryptedSharedPreferences siap.
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    fun locale(l: AppLanguage): Locale = Locale.forLanguageTag(l.tag)

    /** Bahasa yang sedang dipakai sistem untuk app ini (API 33+), null bila mengikuti sistem. */
    fun systemAppLanguage(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < 33) return null
        val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (list.isEmpty) null else AppLanguage.fromTag(list[0].toLanguageTag())
    }

    /** Terapkan pilihan. Mengembalikan true bila activity akan/di-recreate. */
    fun apply(activity: Activity, l: AppLanguage): Boolean {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, l.tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = activity.getSystemService(LocaleManager::class.java) ?: return false
            if (systemAppLanguage(activity) == l) return false
            lm.applicationLocales = LocaleList.forLanguageTags(l.tag) // sistem me-recreate activity
            return true
        }
        val current = AppLanguage.fromTag(activity.resources.configuration.locales[0].toLanguageTag())
        if (current == l) return false
        activity.recreate()
        return true
    }

    /** Tag bahasa UI yang tersimpan (ikut cadangan pengaturan). */
    fun storedTag(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun setStoredTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
    }

    /** Untuk attachBaseContext (API < 33). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val l = AppLanguage.fromTag(tag) ?: return base
        return localized(base, l)
    }

    /** Context dengan resource bahasa [l] (dipakai ViewModel untuk caption/teks di luar Compose). */
    fun localized(context: Context, l: AppLanguage): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale(l)))
        return context.createConfigurationContext(config)
    }
}
