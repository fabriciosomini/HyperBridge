package com.d4viddf.hyperbridge.service

import com.d4viddf.hyperbridge.models.theme.ColorMode
import com.d4viddf.hyperbridge.models.theme.GlobalConfig
import com.d4viddf.hyperbridge.models.theme.HyperTheme
import com.d4viddf.hyperbridge.models.theme.ThemeMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class PermanentIslandBorderColorTest {

    private val accent = "#AED0A7"
    private val isHex: (String) -> Boolean = { it.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) }

    private fun theme(highlight: String?, mode: ColorMode?) = HyperTheme(
        id = "t",
        meta = ThemeMetadata(name = "T", author = "me"),
        global = GlobalConfig(highlightColor = highlight, colorMode = mode)
    )

    private fun resolve(theme: HyperTheme?) = PermanentIslandBorderColor.resolve(theme, accent, isHex)

    @Test
    fun noActiveThemeKeepsWhite() {
        assertEquals(PermanentIslandBorderColor.DEFAULT, resolve(null))
    }

    @Test
    fun customModeUsesThemeHighlight() {
        assertEquals("#3DDA82", resolve(theme("#3DDA82", ColorMode.CUSTOM)))
    }

    @Test
    fun materialYouUsesAccentOverStoredHighlight() {
        assertEquals(accent, resolve(theme("#3DDA82", ColorMode.MATERIAL_YOU)))
    }

    @Test
    fun appIconModeFallsBackToStoredHighlight() {
        assertEquals("#3DDA82", resolve(theme("#3DDA82", ColorMode.APP_ICON)))
    }

    @Test
    fun invalidOrMissingHighlightFallsBackToWhite() {
        assertEquals(PermanentIslandBorderColor.DEFAULT, resolve(theme("not-a-color", ColorMode.CUSTOM)))
        assertEquals(PermanentIslandBorderColor.DEFAULT, resolve(theme(null, null)))
    }
}
