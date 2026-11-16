package com.example.chatgpt_mealplanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Dark: deep espresso brown with cream text, saffron-orange primary, leaf-green secondary.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFA65C),
    onPrimary = Color(0xFF3F1D00),
    primaryContainer = Color(0xFF5C3418),
    onPrimaryContainer = Color(0xFFFFF1E3),
    secondary = Color(0xFF8FD99B),
    onSecondary = Color(0xFF07290F),
    secondaryContainer = Color(0xFF2F4A35),
    onSecondaryContainer = Color(0xFFEAF6EA),
    tertiary = Color(0xFFF2CE5E),
    onTertiary = Color(0xFF3A2D00),
    tertiaryContainer = Color(0xFF4F4120),
    onTertiaryContainer = Color(0xFFFFF4D6),
    background = Color(0xFF1F1713),
    onBackground = Color(0xFFFFF1E3),
    surface = Color(0xFF1F1713),
    onSurface = Color(0xFFFFF1E3),
    surfaceVariant = Color(0xFF3A2C25),
    onSurfaceVariant = Color(0xFFE3D2C4),
    surfaceContainerLowest = Color(0xFF17100D),
    surfaceContainerLow = Color(0xFF261B16),
    surfaceContainer = Color(0xFF2E221C),
    surfaceContainerHigh = Color(0xFF392A23),
    surfaceContainerHighest = Color(0xFF45342B),
    outline = Color(0xFFA68F7F),
    outlineVariant = Color(0xFF55433A)
)

// Light: warm cream with white cards, terracotta primary, leaf-green secondary.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC4501B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0CC),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF2F6B3A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9EBD3),
    onSecondaryContainer = Color(0xFF10301A),
    tertiary = Color(0xFF8F6B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBB0),
    onTertiaryContainer = Color(0xFF3A2D00),
    background = Color(0xFFFFF4E0),
    onBackground = Color(0xFF2A1F19),
    surface = Color(0xFFFFF4E0),
    onSurface = Color(0xFF2A1F19),
    surfaceVariant = Color(0xFFF5E3C8),
    onSurfaceVariant = Color(0xFF5A4A3E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFAF0),
    surfaceContainer = Color(0xFFFFF9EE),
    surfaceContainerHigh = Color(0xFFFAEBD2),
    surfaceContainerHighest = Color(0xFFF5E3C8),
    outline = Color(0xFF9A8472),
    outlineVariant = Color(0xFFE6D3B8)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

/** Follows the phone's light/dark setting, always with the Pantry Pal palette (no wallpaper colours). */
@Composable
fun ChatGPT_MealPlannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val custom = AppThemeState.useCustom
    val scheme = if (custom) customColorScheme(AppThemeState.accent, AppThemeState.background, darkTheme)
    else if (darkTheme) DarkColorScheme else LightColorScheme
    // With a hand-picked background the status bar icons must follow that colour, not the phone's setting.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val darkSurface = if (custom) AppThemeState.background?.let { isDarkBackground(it) } ?: darkTheme else darkTheme
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkSurface
                isAppearanceLightNavigationBars = !darkSurface
            }
        }
    }
    // Material 3 Expressive: springy, physics-based motion and bolder, rounder shapes.
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
