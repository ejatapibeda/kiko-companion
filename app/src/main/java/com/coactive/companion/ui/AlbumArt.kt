package com.coactive.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Muat sampul album sekali per URL (dipakai pil now-playing & layar pemutar).
 * Gagal/tanpa URL → null, pemanggil menampilkan placeholder gradasi ([AlbumArtBox]).
 */
@Composable
internal fun rememberAlbumArt(url: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, url) {
        value = null
        if (url.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                conn.getInputStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

/** Gradasi placeholder sampul (pink → lilac → periwinkle). */
internal val ArtPlaceholderBrush = Brush.linearGradient(listOf(Color(0xFFE7C9E6), Color(0xFFA9A6E8), Color(0xFF7D8BC7)))

/** Kotak sampul: gambar [art] terpotong rapi, atau gradasi + ikon not musik bila belum/tidak ada. */
@Composable
internal fun AlbumArtBox(art: ImageBitmap?, modifier: Modifier, shape: Shape, glyph: Dp) {
    Box(modifier.clip(shape).background(ArtPlaceholderBrush), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(glyph))
    }
}
