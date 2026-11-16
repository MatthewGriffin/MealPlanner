package com.example.chatgpt_mealplanner.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

/** The user's own colours, kept in a tiny preferences file and observable by the theme so changes apply instantly. */
object AppThemeState {
    private const val PREFS = "app_theme_v1"
    private var appContext: Context? = null

    var useCustom by mutableStateOf(false); private set
    var accent by mutableStateOf(DEFAULT_ACCENT); private set
    /** Null means "work the background out from the main colour". */
    var background by mutableStateOf<Int?>(null); private set

    fun load(context: Context) {
        appContext = context.applicationContext
        val prefs = runCatching { appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return
        useCustom = prefs.getBoolean("custom", false)
        accent = prefs.getInt("accent", DEFAULT_ACCENT)
        background = if (prefs.contains("background")) prefs.getInt("background", 0) else null
    }

    fun chooseCustom(value: Boolean) { useCustom = value; save() }
    fun chooseAccent(value: Int) { accent = value; save() }
    fun chooseBackground(value: Int?) { background = value; save() }
    fun reset() { useCustom = false; accent = DEFAULT_ACCENT; background = null; save() }

    private fun save() {
        val prefs = runCatching { appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return
        prefs.edit().apply {
            putBoolean("custom", useCustom)
            putInt("accent", accent)
            if (background != null) putInt("background", background!!) else remove("background")
        }.apply()
    }

    const val DEFAULT_ACCENT: Int = 0xFFC4501B.toInt()
}

private fun hsl(color: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
private fun fromHsl(h: Float, s: Float, l: Float): Int =
    ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
private fun blend(from: Int, to: Int, amount: Float): Int = ColorUtils.blendARGB(from, to, amount)
private fun readableOn(color: Int): Int {
    val darkText = 0xFF1A1410.toInt()
    val lightText = 0xFFFFFFFF.toInt()
    return if (ColorUtils.calculateContrast(darkText, color) >= ColorUtils.calculateContrast(lightText, color)) darkText else lightText
}
private fun c(argb: Int) = Color(argb)

/** True when the chosen background is dark enough that light text and icons are needed. */
fun isDarkBackground(background: Int): Boolean = ColorUtils.calculateLuminance(background) < 0.35

/**
 * Builds a whole palette from one main colour. Buttons and highlights are lightened (dark backgrounds) or darkened
 * (light backgrounds) until they have enough contrast to read, so any pick stays usable. Pass [background] to choose
 * the background yourself; otherwise it is a very light or very dark tint of the main colour.
 */
fun customColorScheme(accent: Int, background: Int?, systemDark: Boolean): ColorScheme {
    val dark = background?.let { isDarkBackground(it) } ?: systemDark
    val (h, s, _) = hsl(accent).let { Triple(it[0], it[1], it[2]) }
    val bg = background ?: if (dark) fromHsl(h, (s * 0.45f).coerceIn(0.1f, 0.4f), 0.09f)
    else fromHsl(h, s.coerceIn(0.35f, 0.9f), 0.95f)
    val onBg = readableOn(bg)

    // Foreground colour: nudge lightness until it contrasts with the background.
    fun legible(hue: Float, sat: Float, startL: Float): Int {
        var l = startL
        var color = fromHsl(hue, sat, l)
        var guard = 0
        while (ColorUtils.calculateContrast(color, bg) < 4.5 && guard++ < 40) {
            l += if (dark) 0.02f else -0.02f
            color = fromHsl(hue, sat, l)
        }
        return color
    }
    val primary = legible(h, s.coerceIn(0.35f, 1f), hsl(accent)[2].let { if (dark) maxOf(it, 0.65f) else minOf(it, 0.42f) })
    val secondary = legible(h + 40f, (s * 0.8f).coerceIn(0.3f, 0.9f), if (dark) 0.7f else 0.38f)
    val tertiary = legible(h + 80f, (s * 0.8f).coerceIn(0.3f, 0.9f), if (dark) 0.72f else 0.36f)
    fun container(hue: Float, sat: Float) = fromHsl(hue, sat * 0.6f, if (dark) 0.22f else 0.88f)
    val primaryContainer = container(h, s.coerceIn(0.35f, 1f))
    val secondaryContainer = container(h + 40f, s.coerceIn(0.3f, 0.9f))
    val tertiaryContainer = container(h + 80f, s.coerceIn(0.3f, 0.9f))

    val common = fun(base: ColorScheme): ColorScheme = base.copy(
        primary = c(primary), onPrimary = c(readableOn(primary)),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(readableOn(primaryContainer)),
        secondary = c(secondary), onSecondary = c(readableOn(secondary)),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(readableOn(secondaryContainer)),
        tertiary = c(tertiary), onTertiary = c(readableOn(tertiary)),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(readableOn(tertiaryContainer)),
        background = c(bg), onBackground = c(onBg),
        surface = c(bg), onSurface = c(onBg),
        surfaceVariant = c(blend(bg, onBg, 0.10f)), onSurfaceVariant = c(blend(onBg, bg, 0.25f)),
        surfaceContainerLowest = c(blend(bg, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.5f)),
        surfaceContainerLow = c(blend(bg, onBg, 0.03f)),
        surfaceContainer = c(blend(bg, onBg, 0.05f)),
        surfaceContainerHigh = c(blend(bg, onBg, 0.08f)),
        surfaceContainerHighest = c(blend(bg, onBg, 0.12f)),
        outline = c(blend(bg, onBg, 0.5f)), outlineVariant = c(blend(bg, onBg, 0.2f))
    )
    return common(if (dark) darkColorScheme() else lightColorScheme())
}
