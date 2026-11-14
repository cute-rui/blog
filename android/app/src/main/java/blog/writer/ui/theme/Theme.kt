package blog.writer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import blog.writer.R

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D5786), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF0B2E54),
    secondary = Color(0xFF5E5A52), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E0D4), onSecondaryContainer = Color(0xFF1E1C19),
    tertiary = Color(0xFF8A5A14), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E6C4), onTertiaryContainer = Color(0xFF2E1F00),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF7F0), onBackground = Color(0xFF1E1C19),
    surface = Color(0xFFFAF7F0), onSurface = Color(0xFF1E1C19),
    surfaceVariant = Color(0xFFE8E2D6), onSurfaceVariant = Color(0xFF4C463E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F0E7),
    surfaceContainer = Color(0xFFEEE9DF), surfaceContainerHigh = Color(0xFFE8E2D6),
    surfaceContainerHighest = Color(0xFFE2DCCF),
    outline = Color(0xFF7D756A), outlineVariant = Color(0xFFD3CBBE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7F0), onPrimary = Color(0xFF0D2F55),
    primaryContainer = Color(0xFF244A75), onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFCBC5BA), onSecondary = Color(0xFF33302A),
    secondaryContainer = Color(0xFF4A463F), onSecondaryContainer = Color(0xFFE8E2D6),
    tertiary = Color(0xFFE8BF7A), onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF4A3510), onTertiaryContainer = Color(0xFFFCE3B6),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1712), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF161512), onBackground = Color(0xFFE9E4DA),
    surface = Color(0xFF161512), onSurface = Color(0xFFE9E4DA),
    surfaceVariant = Color(0xFF2C2A25), onSurfaceVariant = Color(0xFFCEC6B9),
    surfaceContainerLowest = Color(0xFF11100D), surfaceContainerLow = Color(0xFF1E1C19),
    surfaceContainer = Color(0xFF22201C), surfaceContainerHigh = Color(0xFF2C2A25),
    surfaceContainerHighest = Color(0xFF37342F),
    outline = Color(0xFF978F83), outlineVariant = Color(0xFF4C463E),
)

@Immutable
data class WritingColors(
    val supported: Color,
    val supportedContainer: Color,
    val uncertain: Color,
    val uncertainContainer: Color,
)

private val LightWriting = WritingColors(
    Color(0xFF2F6B3F), Color(0xFFD3ECD6), Color(0xFF6B6256), Color(0xFFECE6DC),
)
private val DarkWriting = WritingColors(
    Color(0xFF8FCB9B), Color(0xFF1D3A24), Color(0xFFB9B0A3), Color(0xFF33302A),
)

val LocalWritingColors = staticCompositionLocalOf { LightWriting }

@Composable
fun rememberWritingColorScheme(dark: Boolean, useDynamic: Boolean): ColorScheme {
    val brand = if (dark) DarkColors else LightColors
    if (!useDynamic || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return brand
    val context = LocalContext.current
    val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return brand.copy(
        primary = dyn.primary, onPrimary = dyn.onPrimary,
        primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer,
        secondary = dyn.secondary, onSecondary = dyn.onSecondary,
        secondaryContainer = dyn.secondaryContainer, onSecondaryContainer = dyn.onSecondaryContainer,
    )
}

val SerifSc = FontFamily(
    Font(R.font.noto_serif_sc_regular, FontWeight.Normal),
    Font(R.font.noto_serif_sc_semibold, FontWeight.SemiBold),
)
val Mono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

private val CjkBase = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    lineBreak = LineBreak(
        LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Default,
    ),
)

object EditorText {
    private val editing = CjkBase.copy(
        fontFamily = SerifSc,
        lineBreak = LineBreak(
            LineBreak.Strategy.Simple, LineBreak.Strictness.Strict, LineBreak.WordBreak.Default,
        ),
    )
    val body = editing.copy(fontSize = 17.sp, lineHeight = 30.sp, letterSpacing = 0.4.sp)
    val h1 = editing.copy(fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    val h2 = editing.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    val h3 = editing.copy(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    val markup = editing.copy(fontFamily = Mono, fontSize = 13.sp)

    fun body(sizeSp: Float, serif: Boolean): TextStyle {
        val size = sizeSp.coerceIn(15f, 22f)
        return editing.copy(
            fontFamily = if (serif) SerifSc else FontFamily.Default,
            fontSize = size.sp,
            lineHeight = (size * 1.75f).sp,
            letterSpacing = 0.4.sp,
        )
    }
}

private val Ui = CjkBase.copy(fontFamily = FontFamily.Default)
private val M3 = Typography()

val WritingTypography = Typography(
    headlineSmall = CjkBase.copy(fontFamily = SerifSc, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 32.sp),
    titleLarge = CjkBase.copy(fontFamily = SerifSc, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = 0.2.sp),
    bodyLarge = CjkBase.copy(fontFamily = SerifSc, fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = CjkBase.copy(fontFamily = SerifSc, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp),
    titleMedium = Ui.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = Ui.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = Ui.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Ui.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = Ui.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = Ui.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    displayLarge = M3.displayLarge, displayMedium = M3.displayMedium, displaySmall = M3.displaySmall,
    headlineLarge = M3.headlineLarge, headlineMedium = M3.headlineMedium,
)

val WritingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WritingTheme(
    dark: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWritingColors provides if (dark) DarkWriting else LightWriting) {
        MaterialTheme(
            colorScheme = rememberWritingColorScheme(dark, useDynamicColor),
            typography = WritingTypography,
            shapes = WritingShapes,
            content = content,
        )
    }
}
