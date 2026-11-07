package de.uditkaro.rax.theme

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    private val catalog = ThemeCatalog(
        Json.decodeFromString<List<RexThemeSource>>(File("src/main/assets/themes/rex-themes.json").readText())
            .map(ThemeCatalog::toTheme),
    )

    @Test
    fun everyThemeHasAFullPalette() {
        assertTrue(catalog.themes.size >= 40)
        catalog.themes.forEach { assertEquals(it.id, 256, it.terminal.palette.size) }
    }

    @Test
    fun defaultsAreRexDefaults() {
        assertEquals(ThemeCatalog.DEFAULT_DARK, catalog.dark(null).id)
        assertEquals(ThemeCatalog.DEFAULT_LIGHT, catalog.light(null).id)
    }

    @Test
    fun aThemeOfTheWrongAppearanceFallsBackToTheDefault() {
        val light = catalog.light.first().id
        assertEquals(ThemeCatalog.DEFAULT_DARK, catalog.dark(light).id)
        assertFalse(catalog.light(catalog.dark.first().id).dark)
    }

    @Test
    fun parsesColoursWithAndWithoutAlpha() {
        assertEquals(0xFF171210.toInt(), parseArgb("#171210"))
        assertEquals(0x05E0CFC4, parseArgb("#e0cfc405"))
    }
}
