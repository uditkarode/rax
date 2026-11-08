package de.uditkaro.rax.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {
    private fun block(label: String?) = BlockView(id = "block:a", label = label, flavor = "$TERMINAL.shell")

    @Test
    fun onlyALabelTheUserGaveCountsAsTheirs() {
        assertEquals("server", block("server").userLabel)
        assertNull(block("shell").userLabel)
        assertNull(block("Block 89abcdef").userLabel)
        assertNull(block(" ").userLabel)
        assertNull(block(null).userLabel)
    }
}
