package com.coactive.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coactive.companion.R
import com.coactive.companion.assistant.ActivityKind
import com.coactive.companion.assistant.ActivityStep
import com.coactive.companion.assistant.AgentActivity
import com.coactive.companion.ui.face.FaceLayout
import com.coactive.companion.ui.face.GazeMode
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ikon, warna, dan pola lirikan per aktivitas (satu tempat → wajah, pil, dan chat konsisten). */
object ActivityVisuals {
    val SpotifyGreen = Color(0xFF1DB954)
    val WeatherAmber = Color(0xFFF5A623)
    val FailRed = Color(0xFFE5484D)

    fun icon(kind: ActivityKind, failed: Boolean = false): ImageVector = if (failed) Icons.Outlined.ErrorOutline else when (kind) {
        ActivityKind.LISTENING -> Icons.Filled.Mic
        ActivityKind.TRANSCRIBING -> Icons.Filled.GraphicEq
        ActivityKind.THINKING -> Icons.Outlined.Psychology
        ActivityKind.VISION -> Icons.Outlined.Image
        ActivityKind.WEB_SEARCH -> Icons.Filled.Search
        ActivityKind.BROWSING -> Icons.Outlined.Description
        ActivityKind.SPOTIFY_PLAY -> Icons.Filled.MusicNote
        ActivityKind.SPOTIFY_PAUSE -> Icons.Filled.Pause
        ActivityKind.SPOTIFY_RESUME -> Icons.Filled.PlayArrow
        ActivityKind.SPOTIFY_NEXT -> Icons.Filled.SkipNext
        ActivityKind.SPOTIFY_PREVIOUS -> Icons.Filled.SkipPrevious
        ActivityKind.SPOTIFY_NOW_PLAYING -> Icons.Filled.LibraryMusic
        ActivityKind.WEATHER -> Icons.Outlined.WbSunny
        ActivityKind.LYRICS -> Icons.Outlined.Lyrics
        ActivityKind.TIMER -> Icons.Outlined.Timer
        ActivityKind.ALARM -> Icons.Outlined.Alarm
        ActivityKind.OPEN_APP -> Icons.Outlined.Apps
        ActivityKind.DATETIME -> Icons.Outlined.Schedule
        ActivityKind.DANCE -> Icons.Outlined.Celebration
        ActivityKind.TOOL -> Icons.Outlined.Build
        ActivityKind.SPEAKING_PREP -> Icons.Outlined.RecordVoiceOver
    }

    fun tint(kind: ActivityKind, dark: Boolean, failed: Boolean = false): Color = when {
        failed -> FailRed
        kind.isSpotify || kind == ActivityKind.LYRICS -> SpotifyGreen
        kind == ActivityKind.WEATHER -> WeatherAmber
        kind == ActivityKind.LISTENING || kind == ActivityKind.TRANSCRIBING -> if (dark) Palette.MicBlue else Palette.MicBlueDeep
        else -> if (dark) Color(0xFF9DB4FF) else Palette.Accent
    }

    /** Lirikan khusus saat aktivitas ini (null = ikut ekspresi). */
    fun gaze(a: AgentActivity?): GazeMode? = when (a?.kind) {
        ActivityKind.WEB_SEARCH -> GazeMode.SCANNING
        ActivityKind.BROWSING, ActivityKind.VISION -> GazeMode.READING
        ActivityKind.TRANSCRIBING -> GazeMode.LISTENING
        ActivityKind.THINKING, ActivityKind.WEATHER -> GazeMode.THINKING
        null -> null
        else -> if (a.kind.isSpotify) GazeMode.TALKING else null
    }
}

@Composable
fun activityLabel(a: AgentActivity): String {
    val d = a.detail
    val base = when (a.kind) {
        ActivityKind.LISTENING -> stringResource(R.string.act_listening)
        ActivityKind.TRANSCRIBING -> stringResource(R.string.act_transcribing)
        ActivityKind.THINKING -> stringResource(R.string.act_thinking)
        ActivityKind.VISION -> stringResource(R.string.act_vision)
        ActivityKind.WEB_SEARCH -> if (d != null) stringResource(R.string.act_web_search_q, d) else stringResource(R.string.act_web_search)
        ActivityKind.BROWSING -> if (d != null) stringResource(R.string.act_browsing_q, d) else stringResource(R.string.act_browsing)
        ActivityKind.SPOTIFY_PLAY -> if (d != null) stringResource(R.string.act_spotify_play_q, d) else stringResource(R.string.act_spotify_play)
        ActivityKind.SPOTIFY_PAUSE -> stringResource(R.string.act_spotify_pause)
        ActivityKind.SPOTIFY_RESUME -> stringResource(R.string.act_spotify_resume)
        ActivityKind.SPOTIFY_NEXT -> stringResource(R.string.act_spotify_next)
        ActivityKind.SPOTIFY_PREVIOUS -> stringResource(R.string.act_spotify_previous)
        ActivityKind.SPOTIFY_NOW_PLAYING -> stringResource(R.string.act_spotify_now)
        ActivityKind.WEATHER -> if (d != null) stringResource(R.string.act_weather_q, d) else stringResource(R.string.act_weather)
        ActivityKind.LYRICS -> stringResource(R.string.act_lyrics)
        ActivityKind.TIMER -> if (d != null) stringResource(R.string.act_timer_q, d) else stringResource(R.string.act_timer)
        ActivityKind.ALARM -> if (d != null) stringResource(R.string.act_alarm_q, d) else stringResource(R.string.act_alarm)
        ActivityKind.OPEN_APP -> if (d != null) stringResource(R.string.act_open_app_q, d) else stringResource(R.string.act_open_app)
        ActivityKind.DATETIME -> stringResource(R.string.act_datetime)
        ActivityKind.DANCE -> stringResource(R.string.act_dance)
        ActivityKind.TOOL -> if (d != null) stringResource(R.string.act_tool_q, d) else stringResource(R.string.act_tool)
        ActivityKind.SPEAKING_PREP -> stringResource(R.string.act_speaking_prep)
    }
    return if (a.failed) stringResource(R.string.act_failed, base.trimEnd('…')) else base
}

/** Teks chip langkah di balasan chat (detail bila ada, kalau tidak nama singkat jenisnya). */
@Composable
fun stepLabel(s: ActivityStep): String {
    val k = s.activityKind
    val short = when {
        k == ActivityKind.WEB_SEARCH -> stringResource(R.string.step_web)
        k == ActivityKind.BROWSING -> stringResource(R.string.step_page)
        k.isSpotify -> stringResource(R.string.step_spotify)
        k == ActivityKind.WEATHER -> stringResource(R.string.step_weather)
        k == ActivityKind.TIMER -> stringResource(R.string.step_timer)
        k == ActivityKind.ALARM -> stringResource(R.string.step_alarm)
        k == ActivityKind.OPEN_APP -> stringResource(R.string.step_app)
        k == ActivityKind.DATETIME -> stringResource(R.string.step_time)
        k == ActivityKind.DANCE -> stringResource(R.string.step_dance)
        else -> stringResource(R.string.step_tool)
    }
    val text = s.detail?.let { if (k == ActivityKind.WEB_SEARCH || k == ActivityKind.SPOTIFY_PLAY) "“$it”" else it } ?: short
    return if (s.failed) "$text · ${stringResource(R.string.step_failed)}" else text
}

/** true bila pengguna mematikan animasi (Opsi pengembang / Aksesibilitas → hapus animasi). */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Menahan aktivitas tool yang sangat cepat (mis. get_datetime ~1 ms) minimal [minMs] supaya sempat terbaca.
 * Hanya penghalusan tampilan; status sebenarnya tetap dari siklus hidup tool.
 */
@Composable
fun rememberHeldActivity(activity: AgentActivity?, minMs: Long = 650): AgentActivity? {
    var shown by remember { mutableStateOf(activity) }
    var since by remember { mutableLongStateOf(0L) }
    LaunchedEffect(activity) {
        val cur = shown
        if (cur != null && cur.kind.isTool && activity != cur) {
            val left = minMs - (System.currentTimeMillis() - since)
            if (left > 0) delay(left)
        }
        shown = activity
        since = System.currentTimeMillis()
    }
    return shown
}

/** Tiga titik kecil berdenyut bergantian (dipakai di pil & baris status chat). */
@Composable
fun ActivityDots(color: Color, reducedMotion: Boolean, modifier: Modifier = Modifier, dot: Int = 5) {
    val t = if (reducedMotion) 0.3f else {
        val tr = rememberInfiniteTransition(label = "dots")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "t").value
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val ph = ((t - i * 0.2f) % 1f + 1f) % 1f
            val a = if (reducedMotion) 0.7f else 0.3f + 0.7f * (if (ph < 0.5f) sin(ph / 0.5f * PI).toFloat() else 0f)
            Box(Modifier.size(dot.dp).clip(CircleShape).background(color.copy(alpha = a)))
        }
    }
}

/**
 * Pil status kaca buram (ikon + label singkat + titik). Diletakkan di atas caption, dekat tombol mic —
 * jauh dari Dynamic Island di tengah atas sehingga keduanya tidak pernah bertabrakan.
 */
@Composable
fun ActivityPill(activity: AgentActivity?, dark: Boolean, modifier: Modifier = Modifier, reducedMotion: Boolean = rememberReducedMotion()) {
    AnimatedVisibility(activity != null, modifier, enter = fadeIn(tween(160)) + scaleIn(tween(160), 0.9f), exit = fadeOut(tween(200))) {
        var last by remember { mutableStateOf(activity) }
        if (activity != null) last = activity
        val a = last ?: return@AnimatedVisibility
        AnimatedContent(a, label = "pill", contentKey = { Triple(it.kind, it.detail, it.failed) },
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }) { cur ->
            val tint = ActivityVisuals.tint(cur.kind, dark, cur.failed)
            val fg = if (dark) Palette.DarkText else Palette.TitleText
            val label = activityLabel(cur)
            Row(
                Modifier.height(34.dp).frost(dark, RoundedCornerShape(17.dp)).padding(start = 10.dp, end = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }.testTag("activityPill"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = if (dark) 0.22f else 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(ActivityVisuals.icon(cur.kind, cur.failed), null, tint = tint, modifier = Modifier.size(14.dp))
                }
                Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp).testTag("activityLabel"))
                if (!cur.failed) ActivityDots(tint, reducedMotion)
            }
        }
    }
}

/**
 * Efek di sekitar wajah selama aktivitas:
 *  - THINKING: gelembung pikiran (kanan atas) berisi "…" berdenyut + dua bulatan jejak.
 *  - WEB_SEARCH: kaca pembesar mengorbit di busur atas kepala (mata ikut memindai kiri↔kanan).
 *  - BROWSING/VISION: gelembung berisi ikon halaman/gambar dengan garis "baca" menyapu.
 *  - Spotify: gelembung bertepi hijau berisi ikon not/aksi yang berdenyut + not kecil melayang.
 *  - WEATHER: gelembung berisi matahari berputar pelan + awan bergoyang.
 *  - lainnya (timer, alarm, app, jam, joget, tool, transkrip, siap bicara): gelembung + ikon berdenyut.
 * LISTENING & LYRICS tidak digambar di sini (wajah sudah punya bar dengar; lirik ada di layar pemutar).
 * Animasi hanya berjalan saat ada aktivitas; [reducedMotion] = pose diam.
 */
@Composable
fun ActivityFx(activity: AgentActivity?, dark: Boolean, modifier: Modifier = Modifier, reducedMotion: Boolean = rememberReducedMotion()) {
    val a = activity?.takeIf { it.kind != ActivityKind.LISTENING && it.kind != ActivityKind.LYRICS }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        if (wPx < 1f || hPx < 1f) return@BoxWithConstraints
        val face = remember(wPx, hPx) { FaceLayout.compute(wPx, hPx) }
        AnimatedContent(a, label = "fx", contentKey = { it?.let { x -> fxGroup(x) } },
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), 0.85f)) togetherWith fadeOut(tween(160)) }) { cur ->
            if (cur == null) { Box(Modifier); return@AnimatedContent }
            val t = if (reducedMotion) 0.25f else {
                val tr = rememberInfiniteTransition(label = "fxT")
                tr.animateFloat(0f, 1f, infiniteRepeatable(tween(if (cur.kind == ActivityKind.WEB_SEARCH) 2400 else 1400, easing = LinearEasing), RepeatMode.Restart), label = "t").value
            }
            val tint = ActivityVisuals.tint(cur.kind, dark, cur.failed)
            val portrait = hPx > wPx
            if (cur.kind == ActivityKind.WEB_SEARCH && !cur.failed) {
                // busur di atas kepala: ping-pong 200°→340°
                val ping = if (t < 0.5f) t * 2 else 2 - t * 2
                val eased = (1 - cos(ping * PI)).toFloat() / 2
                val ang = (200 + 140 * eased) * PI / 180
                val sizePx = face.r * 1.25f
                // puncak busur tetap di bawah bilah atas (tombol + Dynamic Island), terutama di landscape
                val topClear = with(density) { 64.dp.toPx() }
                val ry = (face.cy - face.r * 0.2f - sizePx / 2 - topClear).coerceIn(face.r * 0.6f, face.r * 2.3f)
                // busur yang dipipihkan melebar ke samping supaya kaca pembesar tidak menutupi mata
                val rx = face.spacing + face.r * (if (ry < face.r * 1.6f) 2.1f else 1.3f)
                val x = face.cx + rx * cos(ang).toFloat() - sizePx / 2
                val y = face.cy - face.r * 0.2f + ry * sin(ang).toFloat() - sizePx / 2
                val sizeDp = with(density) { sizePx.toDp() }
                Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(sizeDp)
                    .graphicsLayer { rotationZ = -20f + 40f * eased }
                    .clip(CircleShape).background(tint.copy(alpha = if (dark) 0.22f else 0.14f)).testTag("fxSearch"),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, null, tint = tint, modifier = Modifier.size(sizeDp * 0.72f))
                }
                return@AnimatedContent
            }
            // gelembung pikiran
            val bw = face.r * 2.3f; val bh = face.r * 1.45f
            val (bx, by) = if (portrait) (face.cx + face.spacing * 0.95f + face.r * 0.4f) to (face.cy - face.r * 2.75f)
                else (face.cx + face.spacing + face.r * 2.7f) to (face.cy - face.r * 0.85f)
            val margin = with(density) { 10.dp.toPx() }
            val cxB = bx.coerceIn(bw / 2 + margin, wPx - bw / 2 - margin)
            // jauhi bilah atas (tombol + Dynamic Island): minimal 64dp dari tepi atas
            val topClear = with(density) { 64.dp.toPx() }
            val cyB = by.coerceIn(minOf(bh / 2 + topClear, hPx - bh / 2 - margin), hPx - bh / 2 - margin)
            val bob = if (reducedMotion) 0f else sin(t * 2 * PI).toFloat() * face.r * 0.06f
            val bubbleShape = RoundedCornerShape(50)
            val accent = cur.kind.isSpotify || cur.failed
            // jejak dua bulatan kecil dari kepala ke gelembung
            val headX = if (portrait) face.cx + face.spacing * 0.75f else face.cx + face.spacing + face.r * 1.2f
            val headY = if (portrait) face.cy - face.r * 1.7f else face.cy - face.r * 0.6f
            listOf(0.3f to 0.16f, 0.62f to 0.24f).forEach { (f, rr) ->
                val px = headX + (cxB - bw * 0.3f - headX) * f
                val py = headY + (cyB + bh * 0.35f - headY) * f
                val d = face.r * rr * 2
                Box(Modifier.offset { IntOffset((px - d / 2).roundToInt(), (py - d / 2 + bob * f).roundToInt()) }
                    .size(with(density) { d.toDp() }).frost(dark, CircleShape))
            }
            Box(
                Modifier.offset { IntOffset((cxB - bw / 2).roundToInt(), (cyB - bh / 2 + bob).roundToInt()) }
                    .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                    .frost(dark, bubbleShape)
                    .then(if (accent) Modifier.background(tint.copy(alpha = if (dark) 0.16f else 0.10f), bubbleShape) else Modifier)
                    .testTag("fxBubble"),
                contentAlignment = Alignment.Center,
            ) {
                val iconDp = with(density) { (face.r * 0.8f).toDp() }
                when {
                    cur.failed -> Icon(ActivityVisuals.icon(cur.kind, true), null, tint = tint, modifier = Modifier.size(iconDp))
                    cur.kind == ActivityKind.THINKING -> ActivityDots(tint, reducedMotion, dot = with(density) { (face.r * 0.24f).toDp() }.value.roundToInt())
                    cur.kind == ActivityKind.WEATHER -> Box(Modifier.size(iconDp * 1.3f)) {
                        Icon(Icons.Outlined.WbSunny, null, tint = ActivityVisuals.WeatherAmber,
                            modifier = Modifier.size(iconDp).align(Alignment.TopEnd).graphicsLayer { rotationZ = t * 90f })
                        Icon(Icons.Filled.Cloud, null, tint = if (dark) Color(0xFFDCE3FF) else Color(0xFFB9C4E6),
                            modifier = Modifier.size(iconDp * 0.9f).align(Alignment.BottomStart)
                                .graphicsLayer { translationX = sin(t * 2 * PI).toFloat() * face.r * 0.08f })
                    }
                    cur.kind == ActivityKind.BROWSING || cur.kind == ActivityKind.VISION -> Box(contentAlignment = Alignment.Center) {
                        Icon(ActivityVisuals.icon(cur.kind), null, tint = tint, modifier = Modifier.size(iconDp))
                        // garis "baca" menyapu ke bawah
                        Box(Modifier.size(iconDp * 0.8f, with(density) { (face.r * 0.05f).toDp() }).graphicsLayer {
                            translationY = (t - 0.5f) * iconDp.toPx() * 0.7f; alpha = 0.8f
                        }.background(tint))
                    }
                    else -> {
                        val pulse = if (reducedMotion) 1f else 1f + 0.12f * sin(t * 2 * PI).toFloat()
                        Icon(ActivityVisuals.icon(cur.kind), null, tint = tint,
                            modifier = Modifier.size(iconDp).graphicsLayer { scaleX = pulse; scaleY = pulse })
                    }
                }
            }
            // not kecil melayang dari gelembung (Spotify)
            if (cur.kind.isSpotify && !cur.failed && !reducedMotion) {
                val nd = face.r * 0.55f
                repeat(2) { i ->
                    val ph = (t + i * 0.5f) % 1f
                    val nx = cxB + bw * (0.38f + 0.12f * i) + sin(ph * 2 * PI).toFloat() * face.r * 0.12f
                    val ny = maxOf(cyB - bh * 0.2f - ph * face.r * 1.1f, topClear)
                    Icon(Icons.Filled.MusicNote, null, tint = ActivityVisuals.SpotifyGreen,
                        modifier = Modifier.offset { IntOffset((nx - nd / 2).roundToInt(), (ny - nd / 2).roundToInt()) }
                            .size(with(density) { nd.toDp() }).graphicsLayer { alpha = 1f - ph })
                }
            }
        }
    }
}

/** Kelompok efek: perubahan di dalam kelompok tidak memicu animasi masuk ulang. */
private fun fxGroup(a: AgentActivity): Any = Pair(a.kind, a.failed)
