package com.coactive.companion.ui.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Suasana latar cuaca (ambient) di belakang wajah. */
enum class WeatherScene { RAIN, COLD, HOT }

/**
 * Efek cuaca ambient layar penuh di belakang wajah (gaya sama dengan not musik joget: partikel Canvas ringan):
 * - HUJAN: garis hujan tipis miring + riak kecil di bawah + rona kebiruan,
 * - DINGIN: kepingan salju 6 lengan bercabang (+ butiran kecil) melayang berputar pelan + bingkai embun beku di tepi + rona biru + sesekali uap napas di dekat mulut,
 * - PANAS: rona oranye hangat + cahaya matahari lembut di pojok + partikel hangat naik + gelombang fatamorgana
 *   + sesekali tetes keringat di pelipis.
 * Partikel dihitung dari waktu (deterministik, tanpa alokasi per partikel). Jam hanya berjalan saat efek terlihat,
 * dibatasi ~30 fps agar hemat baterai. Masuk/keluar memudar halus ([scene] null = hilang).
 */
@Composable
fun WeatherAmbient(scene: WeatherScene?, dark: Boolean, modifier: Modifier = Modifier) {
    val fixedTime = LocalWeatherPreviewTime.current ?: if (LocalInspectionMode.current) 7.3f else null
    val preview = fixedTime != null // snapshot/preview: tampil penuh pada waktu tetap (deterministik), tanpa jam
    val amount = remember { Animatable(if (preview) 1f else 0f) }
    var shown by remember { mutableStateOf(scene) }
    LaunchedEffect(scene) {
        if (scene != null && shown != null && shown != scene) amount.animateTo(0f, tween(700)) // ganti suasana: pudar dulu
        if (scene != null) shown = scene
        amount.animateTo(if (scene != null) 1f else 0f, tween(if (scene != null) 1400 else 1000))
        if (scene == null) shown = null
    }
    var time by remember { mutableFloatStateOf(fixedTime ?: 0f) }
    val running = shown != null && !preview
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        val start = withFrameMillis { it }
        while (true) withFrameMillis { ms ->
            if (ms - last >= 33) { last = ms; time = ((ms - start) % 600_000L) / 1000f }
        }
    }
    val sc = shown ?: return
    val a = amount.value.coerceIn(0f, 1f)
    if (a <= 0.001f) return
    val snow = remember { SnowCache() }
    Canvas(modifier) {
        val layout = FaceLayout.compute(size.width, size.height)
        when (sc) {
            WeatherScene.RAIN -> drawRain(time, a, dark, layout)
            WeatherScene.COLD -> drawCold(time, a, dark, layout, snow)
            WeatherScene.HOT -> drawHot(time, a, dark, layout)
        }
    }
}

/** Waktu tetap (detik) untuk snapshot/preview efek cuaca; null = animasi normal. */
val LocalWeatherPreviewTime = androidx.compose.runtime.staticCompositionLocalOf<Float?> { null }

/** Hash 0..1 deterministik per partikel. */
private fun h(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777215f
}

private fun frac(x: Float) = x - kotlin.math.floor(x)
private val TAU = (2 * PI).toFloat()

private fun DrawScope.drawRain(t: Float, a: Float, dark: Boolean, l: FaceLayout) {
    val w = size.width; val hgt = size.height; val r = l.r
    drawRect(Brush.verticalGradient(0f to Color(0xFF5B8FD6).copy(alpha = (if (dark) 0.16f else 0.12f) * a), 0.7f to Color.Transparent))
    val drop = if (dark) Color(0xFF9CC9FF) else Color(0xFF5E93D1)
    for (i in 0 until 64) {
        val speed = 0.75f + 0.5f * h(i, 2)      // tinggi layar per detik
        val len = r * (0.55f + 0.6f * h(i, 3))
        val y = (frac(h(i, 4) + t * speed) * 1.2f - 0.1f) * hgt
        val x = h(i, 1) * w * 1.15f - 0.05f * w - (y / hgt) * w * 0.08f
        val alpha = (if (dark) 0.18f + 0.2f * h(i, 5) else 0.26f + 0.24f * h(i, 5)) * a
        drawLine(drop.copy(alpha = alpha), Offset(x, y), Offset(x - len * 0.16f, y + len), strokeWidth = max(1.5f, r * 0.045f), cap = StrokeCap.Round)
    }
    // riak kecil di bagian bawah
    for (k in 0 until 4) {
        val cyc = 2.4f
        val ph = frac(t / cyc + k * 0.27f)
        val idx = (t / cyc + k * 0.27f).toInt() * 7 + k
        val c = Offset((0.12f + 0.76f * h(idx, 9)) * w, hgt * (0.83f + 0.1f * h(idx, 10)))
        val rad = r * (0.25f + 1.1f * ph)
        drawOval(drop.copy(alpha = 0.3f * (1 - ph) * a), Offset(c.x - rad, c.y - rad * 0.32f), Size(rad * 2, rad * 0.64f), style = Stroke(max(1.2f, r * 0.04f)))
    }
}

private fun DrawScope.drawCold(t: Float, a: Float, dark: Boolean, l: FaceLayout, snow: SnowCache) {
    val w = size.width; val hgt = size.height; val r = l.r
    drawRect(Color(0xFF8EC5FF).copy(alpha = (if (dark) 0.08f else 0.07f) * a))
    // bingkai embun beku di tepi
    drawRect(Brush.radialGradient(
        0.55f to Color.Transparent, 0.85f to Color(0xFFBFE0FF).copy(alpha = 0.22f * a), 1f to Color(0xFFE6F4FF).copy(alpha = 0.45f * a),
        center = Offset(w / 2, hgt / 2), radius = max(w, hgt) * 0.72f))
    drawSnow(t, a, dark, l, snow)
    // uap napas "huff" di dekat mulut (tiap ~5 dtk, 1.8 dtk)
    val cyc = 5f
    val ph = frac(t / cyc) * cyc / 1.8f
    if (ph < 1f) {
        val mouth = Offset(l.cx + r * 0.55f, l.cy + r * 2.0f)
        val col = if (dark) Color(0xFFE3F1FF) else Color(0xFFAFCDEB)
        for (k in 0 until 3) {
            val p = (ph - k * 0.12f).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val c = mouth + Offset(r * (0.4f + 1.4f * p + k * 0.35f), -r * (0.15f + 0.5f * p))
            drawCircle(col.copy(alpha = 0.45f * sin(p * PI.toFloat()) * a), r * (0.18f + 0.3f * p + k * 0.05f), c)
        }
    }
}

private fun DrawScope.drawHot(t: Float, a: Float, dark: Boolean, l: FaceLayout) {
    val w = size.width; val hgt = size.height; val r = l.r
    drawRect(Brush.verticalGradient(
        0f to Color(0xFFFF9A3C).copy(alpha = (if (dark) 0.16f else 0.13f) * a), 0.5f to Color(0xFFFFC27A).copy(alpha = 0.05f * a),
        1f to Color(0xFFFF7A45).copy(alpha = 0.1f * a)))
    // matahari lembut di pojok kanan atas (berdenyut pelan)
    val pulse = 1f + 0.06f * sin(t * 1.3f)
    val sunR = w * 0.6f * pulse
    drawCircle(Brush.radialGradient(
        0f to Color(0xFFFFD27A).copy(alpha = 0.55f * a), 0.35f to Color(0xFFFFB04D).copy(alpha = 0.25f * a), 1f to Color.Transparent,
        center = Offset(w * 0.95f, hgt * 0.02f), radius = sunR), sunR, Offset(w * 0.95f, hgt * 0.02f))
    // gelombang fatamorgana (garis berombak tipis yang naik pelan)
    val wave = Color(0xFFFF9A50)
    for (k in 0 until 3) {
        val ph = frac(t * 0.07f + k / 3f)
        val y0 = hgt * (0.95f - 0.8f * ph)
        val path = Path()
        var x = 0f
        val step = w / 24f
        path.moveTo(0f, y0)
        while (x <= w + step) { x += step; path.lineTo(x, y0 + sin(x / w * TAU * 2.5f + t * 2.2f + k) * r * 0.12f) }
        drawPath(path, wave.copy(alpha = 0.16f * sin(ph * PI.toFloat()) * a), style = Stroke(max(1.5f, r * 0.05f), cap = StrokeCap.Round))
    }
    // partikel hangat naik
    for (i in 0 until 24) {
        val speed = 0.04f + 0.05f * h(i, 2)
        val ph = frac(h(i, 4) + t * speed)
        val y = hgt * (1.05f - 1.1f * ph)
        val x = h(i, 1) * w + sin(t * 1.5f + h(i, 7) * TAU) * r * 0.35f
        drawCircle(Color(if (h(i, 8) > 0.5f) 0xFFFFA14A else 0xFFFF7A5C).copy(alpha = (0.18f + 0.25f * h(i, 5)) * sin(ph * PI.toFloat()) * a),
            r * (0.05f + 0.08f * h(i, 3)), Offset(x, y))
    }
    // tetes keringat sesekali di pelipis kanan (tiap ~6 dtk, meluncur 1.6 dtk)
    val ph = frac(t / 6f) * 6f / 1.6f
    if (ph < 1f) {
        val c = Offset(l.cx + l.spacing + r * 1.25f, l.cy - r * 0.9f + r * 1.1f * ph * ph)
        val s = r * 0.22f
        val alpha = (if (ph < 0.15f) ph / 0.15f else 1f - ((ph - 0.7f) / 0.3f).coerceIn(0f, 1f)) * a
        val drop = Path().apply {
            moveTo(c.x, c.y - s * 1.8f)
            cubicTo(c.x + s * 0.2f, c.y - s * 0.9f, c.x + s, c.y - s * 0.3f, c.x + s, c.y + s * 0.2f)
            cubicTo(c.x + s, c.y + s * 0.8f, c.x + s * 0.5f, c.y + s * 1.1f, c.x, c.y + s * 1.1f)
            cubicTo(c.x - s * 0.5f, c.y + s * 1.1f, c.x - s, c.y + s * 0.8f, c.x - s, c.y + s * 0.2f)
            cubicTo(c.x - s, c.y - s * 0.3f, c.x - s * 0.2f, c.y - s * 0.9f, c.x, c.y - s * 1.8f)
            close()
        }
        drawPath(drop, Color(0xFF7CC4FF).copy(alpha = 0.85f * alpha))
        drawCircle(Color.White.copy(alpha = 0.8f * alpha), s * 0.25f, Offset(c.x - s * 0.35f, c.y + s * 0.1f))
    }
}

/** Path kepingan salju di-cache per (tier, ukuran, varian) untuk r wajah tertentu: dibangun sekali, tiap frame cuma translate+rotate. */
private class SnowCache {
    private var forR = -1f
    private val paths = arrayOfNulls<Path>(4 * Snowfall.SIZE_STEPS * Snowfall.VARIANTS)
    val flake = Snowfall.Flake()
    val strokes = arrayOfNulls<Stroke>(paths.size * 2)

    fun path(f: Snowfall.Flake, r: Float): Int {
        if (r != forR) { forR = r; paths.fill(null); strokes.fill(null) }
        val key = (f.tier.ordinal * Snowfall.SIZE_STEPS + f.sizeStep) * Snowfall.VARIANTS + f.variant
        if (paths[key] == null) {
            val seg = Snowfall.segments(f.radius, f.variant)
            paths[key] = Path().apply {
                var k = 0
                while (k < seg.size) { moveTo(seg[k], seg[k + 1]); lineTo(seg[k + 2], seg[k + 3]); k += 4 }
            }
            val sw = max(1.2f, f.radius * 0.11f)
            strokes[key * 2] = Stroke(sw, cap = StrokeCap.Round)              // inti putih
            strokes[key * 2 + 1] = Stroke(sw * 2.3f, cap = StrokeCap.Round)  // garis tepi/bayangan biru
        }
        return key
    }
    fun pathAt(key: Int) = paths[key]!!
}

private fun DrawScope.drawSnow(t: Float, a: Float, dark: Boolean, l: FaceLayout, c: SnowCache) {
    val w = size.width; val hgt = size.height; val r = l.r
    val topClear = 64.dp.toPx() // bar atas + Dynamic Island
    val core = Color.White
    val edge = if (dark) Color(0xFF9CC4EE) else Color(0xFF5E8FC8) // tepi biru tipis: tetap terlihat di tema terang
    val edgeA = if (dark) 0.32f else 0.6f
    val dotCore = if (dark) Color(0xFFF2F8FF) else Color(0xFFC9DDF3) // butiran jauh: di tema terang sedikit biru agar terlihat
    val f = c.flake
    for (i in 0 until Snowfall.COUNT) {
        Snowfall.flake(i, t, w, hgt, r, topClear, f)
        val alpha = f.alpha * a
        if (alpha <= 0.01f) continue
        if (f.tier == Snowfall.Tier.DOT) { // butiran jauh yang lembut (kedalaman)
            val o = Offset(f.x, f.y)
            drawCircle(dotCore.copy(alpha = alpha * 0.35f), f.radius * 1.7f, o) // pendar lembut, bukan cincin
            drawCircle(dotCore.copy(alpha = alpha), f.radius, o)
            continue
        }
        val key = c.path(f, r)
        val path = c.pathAt(key)
        translate(f.x, f.y) {
            rotate(f.rotation, pivot = Offset.Zero) {
                drawPath(path, edge.copy(alpha = alpha * edgeA), style = c.strokes[key * 2 + 1]!!)
                drawPath(path, core.copy(alpha = alpha), style = c.strokes[key * 2]!!)
            }
        }
    }
}
