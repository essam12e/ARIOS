package com.arios.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.arios.player.R

object AriosColors {
    val Black = Color(0xFF050506)
    val Background = Color(0xFF0B0B0D)
    val Surface = Color(0xFF141417)
    val SurfaceHigh = Color(0xFF1D1E22)
    val SurfaceHighest = Color(0xFF26282D)
    val Silver = Color(0xFFD9DDE3)
    val SilverDim = Color(0xFF9EA4AD)
    val Steel = Color(0xFF6B717B)
    val Mint = Color(0xFF3DDC97) // accent taken from the original ARIOS emblem
    val TextPrimary = Color(0xFFF4F5F7)
    val TextSecondary = Color(0xFFA3A8B0)
    val silverBrush = Brush.linearGradient(listOf(Color(0xFFF7F8FA), Color(0xFFB9BEC6), Color(0xFF7D838C)))
}

/** IBM Plex Sans Arabic SemiBold – used for lyrics and headings (supports Arabic + Latin). */
val PlexArabic = FontFamily(Font(R.font.plex_arabic_semibold, FontWeight.SemiBold))

private val scheme = darkColorScheme(
    primary = AriosColors.Silver,
    onPrimary = AriosColors.Black,
    primaryContainer = AriosColors.SurfaceHighest,
    onPrimaryContainer = AriosColors.TextPrimary,
    secondary = AriosColors.Mint,
    onSecondary = AriosColors.Black,
    secondaryContainer = Color(0xFF203029),
    onSecondaryContainer = Color(0xFFBFF5DC),
    tertiary = AriosColors.SilverDim,
    background = AriosColors.Background,
    onBackground = AriosColors.TextPrimary,
    surface = AriosColors.Background,
    onSurface = AriosColors.TextPrimary,
    surfaceVariant = AriosColors.SurfaceHigh,
    onSurfaceVariant = AriosColors.TextSecondary,
    surfaceContainerLowest = AriosColors.Black,
    surfaceContainerLow = AriosColors.Surface,
    surfaceContainer = AriosColors.Surface,
    surfaceContainerHigh = AriosColors.SurfaceHigh,
    surfaceContainerHighest = AriosColors.SurfaceHighest,
    outline = Color(0xFF3A3D43),
    outlineVariant = Color(0xFF2A2C31),
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun AriosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

fun lyricStyle(size: Int) = TextStyle(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.38f).sp)
