package io.github.xchacha20_poly1305.libghostty.compose

import android.view.KeyEvent
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyModsTest {

    private val rightAlt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON

    @Test
    fun altGrLeavesAltOut() {
        assertEquals(0, keyMods(rightAlt, altGr = true))
        assertEquals(GhosttyMods.SHIFT, keyMods(rightAlt or KeyEvent.META_SHIFT_ON, altGr = true))
        // Left Alt held as well still counts.
        assertEquals(GhosttyMods.ALT, keyMods(rightAlt or KeyEvent.META_ALT_LEFT_ON, altGr = true))
    }

    @Test
    fun rightAltWithoutAltGrCharacterIsAlt() {
        assertEquals(GhosttyMods.ALT or GhosttyMods.ALT_RIGHT, keyMods(rightAlt, altGr = false))
        assertEquals(GhosttyMods.ALT, keyMods(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, altGr = false))
    }
}
