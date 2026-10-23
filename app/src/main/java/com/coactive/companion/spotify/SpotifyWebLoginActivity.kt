package com.coactive.companion.spotify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Login Spotify di WebView (untuk mode spotapi). Pengguna login sendiri di accounts.spotify.com;
 * setelah berhasil, cookie sp_dc (+ sp_key/sp_t bila ada) dibaca dari CookieManager dan dikembalikan
 * HANYA lewat Intent hasil (memori). Aplikasi lalu mengirimnya SEKALI ke POST /spotify/session dan tidak
 * menyimpannya. Cookie WebView dihapus sebelum & sesudah login agar tidak tertinggal di disk.
 * Tidak ada login email+sandi+captcha buatan aplikasi.
 */
class SpotifyWebLoginActivity : Activity() {
    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null); cm.flush()
        cm.setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.saveFormData = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = check()
                override fun onPageFinished(view: WebView, url: String) = check()
            }
        }
        cm.setAcceptThirdPartyCookies(web, true)
        val hint = TextView(this).apply {
            text = "Masuk ke akun Spotify kamu. Kata sandi tidak disimpan aplikasi; hanya sesi login yang dikirim sekali ke server spotapi milikmu."
            setPadding(32, 24, 32, 24)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(LOGIN_URL)
    }

    private fun check() {
        if (done) return
        val cm = CookieManager.getInstance()
        val cookies = COOKIE_URLS.mapNotNull { cm.getCookie(it) }.joinToString("; ")
        val parsed = parseCookies(cookies) ?: return
        done = true
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_SP_DC, parsed.spDc); putExtra(EXTRA_SP_KEY, parsed.spKey); putExtra(EXTRA_SP_T, parsed.spT)
        })
        wipe()
        finish()
    }

    private fun wipe() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
            web.clearCache(true); web.clearHistory(); web.clearFormData()
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    override fun onDestroy() {
        wipe()
        runCatching { web.destroy() }
        super.onDestroy()
    }

    companion object {
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        val COOKIE_URLS = listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com", "https://spotify.com")
        const val EXTRA_SP_DC = "sp_dc"
        const val EXTRA_SP_KEY = "sp_key"
        const val EXTRA_SP_T = "sp_t"

        /** Parsing header cookie "a=b; c=d" → SessionCookies (null jika sp_dc belum ada). */
        fun parseCookies(header: String): SessionCookies? {
            val map = header.split(';').mapNotNull {
                val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
            }.toMap()
            val dc = map["sp_dc"]?.takeIf { it.isNotBlank() } ?: return null
            return SessionCookies(dc, map["sp_key"]?.takeIf { it.isNotBlank() }, map["sp_t"]?.takeIf { it.isNotBlank() })
        }

        fun fromResult(data: Intent?): SessionCookies? {
            val dc = data?.getStringExtra(EXTRA_SP_DC)?.takeIf { it.isNotBlank() } ?: return null
            return SessionCookies(dc, data.getStringExtra(EXTRA_SP_KEY), data.getStringExtra(EXTRA_SP_T))
        }
    }
}
