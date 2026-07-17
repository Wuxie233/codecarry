package dev.minios.ocremote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBCC3F5),
    onPrimary = Color(0xFF252B58),
    primaryContainer = Color(0xFF3C426E),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC5C7C0),
    onSecondary = Color(0xFF2F312D),
    secondaryContainer = Color(0xFF454842),
    onSecondaryContainer = Color(0xFFE1E3DC),
    tertiary = Color(0xFF8FD3C9),
    onTertiary = Color(0xFF003733),
    tertiaryContainer = Color(0xFF194E49),
    onTertiaryContainer = Color(0xFFAAEFE5),
    background = Color(0xFF111310),
    onBackground = Color(0xFFE8EAE5),
    surface = Color(0xFF191B18),
    onSurface = Color(0xFFE8EAE5),
    surfaceVariant = Color(0xFF2C2F2A),
    onSurfaceVariant = Color(0xFFB7BBB3),
    surfaceContainerLowest = Color(0xFF0C0E0B),
    surfaceContainerLow = Color(0xFF171916),
    surfaceContainer = Color(0xFF1D201C),
    surfaceContainerHigh = Color(0xFF222520),
    surfaceContainerHighest = Color(0xFF2B2E29),
    outline = Color(0xFF8D9189),
    outlineVariant = Color(0xFF3D413A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF525985),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E7F4),
    onPrimaryContainer = Color(0xFF202747),
    secondary = Color(0xFF5F625D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7E1),
    onSecondaryContainer = Color(0xFF1D201C),
    tertiary = Color(0xFF2F6F69),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC0E8E1),
    onTertiaryContainer = Color(0xFF00201E),
    background = Color(0xFFF8F8F6),
    onBackground = Color(0xFF20211F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF20211F),
    surfaceVariant = Color(0xFFE7E9E4),
    onSurfaceVariant = Color(0xFF5F625D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6F2),
    surfaceContainer = Color(0xFFF0F1EE),
    surfaceContainerHigh = Color(0xFFEAEBE7),
    surfaceContainerHighest = Color(0xFFE4E6E1),
    outline = Color(0xFF747872),
    outlineVariant = Color(0xFFD9DBD5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

/**
 * AMOLED dark color scheme — pure black surfaces for OLED battery savings.
 * Uses true black (#000000) for the main surface and very dark tones for containers,
 * ensuring cards/sheets are still visually distinguishable from the background.
 */
private val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF19191C),
    surfaceContainer = Color(0xFF0D0D0F),
    surfaceContainerLow = Color(0xFF08080A),
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF141416),
    surfaceContainerHighest = Color(0xFF1C1C1F)
)

private val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/**
 * OpenCode Material 3 Theme
 * 
 * Supports:
 * - Light/Dark theme based on system settings
 * - Dynamic color on Android 12+ (Material You)
 * - AMOLED dark mode with pure black surfaces
 * - Edge-to-edge display
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && amoledDark) {
                scheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF19191C),
                    surfaceContainer = Color(0xFF0D0D0F),
                    surfaceContainerLow = Color(0xFF08080A),
                    surfaceContainerLowest = Color.Black,
                    surfaceContainerHigh = Color(0xFF141416),
                    surfaceContainerHighest = Color(0xFF1C1C1F)
                )
            } else {
                scheme
            }
        }
        darkTheme && amoledDark -> AmoledDarkColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use surface color for status bar (less jarring than primary)
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = OpenCodeShapes,
        content = content
    )
}
