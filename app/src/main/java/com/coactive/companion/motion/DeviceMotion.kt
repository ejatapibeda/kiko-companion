package com.coactive.companion.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Deteksi goyangan dari akselerometer (murni, mudah diuji). */
class ShakeDetector(
    private val thresholdMs2: Float = 11f, // percepatan di luar gravitasi
    private val peaksNeeded: Int = 3,
    private val windowMs: Long = 800,
) {
    private val peaks = ArrayDeque<Long>()
    private var lastPeak = Long.MIN_VALUE / 2

    fun feed(ax: Float, ay: Float, az: Float, nowMs: Long): Boolean {
        val linear = abs(sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH)
        if (linear < thresholdMs2 || nowMs - lastPeak < 90) return false
        lastPeak = nowMs
        peaks.addLast(nowMs)
        while (peaks.isNotEmpty() && nowMs - peaks.first() > windowMs) peaks.removeFirst()
        if (peaks.size >= peaksNeeded) { peaks.clear(); return true }
        return false
    }
}

/** Kemiringan → condong mata (-1..1), low-pass + dikuantisasi agar UI tidak terus-menerus recompose. */
class LeanFilter(private val alpha: Float = 0.12f, private val step: Float = 0.05f) {
    private var x = 0f
    /** [screenX] = komponen akselerometer pada sumbu-X layar (m/s²). Return nilai lean terkuantisasi. */
    fun feed(screenX: Float): Float {
        val target = (-screenX / SensorManager.GRAVITY_EARTH * 1.3f).coerceIn(-1f, 1f)
        x += (target - x) * alpha
        val q = (x / step).roundToInt() * step
        return if (abs(q) < step * 1.5f) 0f else q // zona mati kecil saat HP hampir tegak
    }

    companion object {
        /** Petakan sumbu perangkat ke sumbu-X layar sesuai rotasi tampilan. */
        fun screenX(ax: Float, ay: Float, rotation: Int): Float = when (rotation) {
            Surface.ROTATION_90 -> -ay
            Surface.ROTATION_180 -> -ax
            Surface.ROTATION_270 -> ay
            else -> ax
        }
    }
}

/**
 * Pendengar akselerometer: hanya aktif saat [start] (dipanggil di onResume) dan dilepas di [stop] (onPause)
 * sehingga tidak menguras baterai di latar belakang. SENSOR_DELAY_UI (~16 Hz) cukup.
 */
class DeviceMotion(context: Context, private val rotation: () -> Int, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val shake = ShakeDetector()
    private val leanFilter = LeanFilter()
    private val _lean = MutableStateFlow(0f)
    val lean: StateFlow<Float> = _lean.asStateFlow()
    private var running = false

    fun start() {
        if (running || accel == null) return
        running = sm!!.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        sm?.unregisterListener(this)
        running = false
        _lean.value = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (ax, ay, az) = Triple(e.values[0], e.values[1], e.values[2])
        if (shake.feed(ax, ay, az, System.currentTimeMillis())) onShake()
        val v = leanFilter.feed(LeanFilter.screenX(ax, ay, rotation()))
        if (v != _lean.value) _lean.value = v
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
