package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Sticky Ctrl and Alt of the extra keys bar: a tap arms a modifier for the next key, a double tap locks it. */
public class GhosttyExtraKeysState : GhosttyStickyModifiers {

    public enum class StickyState { INACTIVE, ARMED, LOCKED }

    public val ctrl: StateFlow<StickyState>
        field = MutableStateFlow(StickyState.INACTIVE)

    public val alt: StateFlow<StickyState>
        field = MutableStateFlow(StickyState.INACTIVE)

    private var lastCtrlTap: TimeMark? = null
    private var lastAltTap: TimeMark? = null

    public fun toggleCtrl() {
        ctrl.value = toggleModifier(ctrl.value, lastCtrlTap)
        lastCtrlTap = TimeSource.Monotonic.markNow()
    }

    public fun toggleAlt() {
        alt.value = toggleModifier(alt.value, lastAltTap)
        lastAltTap = TimeSource.Monotonic.markNow()
    }

    override fun consumeModifiers() {
        if (ctrl.value == StickyState.ARMED) ctrl.value = StickyState.INACTIVE
        if (alt.value == StickyState.ARMED) alt.value = StickyState.INACTIVE
    }

    /** The armed or locked modifiers as [GhosttyMods] bits. */
    override fun currentMods(): Int {
        var mods = 0
        if (ctrl.value != StickyState.INACTIVE) {
            mods = mods or GhosttyMods.CTRL
        }
        if (alt.value != StickyState.INACTIVE) {
            mods = mods or GhosttyMods.ALT
        }
        return mods
    }

    private fun toggleModifier(current: StickyState, lastTap: TimeMark?): StickyState = when (current) {
        StickyState.INACTIVE -> StickyState.ARMED

        StickyState.ARMED -> {
            val elapsed = lastTap?.elapsedNow()?.inWholeMilliseconds
            if (elapsed != null && elapsed < DOUBLE_TAP_THRESHOLD_MS) {
                StickyState.LOCKED
            } else {
                StickyState.INACTIVE
            }
        }

        StickyState.LOCKED -> StickyState.INACTIVE
    }

    private companion object {
        const val DOUBLE_TAP_THRESHOLD_MS = 300L
    }
}

@Composable
public fun rememberGhosttyExtraKeysState(): GhosttyExtraKeysState = remember { GhosttyExtraKeysState() }
