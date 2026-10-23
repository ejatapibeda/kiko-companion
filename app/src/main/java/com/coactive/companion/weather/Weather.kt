package com.coactive.companion.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.coactive.companion.net.Http
import com.coactive.companion.net.await
import com.coactive.companion.net.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import kotlin.coroutines.resume

/** Suasana cuaca yang memicu ekspresi lucu (null = cuaca biasa, tidak ada reaksi). */
enum class WeatherMood(val label: String) { RAIN("Hujan → bersin"), HOT("Panas → kepanasan"), COLD("Dingin → kedinginan") }

/** Hasil cuaca terakhir (Open-Meteo). */
data class WeatherNow(
    val tempC: Double,
    val code: Int,
    val lat: Double,
    val lon: Double,
    /** "gps" (LocationManager) atau "ip" (perkiraan lewat IP). */
    val source: String,
    val at: Long = System.currentTimeMillis(),
) {
    val mood: WeatherMood? get() = WeatherClassifier.classify(tempC, code)
}

object WeatherClassifier {
    /** Ambang suhu (°C) — konteks Jakarta: <23 sudah terasa dingin, >32 terasa gerah. */
    const val COLD_BELOW_C = 23.0
    const val HOT_ABOVE_C = 32.0

    /** Kode WMO gerimis/hujan/hujan lokal/badai petir. */
    val RAIN_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)

    /** Prioritas: HUJAN > PANAS > DINGIN (hujan paling spesifik & paling lucu). */
    fun classify(tempC: Double, code: Int): WeatherMood? = when {
        code in RAIN_CODES -> WeatherMood.RAIN
        tempC > HOT_ABOVE_C -> WeatherMood.HOT
        tempC < COLD_BELOW_C -> WeatherMood.COLD
        else -> null
    }
}

/**
 * Lokasi (GPS/jaringan via LocationManager, cukup izin COARSE) → cuaca terkini dari Open-Meteo.
 * Tanpa API key. Jika izin ditolak / tidak ada fix → perkiraan lokasi lewat IP (ipwho.is, cadangan geojs).
 * Tidak pernah melempar: gagal = null (fitur cuaca dilewati saja).
 */
class WeatherService(context: Context) {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): WeatherNow? = try { fetchOrThrow() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        Log.w(TAG, "gagal ambil cuaca: ${e.message}"); null
    }

    private suspend fun fetchOrThrow(): WeatherNow? {
        val (lat, lon, src) = deviceLocation()?.let { Triple(it.latitude, it.longitude, "gps") }
            ?: ipLocation()?.let { Triple(it.first, it.second, "ip") }
            ?: run { Log.w(TAG, "lokasi tidak tersedia → cuaca dilewati"); return null }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code"
            .format(java.util.Locale.US, lat, lon)
        val body = getText(url)
        val cur = json.parseToJsonElement(body).jsonObject["current"]!!.jsonObject
        val t = cur["temperature_2m"]!!.jsonPrimitive.doubleOrNull!!
        val c = cur["weather_code"]!!.jsonPrimitive.intOrNull!!
        return WeatherNow(t, c, lat, lon, src).also {
            Log.i(TAG, "cuaca: ${it.tempC}°C kode=${it.code} sumber=${it.source} (%.2f,%.2f) → mood=${it.mood}".format(java.util.Locale.US, lat, lon))
        }
    }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Lokasi terakhir yang masih segar (≤ 3 jam), atau satu kali minta lokasi baru (Android 11+, maks 10 dtk). */
    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(): Location? {
        if (!hasLocationPermission()) { Log.i(TAG, "izin lokasi belum ada → pakai IP"); return null }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 3600_000L) return last
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val p = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { it in providers }
            if (p != null) {
                val fresh = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = android.os.CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        runCatching {
                            lm.getCurrentLocation(p, signal, ContextCompat.getMainExecutor(app)) { loc -> if (cont.isActive) cont.resume(loc) }
                        }.onFailure { if (cont.isActive) cont.resume(null) }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return last // lama pun masih lebih baik daripada IP
    }

    /** Perkiraan lokasi dari IP publik (tanpa key). */
    private suspend fun ipLocation(): Pair<Double, Double>? {
        for (url in listOf("https://ipwho.is/", "https://get.geojs.io/v1/ip/geo.json")) {
            val r = try {
                val o: JsonObject = json.parseToJsonElement(getText(url)).jsonObject
                val lat = o["latitude"]!!.jsonPrimitive.content.toDouble()
                val lon = o["longitude"]!!.jsonPrimitive.content.toDouble()
                lat to lon
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "IP geolokasi gagal ($url): ${e.message}"); null
            }
            if (r != null) return r
        }
        return null
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", "CompanionApp/0.1").build()).await().bodyOrThrow()
    }

    companion object { const val TAG = "CompanionWeather" }
}
