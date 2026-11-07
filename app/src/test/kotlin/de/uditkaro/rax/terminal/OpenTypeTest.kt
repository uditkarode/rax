package de.uditkaro.rax.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTypeTest {
    private val fonts = File("src/main/assets/fonts")

    private fun face(path: String) = OpenType.read(File(fonts, path).readBytes()).single()

    @Test
    fun readsFamilyStyleAndWeight() {
        val regular = face("jetbrains-mono/regular.ttf")
        assertEquals("JetBrains Mono", regular.family)
        assertEquals(400, regular.weight)
        assertFalse(regular.italic)
        assertTrue(regular.monospaced)

        val boldItalic = face("jetbrains-mono/bolditalic.ttf")
        assertEquals("JetBrains Mono", boldItalic.family)
        assertEquals(700, boldItalic.weight)
        assertTrue(boldItalic.italic)
    }

    @Test
    fun everyBundledFontIsMonospaced() {
        fonts.listFiles()!!.filter { it.isDirectory && it.name != "symbols-nerd" }.forEach { dir ->
            dir.listFiles { f -> f.extension == "ttf" }!!.forEach { file ->
                val faces = OpenType.read(file.readBytes())
                assertTrue("${file.path} should be monospaced", faces.all { it.monospaced })
            }
        }
    }

    @Test
    fun proportionalFontIsDetected() {
        val ui = OpenType.read(File("src/main/res/font/google_sans_flex.ttf").readBytes()).single()
        assertEquals("Google Sans Flex", ui.family)
        assertFalse(ui.monospaced)
    }

    @Test(expected = OpenType.NotAFont::class)
    fun rejectsOtherFiles() {
        OpenType.read("not a font at all, just some text".toByteArray())
    }

    @Test(expected = OpenType.NotAFont::class)
    fun rejectsTruncatedFonts() {
        OpenType.read(File(fonts, "hack/regular.ttf").readBytes().copyOf(300))
    }
}
