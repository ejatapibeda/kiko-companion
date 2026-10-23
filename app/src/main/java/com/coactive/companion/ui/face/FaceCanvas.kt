package com.coactive.companion.ui.face

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coactive.companion.settings.ListeningStyle
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

data class FaceColors(val eye: Color, val glow: Color, val highlight: Color, val mouth: Color, val accent: Color) {
    companion object {
        // terang: mata navy sangat gelap, glow periwinkle lembut, kilau putih
        val Light = FaceColors(Color(0xFF0C1226), Color(0xFF7D95FF), Color.White, Color(0xFF0E1530), Color(0xFF7F97FF))
        val Dark = FaceColors(Color(0xFF070B18), Color(0xFF5B8CFF), Color.White, Color(0xFF7FA4FF), Color(0xFF8FB0FF))
    }
}

/**
 * Wajah stateless: menggambar mata, mulut, efek mendengarkan dan "zZz" dari [params].
 * @param listening 0..1 intensitas efek mendengarkan, [phase] 0..1 fase animasi.
 */
/**
 * Efek musik saat joget (not ♪♫ naik di samping mata, equalizer mini di bawah mulut, denyut halo & kilau ✦).
 * Sempat dimatikan (7b03784) karena not naik sampai kiri-atas & mirip ikon musik; kini dibatasi di sisi wajah,
 * memudar jauh di bawah island/ikon atas.
 */
private const val SHOW_DANCE_FX = true

@Composable
fun FaceCanvas(
    params: FaceParams,
    colors: FaceColors,
    modifier: Modifier = Modifier,
    listening: Float = 0f,
    listeningStyle: ListeningStyle = ListeningStyle.BARS,
    phase: Float = 0f,
    micLevel: Float = 0f,
    darkGlow: Boolean = false,
    /** Waktu joget (ketukan) untuk not musik; [danceNotes] 0..1 = intensitas not (0 = tidak ada). */
    danceBeats: Float = 0f,
    danceNotes: Float = 0f,
) {
    val measurer = rememberTextMeasurer()
    val params = params.sanitized()
    val listening = listening.clampUnit()
    val micLevel = micLevel.clampUnit()
    val danceAmt = if (SHOW_DANCE_FX) danceNotes.clampUnit() else 0f
    val notes = if (danceAmt > 0.01f) DanceMotion.notes(danceBeats, danceAmt) else emptyList()
    Canvas(modifier) {
        val layout = FaceLayout.compute(size.width, size.height)
        val r = layout.r
        val cx = layout.cx
        val cy = layout.cy
        val spacing = layout.spacing
        val look = Offset(params.lookX * r * 0.9f, params.lookY * r * 0.9f)

        if (listening > 0.01f) drawListening(listeningStyle, Offset(cx, cy), r, spacing, listening, phase, micLevel, colors)

        val glint = Offset(params.glintX, params.glintY)
        if (r < 0.5f) return@Canvas // kanvas belum berukuran
        val fx = if (danceAmt > 0.01f) DanceFx.of(darkGlow, colors) else null
        if (fx != null) drawDanceBackdrop(fx, cx, cy, r, spacing, danceBeats, danceAmt)
        val pivot = Offset(cx, cy + r)
        withTransform({
            translate(0f, params.bounceY * r)
            rotate(params.tilt, pivot)
            scale(1f - params.squash * 0.5f, 1f + params.squash, pivot)
        }) {
            if (params.blush > 0.01f) for (side in listOf(-1f, 1f)) {
                drawOval(Color(0xFFFF7A9A).withAlpha(0.5f * params.blush),
                    topLeft = Offset(cx + side * (spacing + r * 0.35f) - r * 0.6f, cy + r * 1.05f) + look * 0.5f,
                    size = Size(r * 1.2f, r * 0.55f))
            }
            drawEye(Offset(cx - spacing, cy) + look, r * params.scaleL, params.openL, params.arcL, params.sparkle, colors, darkGlow, glint, params.glowPulse)
            drawEye(Offset(cx + spacing, cy) + look, r * params.scaleR, params.openR, params.arcR, params.sparkle, colors, darkGlow, glint, params.glowPulse)
            // mulut sedikit lebih dekat ke mata (proporsi imut seperti referensi)
            drawMouth(Offset(cx, cy + r * MOUTH_DROP) + look * 0.4f, r, params.mouth, colors.mouth, colors.glow, darkGlow)
        }
        // "zZz" naik ±1.1r di atas titik awal: jaga agar tidak terpotong di tepi atas/kanan
        if (params.zzz > 0.01f) {
            val zy = maxOf(cy - r * 2.6f, r * 1.2f + size.height * 0.03f)
            val zx = minOf(cx + spacing + r * 1.6f, size.width - r * 2.6f)
            drawZzz(measurer, Offset(zx, zy), r, params.zzz, phase, colors)
        }
        if (fx != null) drawDanceFront(fx, notes, cx, cy, r, spacing, danceBeats, danceAmt, 72.dp.toPx())
    }
}

private fun DrawScope.drawEye(
    c: Offset, rawR: Float, rawOpenness: Float, arc: Float, sparkle: Float, colors: FaceColors, dark: Boolean,
    glint: Offset = Offset.Zero, rawPulse: Float = 0f,
) {
    // radius dari spring bisa ~0/negatif sesaat; RadialGradient melempar jika radius <= 0
    val r = rawR.clampNonNegative()
    if (r < 0.5f) return
    val openness = rawOpenness.clampNonNegative()
    val pulse = rawPulse.clampUnit()
    // cahaya biru lembut di sekeliling mata: lebih lebar & tepinya lebih halus (banyak stop, alpha rendah)
    val glowR = r * (if (dark) 1.9f else 1.85f) * (1f + 0.12f * pulse)
    drawCircle(
        Brush.radialGradient(
            0.0f to colors.glow.withAlpha(if (dark) 0.75f else 0.72f),
            0.35f to colors.glow.withAlpha(if (dark) 0.5f else 0.5f),
            0.62f to colors.glow.withAlpha(if (dark) 0.22f else 0.24f),
            0.82f to colors.glow.withAlpha(if (dark) 0.08f else 0.08f),
            1f to Color.Transparent,
            center = c, radius = glowR,
        ),
        radius = glowR, center = c, alpha = (0.35f + 0.65f * openness.clampUnit() + 0.2f * pulse).clampUnit(),
    )
    if (openness < 0.18f) {
        // mata tertutup: garis / lengkung (^ senang, ‿ tidur)
        val w = r * 0.95f
        val path = Path().apply {
            moveTo(c.x - w, c.y)
            quadraticBezierTo(c.x, c.y + arc * r * 1.3f, c.x + w, c.y)
        }
        drawPath(path, colors.eye, style = Stroke(width = r * 0.32f, cap = StrokeCap.Round))
        return
    }
    // mata oval lebar (almond lembut): sedikit lebih lebar dari tinggi; saat kedip menyipit jadi oval tipis
    val rx = r * EYE_WIDE
    val ry = r * EYE_TALL * openness
    if (dark) drawOval(colors.glow, topLeft = Offset(c.x - rx * 1.08f, c.y - ry * 1.08f),
        size = Size(rx * 2.16f, ry * 2.16f), style = Stroke(r * 0.08f), alpha = 0.9f)
    // bola mata mengilap: navy agak terang di kiri-atas → hampir hitam
    drawOval(
        Brush.radialGradient(
            0f to Color(0xFF3A4675), 0.45f to Color(0xFF1A2244), 1f to colors.eye,
            center = c + Offset(-rx * 0.3f, -ry * 0.35f), radius = (rx * 1.35f).coerceAtLeast(1f),
        ),
        topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2),
    )
    if (ry > r * 0.3f) {
        // pantulan biru tipis di tepi bawah (kesan bola mata 3D yang lembut)
        drawOval(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.7f to Color.Transparent, 1f to colors.glow.withAlpha(if (dark) 0.45f else 0.35f),
                startY = c.y - ry, endY = c.y + ry,
            ),
            topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2),
        )
    }
    if (openness > 0.45f) {
        // kilau ikut arah pandang, selalu di dalam elips mata (lihat Catchlight)
        fun dot(anchorX: Float, anchorY: Float, rel: Float, depth: Float, color: Color) {
            val dr = r * rel
            drawCircle(color, radius = dr, center = Catchlight.center(c, rx, ry, Offset(anchorX, anchorY), glint, dr, depth))
        }
        // satu titik kilau putih kecil saja (gaya referensi)
        dot(0.36f, -0.36f, 0.17f, 1f, colors.highlight)
        if (sparkle > 0.01f) {
            dot(-0.4f, -0.25f, 0.13f, 0.85f, colors.highlight.withAlpha(sparkle))
            dot(0.45f, 0.3f, 0.08f, 0.7f, colors.highlight.withAlpha(sparkle * 0.8f))
        }
    }
}

/** Proporsi mata terbuka (relatif radius) & jarak mulut di bawah pusat mata. */
private const val EYE_WIDE = 1.1f
private const val EYE_TALL = 0.9f
private const val MOUTH_DROP = 1.8f

private fun DrawScope.drawMouth(c: Offset, r: Float, m: MouthShape, baseColor: Color, glow: Color = Color.Transparent, dark: Boolean = false) {
    val a = (m.width * r / 2).clampNonNegative()
    if (a > 0.5f) {
        // cahaya biru samar di bawah mulut (seperti referensi)
        val gr = (a * 1.6f + r * 0.3f).coerceAtLeast(1f)
        val gc = c + Offset(0f, r * 0.25f)
        drawCircle(Brush.radialGradient(0f to glow.withAlpha(if (dark) 0.3f else 0.22f), 1f to Color.Transparent, center = gc, radius = gr),
            radius = gr, center = gc)
    }
    if (m.wave > 0.01f) {
        // mulut berlekuk "~~"
        val p = Path()
        val n = 28
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = c.x - a + t * 2 * a
            val y = c.y + sin(t * 2 * PI * 1.5).toFloat() * r * 0.17f * m.wave
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, baseColor.scaleAlpha(m.wave), style = Stroke(r * 0.16f, cap = StrokeCap.Round))
        if (m.wave > 0.99f) return
    }
    val color = baseColor.scaleAlpha(1f - m.wave)
    val k = 4f / 3f
    val top = m.top * r * k
    val bottom = m.bottom * r * k
    val path = Path().apply {
        moveTo(c.x - a, c.y)
        cubicTo(c.x - a, c.y + top, c.x + a, c.y + top, c.x + a, c.y)
        cubicTo(c.x + a, c.y + bottom, c.x - a, c.y + bottom, c.x - a, c.y)
        close()
    }
    drawPath(path, color)
    if (kotlin.math.abs(bottom - top) < r * 0.35f) {
        // mulut tipis (sedih/berpikir) diberi garis agar tidak hilang
        val p = Path().apply { moveTo(c.x - a, c.y); cubicTo(c.x - a, c.y + (top + bottom) / 2, c.x + a, c.y + (top + bottom) / 2, c.x + a, c.y) }
        drawPath(p, color, style = Stroke(r * 0.16f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawListening(
    style: ListeningStyle, c: Offset, r: Float, spacing: Float, amount: Float, phase: Float, mic: Float, colors: FaceColors,
) {
    when (style) {
        ListeningStyle.BARS -> {
            val bars = 6
            val step = r * 0.38f
            val start = spacing + r * 2.1f
            val edge = r * 0.3f
            val fitSide = ((size.width / 2 - edge - start) / step).toInt() + 1
            // jaga agar bar tidak menabrak ikon di pojok atas
            val maxH = minOf(r * 2.2f, c.y - 52.dp.toPx()).coerceAtLeast(r * 0.8f)
            if (fitSide >= 3) {
                val n = minOf(bars, fitSide)
                for (side in listOf(-1, 1)) {
                    for (i in 0 until n) {
                        val x = c.x + side * (start + i * step)
                        val wave = 0.5f + 0.5f * sin((phase * 2 * PI + i * 0.9 + side).toFloat())
                        val raw = r * (0.5f + (wave * 0.9f + mic * 1.6f) * (1f - i / (bars * 1.6f)))
                        val h = maxH * kotlin.math.tanh(raw / maxH) * amount // batas lunak
                        drawLine(colors.accent.withAlpha((0.75f - i * 0.08f) * amount), Offset(x, c.y - h), Offset(x, c.y + h),
                            strokeWidth = r * 0.16f, cap = StrokeCap.Round)
                    }
                }
            } else {
                // layar sempit (portrait, wajah besar): equalizer kecil di bawah mulut
                val n = 9
                val y = c.y + r * 4.3f
                for (i in 0 until n) {
                    val k = i - (n - 1) / 2f
                    val x = c.x + k * r * 0.42f
                    val wave = 0.5f + 0.5f * sin((phase * 2 * PI + i * 0.8).toFloat())
                    val h = r * (0.12f + (wave * 0.35f + mic * 0.6f) * (1f - kotlin.math.abs(k) / n)) * amount
                    drawLine(colors.accent.withAlpha((0.8f - kotlin.math.abs(k) * 0.08f) * amount), Offset(x, y - h), Offset(x, y + h),
                        strokeWidth = r * 0.16f, cap = StrokeCap.Round)
                }
            }
        }
        ListeningStyle.RIPPLE, ListeningStyle.RIPPLE_WIDE -> {
            val rings = 3
            val base = if (style == ListeningStyle.RIPPLE) spacing * 1.25f else spacing * 1.6f
            for (i in 0 until rings) {
                val p = (phase + i / rings.toFloat()) % 1f
                val rad = (base + p * r * 2.5f + mic * r).clampNonNegative()
                drawCircle(colors.accent.withAlpha((1 - p) * 0.35f * amount), radius = rad, center = c,
                    style = Stroke(width = r * 0.18f))
            }
        }
    }
}

private fun DrawScope.drawZzz(m: TextMeasurer, at: Offset, r: Float, a: Float, phase: Float, colors: FaceColors) {
    val sizes = listOf(0.7f, 0.9f, 1.15f)
    sizes.forEachIndexed { i, s ->
        val bob = sin((phase * 2 * PI + i).toFloat()) * r * 0.12f
        drawText(m, "z", topLeft = at + Offset(i * r * 0.65f, -i * r * 0.55f + bob),
            style = TextStyle(color = colors.accent.withAlpha(a), fontSize = (r * s / density).sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Not musik minimalis: kepala oval + tangkai + bendera (♪) atau dua kepala + balok (♫). [s] = tinggi kepala. */
private fun DrawScope.drawNote(at: Offset, s: Float, double: Boolean, color: Color) {
    if (s < 0.5f || color.alpha <= 0.01f) return
    val stroke = s * 0.16f
    fun head(h: Offset) {
        drawOval(color, topLeft = Offset(h.x - s * 0.42f, h.y - s * 0.3f), size = Size(s * 0.84f, s * 0.6f))
    }
    val stemX = at.x + s * 0.36f
    head(at)
    if (!double) {
        drawLine(color, Offset(stemX, at.y), Offset(stemX, at.y - s * 1.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        val flag = Path().apply {
            moveTo(stemX, at.y - s * 1.6f)
            quadraticBezierTo(stemX + s * 0.7f, at.y - s * 1.25f, stemX + s * 0.45f, at.y - s * 0.75f)
        }
        drawPath(flag, color, style = Stroke(stroke, cap = StrokeCap.Round))
    } else {
        val h2 = at + Offset(s * 1.0f, -s * 0.3f)
        head(h2)
        val stem2 = h2.x + s * 0.36f
        drawLine(color, Offset(stemX, at.y), Offset(stemX, at.y - s * 1.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(stem2, h2.y), Offset(stem2, h2.y - s * 1.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(stemX, at.y - s * 1.6f), Offset(stem2, h2.y - s * 1.6f), strokeWidth = stroke * 1.6f, cap = StrokeCap.Round)
    }
}

/** Palet efek joget per tema (terang: lebih pekat agar terlihat di latar pucat; gelap: lebih terang/lembut). */
private class DanceFx(val notes: List<Color>, val bar: Color, val barTop: Color, val halo: Color, val sparkle: Color) {
    companion object {
        private val light by lazy {
            DanceFx(listOf(Color(0xFF6F86F5), Color(0xFFFF6FA3), Color(0xFF9A7BF2)), Color(0xFF7F97FF), Color(0xFFFF7AA8), Color(0xFF8FA4FF), Color(0xFFFFB13D))
        }
        private val dark by lazy {
            DanceFx(listOf(Color(0xFF9DB8FF), Color(0xFFFF9CC2), Color(0xFFC3AEFF)), Color(0xFF8FB0FF), Color(0xFFFF9CC2), Color(0xFF5B8CFF), Color(0xFFFFD27A))
        }
        @Suppress("UNUSED_PARAMETER") fun of(dark: Boolean, colors: FaceColors): DanceFx = if (dark) this.dark else light
    }
}

/** Di belakang wajah: halo lembut yang berdenyut di ketukan. */
private fun DrawScope.drawDanceBackdrop(fx: DanceFx, cx: Float, cy: Float, r: Float, spacing: Float, beats: Float, amount: Float) {
    val pulse = DanceMotion.beatPulse(beats, amount)
    val c = Offset(cx, cy + r * 0.7f)
    val rad = ((spacing + r * 2.2f) * (1f + 0.05f * pulse)).clampNonNegative()
    if (rad < 1f) return
    drawCircle(
        Brush.radialGradient(
            0f to fx.halo.withAlpha(0.16f * amount + 0.14f * pulse),
            0.55f to fx.halo.withAlpha(0.06f * amount + 0.06f * pulse),
            1f to Color.Transparent,
            center = c, radius = rad,
        ),
        radius = rad, center = c,
    )
}

/** Di depan wajah: not naik di sisi mata, equalizer di bawah mulut, kilau ✦. [topSafe] = batas atas (island/ikon). */
private fun DrawScope.drawDanceFront(
    fx: DanceFx, notes: List<MusicNote>, cx: Float, cy: Float, r: Float, spacing: Float, beats: Float, amount: Float, topSafe: Float,
) {
    // --- not ♪/♫: muncul di samping pipi, naik sampai kira-kira setinggi alis lalu hilang
    // cukup ruang di samping mata (landscape/tablet)? kalau tidak (portrait sempit) not naik dari atas mata
    val besides = size.width / 2f - (spacing + r * 1.75f) >= r * 1.15f
    for (n in notes) {
        val wob = sin(n.age * 2 * PI * 1.1 + n.lane * 3).toFloat() * r * 0.16f
        val s = (r * 0.4f * n.scale).clampNonNegative()
        val x: Float; val y: Float
        if (besides) {
            x = cx + n.side * (spacing + r * (1.85f + 0.55f * n.lane + 0.2f * n.age)) + wob
            y = cy + r * (0.9f - 2.1f * n.age)
        } else {
            x = cx + n.side * (spacing + r * (0.1f + 0.7f * n.lane + 0.15f * n.age)) + wob
            y = cy - r * (1.7f + 2.0f * n.age)
        }
        // pudar bila mendekati area island (ujung tangkai not ada di y - 1.6s)
        val top = y - s * 1.7f
        val safeFade = ((top - topSafe) / (r * 0.6f)).clampUnit()
        if (safeFade <= 0.01f) continue
        val xc = x.coerceIn(r * 0.5f, (size.width - s * 1.6f - r * 0.3f).coerceAtLeast(r * 0.5f))
        drawNote(Offset(xc, y), s, n.double, fx.notes[n.hue.coerceIn(0, fx.notes.lastIndex)].withAlpha(n.alpha * safeFade))
    }
    // --- equalizer mini di bawah mulut (lantai tetap, tidak ikut lompat)
    val levels = DanceMotion.eqLevels(beats, amount)
    if (levels.isNotEmpty()) {
        val base = minOf(cy + r * 3.1f, size.height - r * 0.2f)
        val maxH = (base - (cy + r * 2.45f)).coerceAtMost(r * 0.7f)
        if (maxH > r * 0.15f) {
            val total = spacing * 0.9f
            val step = total / levels.size
            val w = step * 0.58f
            val x0 = cx - total / 2f + (step - w) / 2f
            levels.forEachIndexed { i, lv ->
                val h = (maxH * (0.12f + 0.88f * lv)).coerceAtLeast(w)
                val left = x0 + i * step
                drawRoundRect(
                    Brush.verticalGradient(listOf(fx.barTop.withAlpha(0.8f * amount), fx.bar.withAlpha(0.55f * amount)), startY = base - h, endY = base),
                    topLeft = Offset(left, base - h), size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                )
            }
        }
    }
    // --- kilau ✦ di 4 titik sekitar wajah (di luar mata, di bawah batas atas)
    for (sp in DanceMotion.sparkles(beats, amount)) {
        val (dx, dy) = when (sp.slot) { 0 -> -1f to -0.9f; 1 -> 1f to -0.6f; 2 -> -1f to 1.6f; else -> 1f to 1.9f }
        val p = Offset((cx + dx * (spacing + r * 1.35f)).coerceIn(r * 0.5f, (size.width - r * 0.5f).coerceAtLeast(r * 0.5f)), (cy + dy * r).coerceAtLeast(topSafe + r * 0.4f))
        drawSparkle(p, r * 0.28f * sp.scale, fx.sparkle.withAlpha(sp.alpha))
    }
}

private fun DrawScope.drawSparkle(c: Offset, s: Float, color: Color) {
    if (s < 0.5f || color.alpha <= 0.01f) return
    val k = s * 0.28f
    val path = Path().apply {
        moveTo(c.x, c.y - s)
        quadraticBezierTo(c.x + k * 0.3f, c.y - k * 0.3f, c.x + s, c.y)
        quadraticBezierTo(c.x + k * 0.3f, c.y + k * 0.3f, c.x, c.y + s)
        quadraticBezierTo(c.x - k * 0.3f, c.y + k * 0.3f, c.x - s, c.y)
        quadraticBezierTo(c.x - k * 0.3f, c.y - k * 0.3f, c.x, c.y - s)
        close()
    }
    drawPath(path, color)
}
