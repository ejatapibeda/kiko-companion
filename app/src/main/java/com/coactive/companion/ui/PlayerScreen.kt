package com.coactive.companion.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.LyricsLine
import com.coactive.companion.spotify.LyricsResponse
import com.coactive.companion.spotify.SpotifyResult
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.res.stringResource
import com.coactive.companion.R
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.coactive.companion.ui.face.withAlpha
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Layar pemutar gaya "Now Playing" iOS: sampul besar, judul/artis, progress bergelombang (gaya One UI), kontrol.
 * Saat terlihat, now-playing di-poll tiap 5 dtk (lihat NowPlayingPoller); di antaranya posisi diperkirakan lokal.
 * [artwork] hanya untuk pratinjau/tes (menimpa sampul dari [com.coactive.companion.spotify.SpotifyTrack.imageUrl]).
 */
@Composable
fun PlayerScreen(
    np: NowPlaying?, dark: Boolean, message: String?, onAction: (String) -> Unit, onOpenSpotify: () -> Unit, onBack: () -> Unit,
    artwork: ImageBitmap? = null,
    /** Pemuat lirik (dari ViewModel → controller aktif); null = lirik tidak tersedia. */
    loadLyrics: (suspend (String) -> SpotifyResult<LyricsResponse>)? = null,
    /** Untuk pratinjau/tes: panel lirik langsung terbuka. */
    showLyricsInitially: Boolean = false,
) {
    val t = np?.track
    val loaded = rememberAlbumArt(t?.imageUrl)
    val art = artwork ?: loaded
    val playing = np?.isPlaying == true
    val titleColor = if (dark) Color(0xFFE9EDFF) else Palette.TitleText
    val muted = if (dark) Palette.DarkText else Palette.LightText
    // teks sekunder (perangkat, waktu, label): di terang pakai warna solid ≥ AA, bukan alpha rendah yang pudar
    val secondary = if (dark) Palette.DarkText.copy(alpha = 0.75f) else Palette.LightMuted

    // panel lirik: dimuat saat dibuka / lagu berganti; gagal atau 404 → "Lirik tidak tersedia"
    var showLyrics by rememberSaveable { mutableStateOf(showLyricsInitially) }
    val uri = t?.uri
    var lyrics by remember(uri) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(uri, showLyrics) {
        if (!showLyrics || lyrics !is LyricsState.Loading) return@LaunchedEffect
        val load = loadLyrics
        lyrics = if (uri.isNullOrBlank() || load == null) LyricsState.Unavailable else try {
            when (val r = load(uri)) {
                is SpotifyResult.Ok -> if (r.value.lines.any { it.text.isNotBlank() }) LyricsState.Ready(r.value) else LyricsState.Unavailable
                is SpotifyResult.Failure -> LyricsState.Unavailable
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { LyricsState.Unavailable }
    }

    // posisi "hidup": dasar dari poller, lalu maju sendiri tiap 0,5 dtk selama diputar (dibatasi durasi)
    val dur = t?.durationMs?.takeIf { it > 0 }
    val polledPos = np?.positionMs
    var livePos by remember { mutableLongStateOf(polledPos ?: 0L) }
    LaunchedEffect(polledPos, playing, dur) {
        val base = polledPos ?: return@LaunchedEffect
        livePos = base
        if (!playing || dur == null) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            delay(500)
            livePos = (base + System.currentTimeMillis() - start).coerceAtMost(dur)
        }
    }

    Box(Modifier.fillMaxSize().drawBehind { drawPearlBackground(dark, bodyGlow = false) }.testTag("player")) {
        AmbientArt(art, dark)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            val boxW = maxWidth
            val boxH = maxHeight
            // isi info + progress + kontrol (dipakai di kedua orientasi)
            val info: @Composable (Modifier) -> Unit = { mod ->
                Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(t?.title ?: stringResource(R.string.player_nothing), color = titleColor, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                            lineHeight = 28.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (t != null) Text(t.artistText, color = muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp))
                        np?.deviceName?.let { Text(stringResource(R.string.player_on_device, it), color = secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    if (dur != null && polledPos != null) {
                        Spacer(Modifier.height(18.dp))
                        WavyProgressBar(
                            progress = livePos.toFloat() / dur, playing = playing,
                            color = Palette.Accent, trackColor = if (dark) Color(0x33A8B8FF) else Color(0x267D8BC7),
                            modifier = Modifier.fillMaxWidth().height(28.dp).testTag("wavyProgress"),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatMs(livePos), color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(formatMs(dur), color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(if (landscape) 10.dp else 18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onAction("previous") }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), tint = titleColor, modifier = Modifier.size(40.dp))
                        }
                        // tombol putar/jeda bulat besar beraksen
                        Box(
                            Modifier.size(76.dp)
                                .shadow(12.dp, CircleShape, ambientColor = Palette.Accent, spotColor = Palette.Accent)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Palette.MicBlue, Palette.Accent)))
                                .clickable { onAction(if (playing) "pause" else "resume") }
                                .testTag("playPause"),
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.player_play_pause), tint = Color.White, modifier = Modifier.size(40.dp)) }
                        IconButton(onClick = { onAction("next") }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.SkipNext, stringResource(R.string.player_next), tint = titleColor, modifier = Modifier.size(40.dp))
                        }
                    }
                    message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = muted, fontSize = 13.sp, textAlign = TextAlign.Center) }
                    if (np == null) { Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onOpenSpotify) { Text(stringResource(R.string.open_spotify)) } }
                }
            }

            if (landscape) {
                // sisakan ruang bar atas (tombol kembali) agar sampul tidak menabraknya
                val artSize = min(min(maxHeight - 96.dp, 300.dp), maxWidth * 0.4f)
                Row(
                    Modifier.align(Alignment.Center).widthIn(max = 900.dp).padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showLyrics) LyricsPanel(lyrics, livePos, dark, titleColor, Modifier.width(boxW * 0.42f).height(artSize))
                    else ArtCard(art, artSize)
                    Spacer(Modifier.width(40.dp))
                    info(Modifier.widthIn(max = 420.dp).weight(1f, fill = false))
                }
            } else {
                val artSize = min(min(maxWidth - 56.dp, 320.dp), maxHeight * 0.42f)
                Column(
                    Modifier.align(Alignment.Center).widthIn(max = 480.dp).padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(40.dp))
                    if (showLyrics) LyricsPanel(lyrics, livePos, dark, titleColor,
                        Modifier.fillMaxWidth().height(maxOf(artSize, boxH * 0.45f)))
                    else ArtCard(art, artSize)
                    Spacer(Modifier.height(32.dp))
                    info(Modifier.fillMaxWidth())
                }
            }

            // bar atas: tombol kembali + label kecil
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FrostIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), dark, onBack)
                Text(stringResource(R.string.player_now_playing), color = secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                // tombol "Lirik": aktif = ikon beraksen; tutup lalu buka lagi = coba muat ulang bila tadi gagal
                Box(
                    Modifier.size(48.dp).frost(dark, CircleShape)
                        .background(if (showLyrics) Palette.Accent.withAlpha(if (dark) 0.3f else 0.16f) else Color.Transparent, CircleShape)
                        .clickable {
                            if (showLyrics && lyrics is LyricsState.Unavailable) lyrics = LyricsState.Loading
                            showLyrics = !showLyrics
                        }.testTag("lyricsToggle"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Lyrics, stringResource(R.string.player_lyrics), modifier = Modifier.size(22.dp),
                        tint = if (showLyrics) Palette.Accent else if (dark) Color(0xFFB8C4EE) else Palette.TitleText)
                }
            }
        }
    }
}

/** Status panel lirik. */
private sealed interface LyricsState {
    data object Loading : LyricsState
    data class Ready(val data: LyricsResponse) : LyricsState
    data object Unavailable : LyricsState
}

/** Baris aktif = baris terakhir dengan start_ms ≤ posisi; -1 bila belum mulai / lirik tak tersinkron. */
internal fun activeLyricIndex(lines: List<LyricsLine>, synced: Boolean, positionMs: Long): Int =
    if (!synced) -1 else lines.indexOfLast { it.startMs <= positionMs }

/**
 * Panel lirik gaya iOS: kartu kaca buram, baris besar tebal; baris aktif beraksen & penuh, lainnya redup,
 * baris aktif digulir ke tengah. Tepi atas/bawah memudar. Lirik tak tersinkron = daftar biasa tanpa sorotan.
 */
@Composable
private fun LyricsPanel(state: LyricsState, positionMs: Long, dark: Boolean, textColor: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(modifier.frost(dark, shape).testTag("lyricsPanel"), contentAlignment = Alignment.Center) {
        val hint = if (dark) Palette.DarkText else Palette.LightText
        when (state) {
            LyricsState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ActivityPill(com.coactive.companion.assistant.AgentActivity(com.coactive.companion.assistant.ActivityKind.LYRICS), dark)
            }
            LyricsState.Unavailable -> Text(stringResource(R.string.player_lyrics_unavailable), color = hint, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("lyricsUnavailable"))
            is LyricsState.Ready -> {
                val lines = state.data.lines
                val synced = state.data.synced
                val active = activeLyricIndex(lines, synced, positionMs)
                // mulai langsung di baris aktif (padding atas ≈ setengah tinggi → baris aktif sudah dekat tengah sejak frame pertama)
                val list = rememberLazyListState(initialFirstVisibleItemIndex = active.coerceAtLeast(0))
                var first by remember { mutableStateOf(true) }
                // gulir baris aktif ke tengah: lompatan pertama instan, berikutnya animasi halus
                LaunchedEffect(active) {
                    if (active < 0) return@LaunchedEffect
                    if (list.layoutInfo.visibleItemsInfo.none { it.index == active }) list.scrollToItem(active)
                    val info = list.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == active } ?: return@LaunchedEffect
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    val delta = (item.offset + item.size / 2 - center).toFloat()
                    if (first) list.scrollBy(delta) else list.animateScrollBy(delta, tween(450))
                    first = false
                }
                val padV = if (synced) (maxHeight / 2 - 24.dp).coerceAtLeast(16.dp) else 20.dp
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = padV),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn)
                        },
                ) {
                    itemsIndexed(lines) { i, line ->
                        val isActive = i == active
                        val alpha = when {
                            !synced -> 0.9f
                            isActive -> 1f
                            else -> if (dark) 0.42f else 0.4f
                        }
                        Text(
                            line.text.ifBlank { "♪" },
                            color = if (isActive) Palette.Accent else textColor.withAlpha(alpha),
                            fontSize = if (synced) 21.sp else 18.sp, lineHeight = if (synced) 27.sp else 24.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().then(if (isActive) Modifier.testTag("lyricActive") else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/** Kartu sampul besar: sudut membulat 24dp + bayangan lembut beraksen. */
@Composable
private fun ArtCard(art: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(24.dp)
    AlbumArtBox(
        art, Modifier.size(size).shadow(28.dp, shape, ambientColor = Palette.Accent.withAlpha(0.5f), spotColor = Palette.Accent.withAlpha(0.6f)).testTag("albumArt"),
        shape, glyph = size * 0.28f,
    )
}

/**
 * Warna ambien dari sampul: versi besar, buram & redup di belakang semuanya (blur hanya API 31+;
 * di bawah itu cukup latar pearl saja). Ditutup sapuan terang/gelap agar teks tetap terbaca.
 */
@Composable
private fun AmbientArt(art: ImageBitmap?, dark: Boolean) {
    if (art == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Image(art, null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().scale(1.4f).blur(90.dp).alpha(if (dark) 0.4f else 0.5f))
    val wash = if (dark) listOf(Palette.DarkBg.withAlpha(0.55f), Palette.DarkBg.withAlpha(0.25f), Palette.DarkBgBottom.withAlpha(0.8f))
    else listOf(Color.White.withAlpha(0.55f), Color.White.withAlpha(0.2f), Color.White.withAlpha(0.7f))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(wash)))
}

/**
 * Progress bergelombang gaya One UI / Android 13: bagian yang sudah diputar = gelombang sinus beraksen yang
 * bergulir saat [playing], sisa = garis lurus samar; kenop bulat di titik progress. Saat dijeda amplitudo
 * mereda ke 0 (garis lurus) dan animasi berhenti. Hanya indikator visual (backend belum punya endpoint seek).
 */
@Composable
fun WavyProgressBar(progress: Float, playing: Boolean, color: Color, trackColor: Color, modifier: Modifier = Modifier) {
    val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    val shown by animateFloatAsState(p, tween(600), label = "progress")
    val amp by animateFloatAsState(if (playing) 1f else 0f, tween(500), label = "amp")
    var phase by remember { mutableFloatStateOf(0f) }
    // putaran fase hanya berjalan saat diputar (frame clock Compose otomatis berhenti saat layar tak terlihat)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) phase = (phase + (now - last) / 1_000_000_000f * WAVE_SPEED) % (2 * PI.toFloat())
            last = now
        }
    }
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val cy = size.height / 2
        val start = stroke / 2
        val end = (size.width - stroke / 2).coerceAtLeast(start)
        val px = start + (end - start) * shown.coerceIn(0f, 1f)
        val a = 3.5.dp.toPx() * amp.coerceIn(0f, 1f)
        val wl = 30.dp.toPx()
        // sisa lagu: garis lurus samar
        if (end > px) drawLine(trackColor, Offset(px, cy), Offset(end, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        // bagian terputar: gelombang (amplitudo meruncing ke 0 di dekat kenop agar menyambung mulus)
        if (px > start) {
            val path = Path()
            val step = 2f
            var x = start
            path.moveTo(x, cy)
            while (x < px) {
                x = minOf(x + step, px)
                val taper = ((px - x) / (wl * 0.5f)).coerceIn(0f, 1f) * ((x - start) / (wl * 0.25f)).coerceIn(0f, 1f)
                path.lineTo(x, cy + a * taper * sin(2 * PI.toFloat() * x / wl - phase))
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // kenop: titik beraksen dengan halo lembut
        drawCircle(color.withAlpha(0.18f), radius = 11.dp.toPx(), center = Offset(px, cy))
        drawCircle(color, radius = 7.dp.toPx(), center = Offset(px, cy))
        drawCircle(Color.White, radius = 2.5.dp.toPx(), center = Offset(px, cy))
    }
}

/** Kecepatan gulir gelombang (radian per detik). */
private const val WAVE_SPEED = 5f

/** mm:ss dari milidetik (≥ 1 jam → h:mm:ss). */
internal fun formatMs(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Chip kecil "♪ judul — artis" di layar utama; ketuk untuk membuka layar pemutar. */
@Composable
fun NowPlayingChip(np: NowPlaying, dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = np.track ?: return
    val bg = if (dark) Color(0xFF182038) else Color.White
    val fg = if (dark) Palette.DarkText else Palette.LightText
    Row(
        modifier.background(bg, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp).testTag("nowPlayingChip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (np.isPlaying) Icons.Filled.MusicNote else Icons.Filled.Pause, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(" ${t.title} — ${t.artistText}", color = fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
    }
}
