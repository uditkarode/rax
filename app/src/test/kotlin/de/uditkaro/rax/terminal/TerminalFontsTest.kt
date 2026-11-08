package de.uditkaro.rax.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TerminalFontsTest {

    private fun idOf(family: String, existing: Map<String, String> = emptyMap()) =
        TerminalFonts.familyId(family) { existing[it] }

    @Test
    fun aLatinNameGivesAReadableId() {
        assertEquals("user-fira-code", idOf("Fira Code"))
    }

    @Test
    fun aFamilyKeepsItsIdWhenImportedAgain() {
        assertEquals("user-fira-code", idOf("Fira Code", mapOf("user-fira-code" to "Fira Code")))
    }

    @Test
    fun namesWithoutLatinLettersGetAnIdEach() {
        val gothic = idOf("源ノ角ゴシック")
        val sarasa = idOf("等距更纱黑体")
        assertNotEquals("user-", gothic)
        assertNotEquals(gothic, sarasa)
        assertEquals(gothic, idOf("源ノ角ゴシック"))
    }

    @Test
    fun aFamilyWhoseIdIsTakenGetsItsOwn() {
        val existing = mapOf("user-fira-code" to "Fira Code")
        val other = idOf("Fira-Code", existing)
        assertNotEquals("user-fira-code", other)
        assertEquals(other, idOf("Fira-Code", existing + (other to "Fira-Code")))
    }
}
