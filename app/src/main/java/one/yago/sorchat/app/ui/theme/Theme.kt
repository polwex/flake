package one.yago.sorchat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.yago.sorchat.app.R

// Warm sunset: orange → coral → pink, on creamy neutrals (dark: deep aubergine).
object Sunset {
    val Orange = Color(0xFFFF9A56)
    val Coral = Color(0xFFFF5F6D)
    val Pink = Color(0xFFE8458B)
    val Plum = Color(0xFF6B2F6B)
    val Green = Color(0xFF3DBE7A)
    val Red = Color(0xFFEF4444)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFE5484D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF5C0A12),
    secondary = Color(0xFFE8763D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC8),
    onSecondaryContainer = Color(0xFF4A1F06),
    tertiary = Color(0xFFD63F82),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E6),
    onTertiaryContainer = Color(0xFF4F0A2B),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF2B1A17),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF2B1A17),
    surfaceVariant = Color(0xFFF6E3DC),
    onSurfaceVariant = Color(0xFF6E5A55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE3),
    surfaceContainerHigh = Color(0xFFF8E3DB),
    surfaceContainerHighest = Color(0xFFF2DCD3),
    outline = Color(0xFFA48D87),
    outlineVariant = Color(0xFFE6D0C9),
    error = Color(0xFFD92D20),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A80),
    onPrimary = Color(0xFF4A0A10),
    primaryContainer = Color(0xFF7A1F27),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFFB27A),
    onSecondary = Color(0xFF4A1F06),
    secondaryContainer = Color(0xFF6B3417),
    onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFFF8CBB),
    onTertiary = Color(0xFF4F0A2B),
    tertiaryContainer = Color(0xFF762049),
    onTertiaryContainer = Color(0xFFFFD8E6),
    background = Color(0xFF1C1214),
    onBackground = Color(0xFFF6E3DE),
    surface = Color(0xFF1C1214),
    onSurface = Color(0xFFF6E3DE),
    surfaceVariant = Color(0xFF3A2C2E),
    onSurfaceVariant = Color(0xFFD9C2BD),
    surfaceContainerLowest = Color(0xFF160E10),
    surfaceContainerLow = Color(0xFF231719),
    surfaceContainer = Color(0xFF2A1D20),
    surfaceContainerHigh = Color(0xFF34262A),
    surfaceContainerHighest = Color(0xFF3F3034),
    outline = Color(0xFF9F8984),
    outlineVariant = Color(0xFF52403F),
    error = Color(0xFFFF8A80),
)

@Immutable
data class Gradients(
    /** Brand gradient: headers, my message bubbles, primary buttons. */
    val sunset: Brush,
    /** Deeper version for full-screen backgrounds (welcome, calls). */
    val dusk: Brush,
)

val LocalGradients = staticCompositionLocalOf {
    Gradients(Brush.linearGradient(listOf(Sunset.Orange, Sunset.Coral, Sunset.Pink)), Brush.linearGradient(listOf(Sunset.Coral, Sunset.Pink, Sunset.Plum)))
}

private fun nunito(weight: Int) = Font(
    R.font.nunito,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800), nunito(900))

private val Base = Typography()

private fun TextStyle.nunito(weight: FontWeight = fontWeight ?: FontWeight.Normal) = copy(fontFamily = Nunito, fontWeight = weight)

private val SorchatTypography = Typography(
    displayLarge = Base.displayLarge.nunito(FontWeight.Black),
    displayMedium = Base.displayMedium.nunito(FontWeight.Black),
    displaySmall = Base.displaySmall.nunito(FontWeight.ExtraBold),
    headlineLarge = Base.headlineLarge.nunito(FontWeight.ExtraBold),
    headlineMedium = Base.headlineMedium.nunito(FontWeight.ExtraBold),
    headlineSmall = Base.headlineSmall.nunito(FontWeight.ExtraBold),
    titleLarge = Base.titleLarge.nunito(FontWeight.ExtraBold),
    titleMedium = Base.titleMedium.nunito(FontWeight.Bold),
    titleSmall = Base.titleSmall.nunito(FontWeight.Bold),
    bodyLarge = Base.bodyLarge.nunito(FontWeight.Medium).copy(fontSize = 17.sp),
    bodyMedium = Base.bodyMedium.nunito(FontWeight.Medium),
    bodySmall = Base.bodySmall.nunito(FontWeight.Medium),
    labelLarge = Base.labelLarge.nunito(FontWeight.Bold),
    labelMedium = Base.labelMedium.nunito(FontWeight.Bold),
    labelSmall = Base.labelSmall.nunito(FontWeight.SemiBold),
)

private val SorchatShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun SorchatTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val gradients = if (dark) {
        Gradients(
            sunset = Brush.linearGradient(listOf(Color(0xFFE8763D), Color(0xFFE5484D), Color(0xFFC2306F))),
            dusk = Brush.linearGradient(listOf(Color(0xFF7A2E3A), Color(0xFF5A1F49), Color(0xFF26142A))),
        )
    } else {
        Gradients(
            sunset = Brush.linearGradient(listOf(Sunset.Orange, Sunset.Coral, Sunset.Pink)),
            dusk = Brush.linearGradient(listOf(Sunset.Coral, Sunset.Pink, Sunset.Plum)),
        )
    }
    CompositionLocalProvider(LocalGradients provides gradients) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = SorchatTypography,
            shapes = SorchatShapes,
            content = content,
        )
    }
}
