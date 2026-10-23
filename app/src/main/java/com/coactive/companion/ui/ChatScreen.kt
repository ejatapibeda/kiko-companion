package com.coactive.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coactive.companion.R
import com.coactive.companion.chat.AttachmentKind
import com.coactive.companion.chat.ChatAttachment
import com.coactive.companion.chat.PendingAttachment
import com.coactive.companion.chat.StoredChatMessage
import com.coactive.companion.ui.face.FaceCanvas
import com.coactive.companion.ui.face.FaceColors
import com.coactive.companion.ui.face.FaceParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layar chat penuh: gelembung user (kanan, biru) & asisten (kiri, kaca buram), riwayat permanen,
 * lampiran gambar/teks, indikator mengetik, hapus riwayat dengan konfirmasi.
 * Latar & warna mengikuti gaya aplikasi (pearl/aurora, kaca buram) untuk tema terang & gelap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    messages: List<StoredChatMessage>,
    sending: Boolean,
    pending: PendingAttachment?,
    assistantName: String,
    dark: Boolean,
    onSend: (String) -> Unit,
    onPickImage: (Uri) -> Unit,
    onPickText: (Uri) -> Unit,
    onRemovePending: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val fg = if (dark) Color(0xFFE3E8FF) else Palette.TitleText
    val muted = if (dark) Palette.DarkText else Palette.LightText
    var confirmClear by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val itemCount = messages.size + (if (sending) 1 else 0)
    LaunchedEffect(itemCount) { if (itemCount > 0) list.animateScrollToItem(itemCount - 1) }
    LaunchedEffect(notice) { notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); onNoticeShown() } }

    Box(Modifier.fillMaxSize().drawBehind { drawPearlBackground(dark, bodyGlow = false) }.testTag("chat")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // bar atas: kembali, wajah mini + judul, hapus riwayat
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FrostIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), dark, onBack, Modifier.testTag("chatBack"))
                Box(Modifier.padding(start = 12.dp).size(40.dp).frost(dark, CircleShape)) {
                    FaceCanvas(FaceParams(), if (dark) FaceColors.Dark else FaceColors.Light, Modifier.fillMaxSize().padding(4.dp))
                }
                Text(stringResource(R.string.chat_title, assistantName), color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                if (messages.isNotEmpty()) FrostIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.chat_clear), dark,
                    { confirmClear = true }, Modifier.testTag("chatClear"))
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (messages.isEmpty() && !sending) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp)) { FaceCanvas(FaceParams.of(com.coactive.companion.ui.face.Expression.HAPPY),
                            if (dark) FaceColors.Dark else FaceColors.Light, Modifier.fillMaxSize()) }
                        Text(stringResource(R.string.chat_empty_title, assistantName), color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Text(stringResource(R.string.chat_empty_body), color = muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                            lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp).widthIn(max = 360.dp))
                    }
                }
                LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).testTag("chatList"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        Bubble(m, dark, fg, muted) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("chat", m.text))
                            Toast.makeText(ctx, ctx.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (sending) item(key = "typing") { TypingBubble(assistantName, dark, muted) }
                }
            }

            Composer(dark, fg, muted, pending, sending, onSend, onPickImage, onPickText, onRemovePending)
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.chat_clear_confirm_title)) },
        text = { Text(stringResource(R.string.chat_clear_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, modifier = Modifier.testTag("chatClearConfirm")) {
            Text(stringResource(R.string.chat_clear_confirm_yes)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: StoredChatMessage, dark: Boolean, fg: Color, muted: Color, onLongPress: () -> Unit) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (user) 20.dp else 6.dp, bottomEnd = if (user) 6.dp else 20.dp)
        val base = Modifier.widthIn(max = 320.dp).clip(shape)
        val bg = when {
            user -> base.background(Brush.linearGradient(listOf(Palette.MicBlue, Palette.MicBlueDeep)))
            m.error -> base.background(if (dark) Color(0x55E5737F) else Color(0x33E5737F))
            else -> base.frost(dark, shape)
        }
        Column(bg.combinedClickable(onClick = {}, onLongClick = onLongPress).padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(if (user) "bubbleUser" else "bubbleAssistant")) {
            m.attachment?.let { AttachmentView(it, user, dark, muted) }
            if (m.text.isNotBlank()) {
                if (m.attachment != null) Spacer(Modifier.size(8.dp))
                Text(if (user) androidx.compose.ui.text.AnnotatedString(m.text) else simpleMarkdown(m.text), color = if (user) Color.White else fg, fontSize = 15.sp, lineHeight = 21.sp)
            }
            if (!user && m.sources.isNotEmpty()) SourceLinks(m.sources, dark, muted)
        }
    }
}

/** Link sumber hasil web_search di bawah jawaban asisten (ketuk = buka di browser). */
@Composable
private fun SourceLinks(sources: List<com.coactive.companion.search.WebSource>, dark: Boolean, muted: Color) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val link = if (dark) Color(0xFF9DB4FF) else Palette.MicBlueDeep
    Column(Modifier.padding(top = 8.dp).testTag("chatSources")) {
        Text(stringResource(R.string.chat_sources), color = muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        sources.forEachIndexed { i, src ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append("${i + 1}. ") }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = link, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(src.title.take(70))
                    }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append(" · " + src.host) }
                },
                fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .clickable { runCatching { uri.openUri(src.url) } }.padding(vertical = 3.dp).testTag("chatSource"),
            )
        }
    }
}

@Composable
private fun AttachmentView(a: ChatAttachment, user: Boolean, dark: Boolean, muted: Color) {
    when (a.kind) {
        AttachmentKind.IMAGE -> {
            val img = rememberFileThumb(a.path)
            Box(Modifier.widthIn(max = 240.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0x22000000)).testTag("bubbleImage")) {
                if (img != null) Image(img, stringResource(R.string.chat_image), contentScale = ContentScale.Fit)
                else Icon(Icons.Filled.Image, null, tint = Color.White, modifier = Modifier.size(48.dp).padding(8.dp))
            }
        }
        AttachmentKind.TEXT -> FileChip(a.name,
            if (a.truncated) stringResource(R.string.chat_file_truncated, a.chars) else stringResource(R.string.chat_file_chars, a.chars),
            onBlue = user, dark = dark, muted = muted)
    }
}

@Composable
private fun FileChip(name: String, sub: String, onBlue: Boolean, dark: Boolean, muted: Color, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (onBlue) Color(0x33FFFFFF) else if (dark) Color(0x22A8B8FF) else Color(0x14303A66))
            .padding(horizontal = 10.dp, vertical = 8.dp).testTag("fileChip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Description, null, tint = if (onBlue) Color.White else Palette.MicBlueDeep, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 8.dp).widthIn(max = 200.dp)) {
            Text(name, color = if (onBlue) Color.White else (if (dark) Color(0xFFE3E8FF) else Palette.TitleText),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = if (onBlue) Color(0xDDFFFFFF) else muted, fontSize = 11.sp, maxLines = 1)
        }
        trailing?.invoke()
    }
}

@Composable
private fun TypingBubble(name: String, dark: Boolean, muted: Color) {
    val tr = rememberInfiniteTransition(label = "typing")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
        Row(Modifier.frost(dark, shape).padding(horizontal = 16.dp, vertical = 14.dp).testTag("typing"),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                val ph = ((t - i * 0.18f) % 1f + 1f) % 1f
                val lift = if (ph < 0.4f) kotlin.math.sin(ph / 0.4f * Math.PI).toFloat() else 0f
                Box(Modifier.offset(y = (-4 * lift).dp).size(8.dp).clip(CircleShape)
                    .background(Palette.MicBlueDeep.copy(alpha = 0.45f + 0.55f * lift)))
            }
        }
        Text(stringResource(R.string.chat_typing, name), color = muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Composer(
    dark: Boolean, fg: Color, muted: Color, pending: PendingAttachment?, sending: Boolean,
    onSend: (String) -> Unit, onPickImage: (Uri) -> Unit, onPickText: (Uri) -> Unit, onRemovePending: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(onPickImage) }
    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPickText) }
    val canSend = (text.isNotBlank() || pending != null) && !sending

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pending != null) {
            Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 8.dp)) {
                val remove: @Composable () -> Unit = {
                    Box(Modifier.padding(start = 6.dp).size(28.dp).clip(CircleShape).clickable(onClick = onRemovePending).testTag("removeAttachment"),
                        contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, stringResource(R.string.chat_remove_attachment), tint = muted, modifier = Modifier.size(18.dp)) }
                }
                when (pending.kind) {
                    AttachmentKind.IMAGE -> Row(Modifier.frost(dark, RoundedCornerShape(14.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val img = rememberFileThumb(pending.file.absolutePath)
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22000000)).testTag("pendingImage")) {
                            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(pending.name, color = fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 10.dp).widthIn(max = 180.dp))
                        remove()
                    }
                    AttachmentKind.TEXT -> Box(Modifier.frost(dark, RoundedCornerShape(14.dp)).padding(4.dp)) {
                        FileChip(pending.name,
                            if (pending.truncated) stringResource(R.string.chat_file_truncated, pending.chars) else stringResource(R.string.chat_file_chars, pending.chars),
                            onBlue = false, dark = dark, muted = muted, trailing = remove)
                    }
                }
            }
        }
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(min = 56.dp).frost(dark, RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (dark) Color(0x33A8B8FF) else Color(0xCCFFFFFF))
                        .clickable { menu = true }.testTag("chatAttach"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, stringResource(R.string.chat_attach), tint = if (dark) Palette.DarkText else Palette.TitleText, modifier = Modifier.size(22.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_image)) }, leadingIcon = { Icon(Icons.Filled.Image, null) },
                        onClick = { menu = false; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.testTag("attachImage"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_text)) }, leadingIcon = { Icon(Icons.Filled.Description, null) },
                        onClick = { menu = false; textPicker.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "text/*")) },
                        modifier = Modifier.testTag("attachText"))
                }
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(stringResource(R.string.chat_hint), color = muted, fontSize = 15.sp)
                BasicTextField(
                    value = text, onValueChange = { text = it }, maxLines = 5,
                    textStyle = TextStyle(color = fg, fontSize = 15.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(Palette.MicBlueDeep),
                    modifier = Modifier.fillMaxWidth().testTag("chatInput"),
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (canSend) Brush.linearGradient(listOf(Palette.MicBlue, Palette.MicBlueDeep)) else SolidColor(muted.copy(alpha = 0.25f)))
                    .clickable(enabled = canSend) { onSend(text); text = "" }.testTag("chatSend"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send), tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Thumbnail dari file lokal (diperkecil ±512 px, di-decode di IO). */
@Composable
private fun rememberFileThumb(path: String): ImageBitmap? = produceState<ImageBitmap?>(null, path) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(path)
            if (!f.exists()) return@runCatching null
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, b)
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= 512) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()
    }
}.value


/** Markdown ringan untuk balasan asisten: tebal (**x**), miring (*x* atau _x_), kode (`x`). Sisanya apa adanya. */
internal fun simpleMarkdown(src: String): androidx.compose.ui.text.AnnotatedString {
    val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![\w*])\*(?!\s)([^*\n]+?)\*(?!\w)|(?<!\w)_(?!\s)([^_\n]+?)_(?!\w)""")
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        for (m in re.findAll(src)) {
            append(src.substring(i, m.range.first))
            val g = m.groupValues
            val style = when {
                g[1].isNotEmpty() -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
                g[2].isNotEmpty() -> androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                else -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
            withStyle(style) { append(g.drop(1).first { it.isNotEmpty() }) }
            i = m.range.last + 1
        }
        append(src.substring(i))
    }
}
