package com.coactive.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val LightBg = Color(0xFFF3F4F9)
    val LightBgBottom = Color(0xFFE9ECF6)
    val DarkBg = Color(0xFF0E1424)
    val DarkBgBottom = Color(0xFF0A0F1C)
    val Accent = Color(0xFF5B7CFA)
    // teks terang: slate-indigo pekat agar jelas di latar pearl (kontras ≥ AA); sebelumnya 0xFF7D8BC7 terlalu pudar
    val LightText = Color(0xFF3D4668)
    /** Teks sekunder terang (perangkat, waktu, label kecil) — sedikit lebih muda dari [LightText], tetap ≥ AA. */
    val LightMuted = Color(0xFF59638A)
    val DarkText = Color(0xFF9DB0F0)

    // latar "pearl/aurora" (terang): dasar hampir putih dengan sapuan lilac & biru pucat
    val PearlTop = Color(0xFFF3F0FB)
    val PearlMid = Color(0xFFF8F8FD)
    val PearlBottom = Color(0xFFEDF1FC)
    val AuroraLilac = Color(0xFFE2D9F8)
    val AuroraBlue = Color(0xFFD6E2FC)
    val AuroraPink = Color(0xFFF4E3F0)
    /** Cahaya putih lembut "badan" di belakang wajah. */
    val BodyGlow = Color(0xFFFFFFFF)
    /** Cahaya badan versi gelap (biru malam lembut). */
    val DarkBodyGlow = Color(0xFF1F2A4A)

    // kaca buram (frosted) untuk pil/tombol
    val Frost = Color(0x9EFFFFFF)
    val FrostBorder = Color(0xE6FFFFFF)
    val DarkFrost = Color(0x99182038)
    val DarkFrostBorder = Color(0x33A8B8FF)

    // tombol mic bulat biru
    val MicBlue = Color(0xFF9BB0FF)
    val MicBlueDeep = Color(0xFF6F8BFB)
    val TitleText = Color(0xFF1E2336)

    // Dynamic Island: terang = kaca pearl hampir opak (teks gelap tetap kontras di atas wajah/cuaca), gelap = kapsul gelap
    val IslandLightBg = Color(0xEBFBFBFF)
    val IslandDarkBg = Color(0xE60E0F14)
    val IslandDarkText = Color(0xFFF2F3F8)
    val IslandDarkMuted = Color(0xFF9EA3B5)
    val IslandDarkAccent = Color(0xFF8FA2FF)
}

@Composable
fun CompanionTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(primary = Palette.Accent, background = Palette.DarkBg, surface = Color(0xFF151C30))
    else lightColorScheme(primary = Palette.Accent, background = Palette.LightBg, surface = Color.White)
    MaterialTheme(colorScheme = scheme, content = content)
}
