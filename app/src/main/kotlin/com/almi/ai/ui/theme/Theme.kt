package com.almi.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almi.ai.data.preferences.AppThemeMode

/**
 * Shared visual scale for components that need a local multiplier. Most ALMI sizing is now handled
 * one level lower through an adaptive LocalDensity, so every existing dp/sp value in Studio, AI,
 * Settings, Avatar and onboarding responds consistently without each screen inventing its own math.
 */
val LocalAlmiUiScale = staticCompositionLocalOf { 1f }

private val LightColors = lightColorScheme(
    primary = Color(0xFF111318),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9EBEF),
    onPrimaryContainer = Color(0xFF111318),
    secondary = Color(0xFF69707D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEDF1),
    onSecondaryContainer = Color(0xFF252932),
    tertiary = Color(0xFF4B67FF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7EBFF),
    onTertiaryContainer = Color(0xFF16245C),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF111318),
    surface = Color(0xFFFCFCFD),
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF686E78),
    outline = Color(0xFFB9BEC7),
    outlineVariant = Color(0xFFDEE1E6),
    error = Color(0xFFEB554B),
    onError = Color.White,
    errorContainer = Color(0xFFFFE8E5),
    onErrorContainer = Color(0xFF6B1713),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF6F7F9),
    onPrimary = Color(0xFF111318),
    primaryContainer = Color(0xFF242831),
    onPrimaryContainer = Color(0xFFF6F7F9),
    secondary = Color(0xFFB7BDC8),
    onSecondary = Color(0xFF171A20),
    secondaryContainer = Color(0xFF242831),
    onSecondaryContainer = Color(0xFFE4E7EC),
    tertiary = Color(0xFF91A2FF),
    onTertiary = Color(0xFF101A4B),
    tertiaryContainer = Color(0xFF283768),
    onTertiaryContainer = Color(0xFFE1E6FF),
    background = Color(0xFF090B0F),
    onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF12151A),
    onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF1D2128),
    onSurfaceVariant = Color(0xFFADB3BE),
    outline = Color(0xFF5D6470),
    outlineVariant = Color(0xFF2C3038),
    error = Color(0xFFFF786F),
    onError = Color(0xFF3D0704),
    errorContainer = Color(0xFF5E1915),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AlmiShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** A deliberately compact editorial type ramp; Android accessibility fontScale is preserved. */
private val AlmiTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.75).sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 29.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.45).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.22.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp),
)

@Composable
fun AlmiTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val configuration = LocalConfiguration.current
    val systemDensity = LocalDensity.current
    val width = configuration.screenWidthDp
    val height = configuration.screenHeightDp

    // A 62dp button should not occupy the same visual proportion on a 360x700 phone and a
    // 480x1000 phone. Scaling density at the design-system boundary fixes every legacy fixed dp/sp
    // consistently while keeping the actual window constraints and accessibility font scale intact.
    val densityScale = when {
        width < 350 || height < 650 -> 0.84f
        width < 380 || height < 720 -> 0.88f
        width < 420 || height < 800 -> 0.92f
        width < 450 || height < 880 -> 0.95f
        width < 600 -> 0.97f
        else -> 1f
    }
    val adaptiveDensity = Density(
        density = systemDensity.density * densityScale,
        fontScale = systemDensity.fontScale,
    )

    CompositionLocalProvider(
        LocalDensity provides adaptiveDensity,
        LocalAlmiUiScale provides 1f,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AlmiTypography,
            shapes = AlmiShapes,
            content = content,
        )
    }
}
