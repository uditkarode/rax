package de.uditkaro.rax.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneChipsTest {
    private fun chip(id: String, name: String, label: String? = null) = PaneChip(id, name, label, waiting = false)

    @Test
    fun panesThatRunTheSameProgramAreToldApart() {
        val chips = listOf(chip("a", "zsh"), chip("b", "zsh"), chip("c", "vim"), chip("d", "zsh"))
        val shown = distinctChips(chips, listOf("api", "web", "api", "web")).map { it.shown }
        assertEquals(listOf("zsh · api", "zsh 2", "vim", "zsh 3"), shown)
    }

    @Test
    fun aLabelTheUserGaveIsShownAsItIs() {
        val chips = listOf(chip("a", "zsh", label = "server"), chip("b", "zsh"))
        assertEquals(listOf("server", "zsh"), distinctChips(chips, listOf(null, null)).map { it.shown })
    }
}
