package com.coactive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.coactive.companion.R
import com.coactive.companion.spotify.NowPlaying
import kotlinx.coroutines.delay

/** Warna island per tema (terang = kapsul kaca pearl, gelap = kapsul gelap tembus pandang). */
@androidx.compose.runtime.Immutable
internal data class IslandColors(
    val bg: Color, val border: Color, val text: Color, val muted: Color, val accent: Color,
    val track: Color, val shadow: Color, val scrim: Color,
) {
    companion object {
        /** Diambil dari colorScheme tema aktif + [Palette]; [dark] = hasil ThemeMode (bukan tema sistem). */
        @Composable
        fun of(dark: Boolean): IslandColors {
            val cs = androidx.compose.material3.MaterialTheme.colorScheme
            return if (dark) IslandColors(
                bg = Palette.IslandDarkBg, border = Color.White.copy(alpha = 0.08f),
                text = Palette.IslandDarkText, muted = Palette.IslandDarkMuted, accent = Palette.IslandDarkAccent,
                track = Color.White.copy(alpha = 0.16f), shadow = Color.Black, scrim = Color.Black.copy(alpha = 0.18f),
            ) else IslandColors(
                bg = Palette.IslandLightBg, border = Palette.FrostBorder,
                text = Palette.TitleText, muted = Palette.LightMuted, accent = cs.primary,
                track = cs.primary.copy(alpha = 0.18f), shadow = Palette.Accent.copy(alpha = 0.45f), scrim = Palette.TitleText.copy(alpha = 0.10f),
            )
        }
    }
}
private val CollapsedW = 148.dp
private val CollapsedH = 36.dp
private val ExpandedH = 186.dp

/**
 * "Dynamic Island" now-playing: kapsul di tengah atas, warnanya mengikuti tema (terang = kaca pearl, gelap = kapsul gelap).
 * Ringkas = sampul + judul berjalan (marquee) + equalizer. Ketuk → mengembang (spring) jadi kartu dengan
 * sampul, judul/artis, progres, dan prev/putar-jeda/next. Ketuk di luar / tombol back → menciut.
 * Ketuk sampul/judul atau ikon perbesar di kartu → layar pemutar penuh.
 * Pemanggil hanya menampilkan ini bila ada lagu (diputar atau dijeda).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DynamicIsland(
    np: NowPlaying,
    onAction: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // default: baca dari colorScheme (CompanionTheme mengikuti ThemeMode); MainScreen meneruskan `dark` langsung
    dark: Boolean = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f,
) {
    val t = np.track ?: return
    val c = IslandColors.of(dark)
    val art = rememberAlbumArt(t.imageUrl)
    if (expanded) BackHandler { onExpandedChange(false) }

    // optimistis: status remote (Spotify Connect) bisa telat beberapa detik → ikon langsung ikut aksi user
    var optimistic by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(np.isPlaying) { if (optimistic == np.isPlaying) optimistic = null }
    LaunchedEffect(optimistic) { if (optimistic != null) { delay(6000); optimistic = null } }
    val playing = optimistic ?: np.isPlaying

    // posisi "hidup" seperti di layar pemutar: dasar dari poller, maju sendiri selama diputar
    val dur = t.durationMs?.takeIf { it > 0 }
    val polledPos = np.positionMs
    var livePos by remember { mutableLongStateOf(polledPos ?: 0L) }
    LaunchedEffect(polledPos, playing, dur, expanded) {
        val base = polledPos ?: return@LaunchedEffect
        livePos = base
        if (!playing || dur == null || !expanded) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) { delay(500); livePos = (base + System.currentTimeMillis() - start).coerceAtMost(dur) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val expandedW = min(maxWidth - 24.dp, 380.dp)
        // ketuk di luar → menciut (lapisan transparan hanya ada saat terbuka)
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(c.scrim).testTag("islandScrim")
                .clickable(remember { MutableInteractionSource() }, indication = null) { onExpandedChange(false) })
        }
        val tr = updateTransition(expanded, label = "island")
        val springDp = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
        val w by tr.animateDp({ springDp }, label = "w") { if (it) expandedW else CollapsedW }
        val h by tr.animateDp({ springDp }, label = "h") { if (it) ExpandedH else CollapsedH }
        val corner by tr.animateDp({ springDp }, label = "r") { if (it) 30.dp else CollapsedH / 2 }
        val contentAlpha by tr.animateFloat({ tween(if (targetState) 220 else 120, delayMillis = if (targetState) 90 else 0) }, label = "a") { if (it) 1f else 0f }
        val shape = RoundedCornerShape(corner)

        Box(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 16.dp)
                .size(w, h)
                .shadow(if (expanded) 18.dp else 6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(shape).background(c.bg).border(1.dp, c.border, shape)
                .testTag(if (expanded) "islandExpanded" else "nowPlayingChip")
                .then(if (!expanded) Modifier.clickable { onExpandedChange(true) }
                    .semantics { contentDescription = t.title } else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!expanded || contentAlpha < 0.05f) {
                // --- ringkas ---
                Row(
                    Modifier.requiredSize(CollapsedW, CollapsedH).padding(start = 5.dp, end = 12.dp)
                        .graphicsAlpha(1f - contentAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtBox(art, Modifier.size(26.dp), CircleShape, glyph = 13.dp)
                    Text(t.title, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp).weight(1f).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
                    Equalizer(playing, c.accent, Modifier.size(16.dp, 14.dp))
                }
            }
            if (expanded || contentAlpha > 0.05f) {
                // --- kartu terbuka ---
                Column(Modifier.requiredSize(expandedW, ExpandedH).padding(16.dp).graphicsAlpha(contentAlpha)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { onExpandedChange(false); onOpenPlayer() }.testTag("islandOpenPlayer"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumArtBox(art, Modifier.size(56.dp), RoundedCornerShape(14.dp), glyph = 24.dp)
                        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                            Text(t.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
                            Text(t.artistText, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.OpenInFull, stringResource(R.string.island_open_player), tint = c.muted, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    val p = if (dur != null && polledPos != null) (livePos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    // progress bergelombang, sama dengan Player (WavyProgressBar); warna ikut tema island.
                    // Tinggi 16dp menggantikan spacer/padding lama sehingga tinggi kartu (ExpandedH) tetap.
                    WavyProgressBar(
                        progress = p, playing = playing, color = c.accent, trackColor = c.track,
                        modifier = Modifier.fillMaxWidth().height(16.dp).testTag("islandWavyProgress"),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(if (polledPos != null) formatMs(livePos) else "", color = c.muted, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(dur?.let { formatMs(it) } ?: "", color = c.muted, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IslandButton(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), 30.dp, "islandPrev", c.text) { onAction("previous") }
                        IslandButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.player_play_pause), 38.dp, "islandPlayPause", c.text) {
                            onAction(if (playing) "pause" else "resume"); optimistic = !playing
                        }
                        IslandButton(Icons.Filled.SkipNext, stringResource(R.string.player_next), 30.dp, "islandNext", c.text) { onAction("next") }
                    }
                }
            }
        }
    }
}

private fun Modifier.graphicsAlpha(a: Float) = this.alpha(a)

@Composable
private fun IslandButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, iconSize: androidx.compose.ui.unit.Dp, tag: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Equalizer mini 3 batang; diam rendah saat dijeda. */
@Composable
private fun Equalizer(playing: Boolean, color: Color, modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "eq")
    val speeds = listOf(420, 560, 360)
    val levels = speeds.mapIndexed { i, ms ->
        inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms, delayMillis = i * 60), RepeatMode.Reverse), label = "eq$i")
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        levels.forEach { lv ->
            val f = if (playing) lv.value else 0.25f
            Box(Modifier.width(3.5.dp).fillMaxHeight(f).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}
