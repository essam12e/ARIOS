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
    // palette taken from the ARIOS logo: deep green-black + emerald → mint gradient
    val Black = Color(0xFF010604)
    val Background = Color(0xFF03100B)
    val Surface = Color(0xFF0A1A14)
    val SurfaceHigh = Color(0xFF11241C)
    val SurfaceHighest = Color(0xFF183126)
    val Silver = Color(0xFF8DF7C9)       // light mint (primary on dark)
    val SilverDim = Color(0xFF8DB3A3)
    val Steel = Color(0xFF4C6C5F)
    val Mint = Color(0xFF2EE6A0)
    val Emerald = Color(0xFF0FA968)
    val MintLight = Color(0xFFB8FFE0)
    val Teal = Color(0xFF19C6A0)
    val TextPrimary = Color(0xFFEFFFF8)
    val TextSecondary = Color(0xFF9DBFB1)
    val brand = Brush.linearGradient(listOf(MintLight, Mint, Emerald))
    val brandHorizontal = Brush.horizontalGradient(listOf(Color(0xFF9DFFD6), Mint, Emerald))
    val silverBrush = brand
}

/** IBM Plex Sans Arabic SemiBold – used for lyrics and headings (supports Arabic + Latin). */
val PlexArabic = FontFamily(Font(R.font.plex_arabic_semibold, FontWeight.SemiBold))

private val scheme = darkColorScheme(
    primary = AriosColors.Silver,
    onPrimary = AriosColors.Black,
    primaryContainer = AriosColors.SurfaceHighest,
    onPrimaryContainer = AriosColors.TextPrimary,
    secondary = AriosColors.Mint,
    tertiaryContainer = AriosColors.Emerald,
    onSecondary = AriosColors.Black,
    secondaryContainer = Color(0xFF0F3A29),
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
    outline = Color(0xFF2C5A47),
    outlineVariant = Color(0xFF1A3328),
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
