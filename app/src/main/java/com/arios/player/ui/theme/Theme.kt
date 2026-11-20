package com.arios.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.arios.player.R

/** An accent family; everything else (surfaces, text tints, gradients) is derived from it. */
data class Accent(val id: Int, val nameAr: String, val nameEn: String, val light: Color, val main: Color, val deep: Color, val second: Color)

val ACCENTS = listOf(
    // iridescent blue + orange taken from the Flowfy logo
    Accent(6, "فلوفاي", "Flowfy", Color(0xFFDDEFFF), Color(0xFF4DB2FF), Color(0xFF2F4FE0), Color(0xFFFF8A4C)),
    Accent(0, "زمرّدي", "Emerald", Color(0xFFB8FFE0), Color(0xFF2EE6A0), Color(0xFF0FA968), Color(0xFF19C6A0)),
    Accent(1, "ملكي بنفسجي", "Royal", Color(0xFFDCD3FF), Color(0xFF9B7CFF), Color(0xFF5B3FD6), Color(0xFFC77DFF)),
    Accent(2, "محيطي", "Ocean", Color(0xFFBFE9FF), Color(0xFF3DB6FF), Color(0xFF1468D6), Color(0xFF2EE6E6)),
    Accent(3, "غروب", "Sunset", Color(0xFFFFE0C2), Color(0xFFFF8A3D), Color(0xFFD9481C), Color(0xFFFF5C7A)),
    Accent(4, "وردي", "Rose", Color(0xFFFFD1E3), Color(0xFFFF5C9A), Color(0xFFC21E62), Color(0xFFFF8AC7)),
    Accent(5, "ذهبي", "Gold", Color(0xFFFFF0C2), Color(0xFFE8B84A), Color(0xFFB07A12), Color(0xFFFFD36E)),
)

/**
 * Live colour system. Every colour is Compose state, so switching accent / AMOLED / colours-from-cover
 * recolours the whole app instantly (with animation where colours are animated).
 */
object AriosColors {
    var Black by mutableStateOf(Color(0xFF010604)); private set
    var Background by mutableStateOf(Color(0xFF03100B)); private set
    var Surface by mutableStateOf(Color(0xFF0A1A14)); private set
    var SurfaceHigh by mutableStateOf(Color(0xFF11241C)); private set
    var SurfaceHighest by mutableStateOf(Color(0xFF183126)); private set
    var Silver by mutableStateOf(Color(0xFF8DF7C9)); private set
    var SilverDim by mutableStateOf(Color(0xFF8DB3A3)); private set
    var Steel by mutableStateOf(Color(0xFF4C6C5F)); private set
    var Mint by mutableStateOf(Color(0xFF2EE6A0)); private set
    var Emerald by mutableStateOf(Color(0xFF0FA968)); private set
    var MintLight by mutableStateOf(Color(0xFFB8FFE0)); private set
    var Teal by mutableStateOf(Color(0xFF19C6A0)); private set
    var TextPrimary by mutableStateOf(Color(0xFFEFFFF8)); private set
    var TextSecondary by mutableStateOf(Color(0xFF9DBFB1)); private set
    var amoled by mutableStateOf(false); private set
    val brand: Brush get() = Brush.linearGradient(listOf(MintLight, Mint, Emerald))
    val brandHorizontal: Brush get() = Brush.horizontalGradient(listOf(lerp(MintLight, Mint, 0.4f), Mint, Emerald))
    val silverBrush: Brush get() = brand

    /** Rebuilds the palette from an accent (or a colour taken from the album art). */
    fun apply(a: Accent, black: Boolean) {
        amoled = black
        MintLight = a.light; Mint = a.main; Emerald = a.deep; Teal = a.second
        Silver = lerp(a.main, Color.White, 0.45f)
        val tint = a.deep
        Black = if (black) Color.Black else lerp(Color(0xFF010101), tint, 0.04f)
        Background = if (black) Color.Black else lerp(Color(0xFF020202), tint, 0.09f)
        Surface = if (black) Color(0xFF0A0A0A) else lerp(Color(0xFF080808), tint, 0.14f)
        SurfaceHigh = if (black) Color(0xFF141414) else lerp(Color(0xFF0E0E0E), tint, 0.19f)
        SurfaceHighest = if (black) Color(0xFF1E1E1E) else lerp(Color(0xFF141414), tint, 0.24f)
        SilverDim = lerp(Color(0xFF9AA0A6), a.main, 0.25f)
        Steel = lerp(Color(0xFF555A60), a.deep, 0.3f)
        TextPrimary = lerp(Color(0xFFF7F7F7), a.light, 0.12f)
        TextSecondary = lerp(Color(0xFFA6ABB0), a.main, 0.2f)
    }

    /** Accent derived from an arbitrary colour (used by "colours from the cover"). */
    fun fromColor(c: Color): Accent {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        fun hsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
        val sat = hsv[1].coerceAtLeast(0.45f)
        return Accent(-1, "", "", hsv(hsv[0], sat * 0.35f, 1f), hsv(hsv[0], sat, 0.95f), hsv(hsv[0], sat, 0.62f), hsv((hsv[0] + 25) % 360, sat, 0.9f))
    }
}

/** IBM Plex Sans Arabic SemiBold – used for lyrics and headings (supports Arabic + Latin). */
val PlexArabic = FontFamily(Font(R.font.plex_arabic_semibold, FontWeight.SemiBold))

private fun scheme() = darkColorScheme(
    primary = AriosColors.Silver,
    onPrimary = AriosColors.Black,
    primaryContainer = AriosColors.SurfaceHighest,
    onPrimaryContainer = AriosColors.TextPrimary,
    secondary = AriosColors.Mint,
    tertiaryContainer = AriosColors.Emerald,
    onSecondary = AriosColors.Black,
    secondaryContainer = lerp(AriosColors.Emerald, Color.Black, 0.6f),
    onSecondaryContainer = AriosColors.MintLight,
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
    outline = lerp(AriosColors.Emerald, Color.Black, 0.5f),
    outlineVariant = AriosColors.SurfaceHighest,
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
    MaterialTheme(colorScheme = scheme(), typography = typography, content = content)
}

fun lyricStyle(size: Int) = TextStyle(fontFamily = PlexArabic, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.38f).sp)
