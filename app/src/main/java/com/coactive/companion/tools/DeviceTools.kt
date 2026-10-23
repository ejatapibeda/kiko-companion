package com.coactive.companion.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DateTimeTool(private val locale: () -> Locale) : AssistantTool {
    override val name = "get_datetime"
    override val description = "Get the current local date, time and day of week on the device."
    override val parameters = schema()
    override suspend fun execute(args: JsonObject): String {
        val now = Date()
        return buildJsonObject {
            put("date", SimpleDateFormat("EEEE, d MMMM yyyy", locale()).format(now))
            put("time", SimpleDateFormat("HH:mm", locale()).format(now))
            put("timezone", java.util.TimeZone.getDefault().id)
        }.toString()
    }
}

class TimerTool(private val context: Context) : AssistantTool {
    override val name = "set_timer"
    override val description = "Start a countdown timer in the device clock app."
    override val parameters = schema {
        integer("seconds", "Timer length in seconds (e.g. 5 minutes = 300)", required = true)
        string("label", "Optional label")
    }
    override suspend fun execute(args: JsonObject): String {
        val secs = args.int("seconds")?.coerceIn(1, 86_399) ?: return """{"error":"seconds wajib"}"""
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label") ?: "Timer")
        return startOrError(context, intent, """{"ok":true,"seconds":$secs}""")
    }
}

class AlarmTool(private val context: Context) : AssistantTool {
    override val name = "set_alarm"
    override val description = "Set an alarm in the device clock app at a given local time (24h)."
    override val parameters = schema {
        integer("hour", "Hour 0-23", required = true)
        integer("minute", "Minute 0-59", required = true)
        string("label", "Optional label")
    }
    override suspend fun execute(args: JsonObject): String {
        val h = args.int("hour")?.coerceIn(0, 23) ?: return """{"error":"hour wajib"}"""
        val m = args.int("minute")?.coerceIn(0, 59) ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label") ?: "Alarm")
        return startOrError(context, intent, """{"ok":true,"time":"%02d:%02d"}""".format(h, m))
    }
}

class OpenAppTool(private val context: Context) : AssistantTool {
    override val name = "open_app"
    override val description = "Open an installed app on the device by its name (e.g. 'YouTube', 'WhatsApp', 'Kamera')."
    override val parameters = schema { string("app_name", "App name as the user said it", required = true) }

    override suspend fun execute(args: JsonObject): String {
        val query = args.str("app_name")?.trim().orEmpty()
        if (query.isEmpty()) return """{"error":"app_name wajib"}"""
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val best = AppMatcher.best(query, launchers) ?: return """{"error":"aplikasi '$query' tidak ditemukan"}"""
        val intent = pm.getLaunchIntentForPackage(best.second) ?: return """{"error":"tidak bisa membuka ${best.first}"}"""
        return startOrError(context, intent, buildJsonObject { put("ok", true); put("opened", best.first) }.toString())
    }
}

object AppMatcher {
    /** Mencocokkan nama aplikasi: sama persis > diawali > mengandung; tidak peka huruf besar/kecil. */
    fun best(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = query.lowercase(Locale.ROOT).removePrefix("aplikasi ").trim()
        fun score(label: String): Int {
            val l = label.lowercase(Locale.ROOT)
            return when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.contains(q) -> 60
                q.contains(l) && l.length >= 3 -> 40
                else -> 0
            }
        }
        return apps.map { it to score(it.first) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}

internal fun startOrError(context: Context, intent: Intent, ok: String): String = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    ok
} catch (e: Exception) {
    """{"error":"tidak ada aplikasi yang bisa menangani: ${e.javaClass.simpleName}"}"""
}

@Suppress("unused")
private fun PackageManager.hasApp(pkg: String) = runCatching { getPackageInfo(pkg, 0) }.isSuccess

@Suppress("unused")
private fun calendarNow() = Calendar.getInstance()
