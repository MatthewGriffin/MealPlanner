package com.example.chatgpt_mealplanner.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomColoursTest {
    private val picks = listOf(
        0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(),
        0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFC4501B.toInt(), 0xFF6A1B9A.toInt(), 0xFF00BCD4.toInt()
    )

    @Test fun anyMainColourGivesReadablePrimaryAndTextInBothModes() {
        for (pick in picks) for (dark in listOf(false, true)) {
            val s = customColorScheme(pick, null, dark)
            val bg = s.background.toArgb()
            assertTrue("primary on bg $pick dark=$dark", ColorUtils.calculateContrast(s.primary.toArgb(), bg) >= 4.4)
            assertTrue("text on bg $pick dark=$dark", ColorUtils.calculateContrast(s.onBackground.toArgb(), bg) >= 7.0)
            assertTrue("onPrimary $pick dark=$dark", ColorUtils.calculateContrast(s.onPrimary.toArgb(), s.primary.toArgb()) >= 4.4)
        }
    }

    @Test fun chosenBackgroundIsUsedAndTextFollowsIt() {
        val light = customColorScheme(0xFFC4501B.toInt(), 0xFFF0F0F0.toInt(), systemDark = true)
        val dark = customColorScheme(0xFFC4501B.toInt(), 0xFF101010.toInt(), systemDark = false)
        assertTrue(light.background.toArgb() == 0xFFF0F0F0.toInt())
        assertTrue(ColorUtils.calculateLuminance(light.onBackground.toArgb()) < 0.2)
        assertTrue(ColorUtils.calculateLuminance(dark.onBackground.toArgb()) > 0.8)
        assertTrue(ColorUtils.calculateContrast(dark.primary.toArgb(), dark.background.toArgb()) >= 4.4)
    }
}
