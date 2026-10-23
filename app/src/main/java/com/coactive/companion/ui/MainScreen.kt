package com.coactive.companion.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.ui.face.FaceLayout
import com.coactive.companion.ui.face.withAlpha
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import com.coactive.companion.ui.face.Haptic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.ui.res.stringResource
import com.coactive.companion.R
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coactive.companion.assistant.Phase
import com.coactive.companion.assistant.UiState
import com.coactive.companion.settings.ListeningStyle
import com.coactive.companion.ui.face.AnimatedFace
import com.coactive.companion.ui.face.FaceColors

// sapaan default: R.string.greeting (lihat strings.xml)

@Composable
fun MainScreen(
    state: UiState,
    dark: Boolean,
    showInput: Boolean,
    listeningStyle: ListeningStyle,
    onSettings: () -> Unit,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onFaceTap: () -> Unit,
    onClearChat: () -> Unit,
    onToggleInput: () -> Unit,
    onFaceDoubleTap: () -> Unit = {},
    onFaceLongPress: () -> Unit = {},
    haptics: Boolean = true,
    lean: Float = 0f,
    nowPlaying: com.coactive.companion.spotify.NowPlaying? = null,
    onOpenPlayer: () -> Unit = {},
    onPlayerAction: (String) -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenSpotify: () -> Unit = {},
    onRetrySpotify: () -> Unit = {},
    onDismissSpotify: () -> Unit = {},
    /** Wajah joget (musik diputar / joget manual), sudah memperhitungkan fase asisten. */
    dancing: Boolean = false,
    danceEnabled: Boolean = true,
    manualDance: Boolean = false,
    onToggleDance: () -> Unit = {},
    /** Efek latar cuaca ambient (null = tidak ada); pemanggil sudah memastikan tidak sedang joget. */
    weatherScene: com.coactive.companion.ui.face.WeatherScene? = null,
) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val textColor = if (dark) Palette.DarkText else Palette.LightText

    val view = LocalView.current
    val tap by rememberUpdatedState(onFaceTap)
    val doubleTap by rememberUpdatedState(onFaceDoubleTap)
    val longPress by rememberUpdatedState(onFaceLongPress)
    // getar halus hanya saat reaksi benar-benar diputar (anti-spam ikut cooldown ReactionPicker)
    LaunchedEffect(state.reaction?.id) {
        val r = state.reaction ?: return@LaunchedEffect
        if (haptics) view.performHapticFeedback(when (r.reaction.haptic) {
            Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.LONG -> HapticFeedbackConstants.LONG_PRESS
        })
    }

    // panel caption bisa disembunyikan lewat tombol chat di rel kiri (murni UI)
    var showCaption by rememberSaveable { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().drawBehind { drawPearlBackground(dark) }) {
        com.coactive.companion.ui.face.WeatherAmbient(weatherScene, dark, Modifier.fillMaxSize())
        AnimatedFace(
            expression = state.expression,
            colors = if (dark) FaceColors.Dark else FaceColors.Light,
            modifier = Modifier.fillMaxSize().testTag("face")
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tap() },
                        onDoubleTap = { doubleTap() },
                        onLongPress = { longPress() },
                    )
                },
            reaction = state.reaction,
            lean = lean,
            talking = state.phase == Phase.TALKING,
            talkLevel = state.talkLevel,
            listening = state.phase == Phase.LISTENING,
            listeningStyle = listeningStyle,
            micLevel = state.micLevel,
            darkGlow = dark,
            dancing = dancing,
            weatherEvent = state.weatherEvent,
        )

        // bar atas: tombol pengaturan (kiri) & chat (kanan); island di tengah
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FrostIconButton(Icons.Filled.Tune, stringResource(R.string.settings_title), dark, onSettings, Modifier.testTag("settings"))
            Box(Modifier.weight(1f))
            FrostIconButton(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.chat_open), dark, onOpenChat, Modifier.testTag("openChat"))
        }
        // Menu "..." & rel ikon kiri/kanan (landscape) dihapus: home cukup Pengaturan + Chat + Dynamic Island + mic.
        // Aksi unik (joget manual, hapus percakapan) pindah ke Pengaturan -> Aksi cepat; kotak ketik ada di Pengaturan -> Tampilan;
        // pemutar musik lewat Dynamic Island.

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().imePadding()
                .padding(horizontal = 20.dp, vertical = if (landscape) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val caption = when {
                state.caption.isNotBlank() -> state.caption
                state.phase == Phase.SLEEPING -> ""
                state.needsSetup -> stringResource(R.string.greeting_setup)
                else -> stringResource(R.string.greeting)
            }
            state.spotifyPrompt?.let { prompt ->
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    when (prompt) {
                        com.coactive.companion.assistant.SpotifyPrompt.OPEN_SPOTIFY ->
                            androidx.compose.material3.Button(onClick = onOpenSpotify, modifier = Modifier.testTag("openSpotify")) { Text(stringResource(R.string.open_spotify)) }
                        com.coactive.companion.assistant.SpotifyPrompt.RETRY ->
                            androidx.compose.material3.Button(onClick = onRetrySpotify, modifier = Modifier.testTag("retrySpotify")) { Text(stringResource(R.string.retry)) }
                    }
                    androidx.compose.material3.TextButton(onClick = onDismissSpotify) { Text(stringResource(R.string.close)) }
                }
            }
            if (caption.isNotBlank() && showCaption) {
                Text(
                    caption, color = textColor, fontSize = if (landscape) 13.sp else 15.sp, textAlign = TextAlign.Center,
                    lineHeight = 21.sp, maxLines = if (landscape) 2 else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp).padding(bottom = if (landscape) 8.dp else 36.dp).testTag("caption"),
                )
            }
            if (showInput) InputBar(dark, state.phase, onMic, onSend, Modifier.widthIn(max = 560.dp),
                onPlus = onOpenChat) // "+" -> layar Chat (lampiran gambar/teks ada di sana)
            else MicButton(state.phase, dark, onMic)
        }

        // Dynamic Island now-playing (paling atas, tengah) — hanya bila ada lagu diputar/dijeda.
        // State terbuka/ringkas di sini supaya tidak reset bila data now-playing sempat kosong sesaat saat polling.
        var islandOpen by rememberSaveable { mutableStateOf(false) }
        var lastNp by remember { mutableStateOf(nowPlaying) }
        if (nowPlaying?.track != null) lastNp = nowPlaying
        val islandNp = if (nowPlaying?.track != null) nowPlaying else lastNp.takeIf { islandOpen }
        if (islandNp?.track != null && state.phase != Phase.SLEEPING) {
            DynamicIsland(islandNp, onPlayerAction, { islandOpen = false; onOpenPlayer() }, islandOpen, { islandOpen = it }, dark = dark)
        } else if (islandOpen && islandNp == null) islandOpen = false
    }
}

@Composable
private fun InputBar(dark: Boolean, phase: Phase, onMic: () -> Unit, onSend: (String) -> Unit, modifier: Modifier, onPlus: () -> Unit = {}) {
    var text by rememberSaveable { mutableStateOf("") }
    val hint = if (dark) Color(0xFF8592BE) else Color(0xFF6E7899)
    val fg = if (dark) Color(0xFFE3E8FF) else Color(0xFF1E2333)
    // pil kaca buram lebar: tombol "+" di depan, petunjuk "Ketik pesan...", tombol mic bulat biru di belakang
    Row(
        modifier.fillMaxWidth().height(60.dp).frost(dark, RoundedCornerShape(30.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (dark) Color(0x33A8B8FF) else Color(0xCCFFFFFF))
                .clickable(onClick = onPlus).testTag("plus"),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, stringResource(R.string.more), tint = if (dark) Palette.DarkText else Palette.TitleText, modifier = Modifier.size(22.dp)) }
        Box(Modifier.weight(1f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(stringResource(R.string.input_hint), color = hint, fontSize = 15.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = fg, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(text); text = "" }),
                modifier = Modifier.fillMaxWidth().testTag("input"),
            )
        }
        if (text.isNotBlank()) {
            BlueRoundButton(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), 44.dp, { onSend(text); text = "" }, Modifier.testTag("send"))
        } else {
            BlueRoundButton(if (phase == Phase.LISTENING || phase == Phase.TALKING) Icons.Filled.Stop else Icons.Filled.Mic, stringResource(R.string.talk),
                44.dp, onMic, Modifier.testTag("mic"), glowing = phase == Phase.LISTENING)
        }
    }
}

@Composable
private fun MicButton(phase: Phase, dark: Boolean, onMic: () -> Unit) {
    BlueRoundButton(if (phase == Phase.LISTENING) Icons.Filled.Stop else Icons.Filled.Mic, stringResource(R.string.talk), 60.dp, onMic,
        Modifier.testTag("mic"), glowing = phase == Phase.LISTENING)
}

/** Latar kaca buram: isi putih tembus pandang + garis tepi putih halus (tanpa bayangan agar tetap ringan). */
internal fun Modifier.frost(dark: Boolean, shape: Shape): Modifier =
    this.clip(shape)
        .background(if (dark) Palette.DarkFrost else Palette.Frost, shape)
        .border(1.dp, if (dark) Palette.DarkFrostBorder else Palette.FrostBorder, shape)

/** Tombol ikon bulat kaca buram (pengaturan, menu). */
@Composable
internal fun FrostIconButton(icon: ImageVector, desc: String, dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).frost(dark, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (dark) Color(0xFFB8C4EE) else Palette.TitleText, modifier = Modifier.size(22.dp)) }
}

private data class RailItem(val icon: ImageVector, val desc: String, val onClick: () -> Unit, val active: Boolean = false)

/** Rel vertikal berbentuk pil berisi tombol ikon bulat. */
@Composable
private fun FrostRail(dark: Boolean, items: List<RailItem>) {
    Column(
        Modifier.frost(dark, RoundedCornerShape(28.dp)).padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val tint = if (dark) Color(0xFFB8C4EE) else Palette.TitleText
        items.forEach { item ->
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (item.active) (if (dark) Color(0x335B7CFA) else Color(0x22FFFFFF)) else Color.Transparent)
                    .clickable(onClick = item.onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(item.icon, item.desc, tint = if (item.active) Palette.Accent else tint, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** Tombol bulat biru bergradasi (mic/kirim). [glowing] = sedang mendengarkan → cincin cahaya. */
@Composable
private fun BlueRoundButton(icon: ImageVector, desc: String, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, glowing: Boolean = false) {
    Box(
        modifier.size(size)
            .shadow(if (glowing) 10.dp else 4.dp, CircleShape, ambientColor = Palette.MicBlueDeep, spotColor = Palette.MicBlueDeep)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Palette.MicBlue, Palette.MicBlueDeep)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.45f)) }
}


/**
 * Latar "pearl/aurora": dasar vertikal lilac pucat → putih → biru pucat, beberapa cahaya radial besar
 * (lilac kiri-atas, biru kiri-bawah, pink kanan-bawah), garis lengkung iridesen tipis,
 * lalu cahaya putih "badan" besar di belakang wajah ([bodyGlow] = false untuk layar tanpa wajah, mis. pemutar).
 */
internal fun DrawScope.drawPearlBackground(dark: Boolean, bodyGlow: Boolean = true) {
    val w = size.width
    val h = size.height
    if (w < 1f || h < 1f) return
    val face = FaceLayout.compute(w, h)
    val bodyC = Offset(face.cx, face.cy + face.r * 0.9f)
    val m = maxOf(w, h)
    fun glow(color: Color, c: Offset, radius: Float, alpha: Float) {
        val rad = radius.coerceAtLeast(1f)
        drawCircle(Brush.radialGradient(0f to color.withAlpha(alpha), 0.55f to color.withAlpha(alpha * 0.45f), 1f to Color.Transparent,
            center = c, radius = rad), radius = rad, center = c)
    }
    if (dark) {
        drawRect(Brush.verticalGradient(listOf(Palette.DarkBg, Palette.DarkBgBottom)))
        glow(Color(0xFF2A2F66), Offset(w * 0.08f, h * 0.1f), m * 0.45f, 0.5f)
        glow(Color(0xFF1C3566), Offset(w * 0.1f, h * 0.95f), m * 0.4f, 0.45f)
        if (bodyGlow) glow(Palette.DarkBodyGlow, bodyC, minOf(w, h) * 0.75f, 0.8f)
        return
    }
    drawRect(Brush.verticalGradient(0f to Palette.PearlTop, 0.5f to Palette.PearlMid, 1f to Palette.PearlBottom))
    glow(Palette.AuroraLilac, Offset(w * 0.1f, h * 0.08f), m * 0.42f, 0.9f)
    glow(Palette.AuroraBlue, Offset(w * 0.06f, h * 0.92f), m * 0.38f, 0.95f)
    glow(Palette.AuroraPink, Offset(w * 0.95f, h * 0.9f), m * 0.32f, 0.7f)
    glow(Palette.AuroraBlue, Offset(w * 0.9f, h * 0.12f), m * 0.25f, 0.5f)
    // sapuan iridesen: busur lebar samar + busur tipis putih→lilac→biru (kesan kilau mutiara)
    val haze = Brush.linearGradient(listOf(Color.White.withAlpha(0f), Color.White.withAlpha(0.55f), Palette.AuroraLilac.withAlpha(0.35f), Color.White.withAlpha(0f)))
    drawArc(haze, startAngle = 190f, sweepAngle = 130f, useCenter = false,
        topLeft = Offset(-m * 0.22f, -m * 0.3f), size = Size(m * 0.5f, m * 0.5f), style = Stroke(m * 0.03f, cap = StrokeCap.Round))
    drawArc(haze, startAngle = 110f, sweepAngle = 100f, useCenter = false,
        topLeft = Offset(-m * 0.28f, h - m * 0.22f), size = Size(m * 0.48f, m * 0.48f), style = Stroke(m * 0.025f, cap = StrokeCap.Round))
    val streak = Brush.linearGradient(listOf(Color.White.withAlpha(0f), Color.White.withAlpha(0.85f), Palette.AuroraLilac.withAlpha(0.6f),
        Palette.AuroraBlue.withAlpha(0.5f), Color.White.withAlpha(0f)))
    drawArc(streak, startAngle = 200f, sweepAngle = 110f, useCenter = false,
        topLeft = Offset(-m * 0.25f, -m * 0.32f), size = Size(m * 0.55f, m * 0.55f), style = Stroke(m * 0.008f, cap = StrokeCap.Round))
    drawArc(streak, startAngle = 120f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(-m * 0.3f, h - m * 0.2f), size = Size(m * 0.5f, m * 0.5f), style = Stroke(m * 0.007f, cap = StrokeCap.Round))
    drawArc(streak, startAngle = 210f, sweepAngle = 80f, useCenter = false,
        topLeft = Offset(w - m * 0.18f, h - m * 0.22f), size = Size(m * 0.5f, m * 0.5f), style = Stroke(m * 0.007f, cap = StrokeCap.Round))
    // cahaya putih "badan" di tengah, wajah duduk di atas massa putih bercahaya
    if (!bodyGlow) return
    val bodyR = (minOf(w, h) * 0.78f).coerceAtLeast(1f)
    drawOval(
        Brush.radialGradient(0f to Palette.BodyGlow.withAlpha(0.95f), 0.5f to Palette.BodyGlow.withAlpha(0.7f),
            0.8f to Palette.BodyGlow.withAlpha(0.25f), 1f to Color.Transparent, center = bodyC, radius = bodyR),
        topLeft = Offset(bodyC.x - bodyR, bodyC.y - bodyR), size = Size(bodyR * 2, bodyR * 2),
    )
}
