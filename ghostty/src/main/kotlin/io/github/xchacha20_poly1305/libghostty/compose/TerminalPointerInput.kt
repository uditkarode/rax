package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import io.github.xchacha20_poly1305.libghostty.GhosttyMods
import io.github.xchacha20_poly1305.libghostty.GhosttyMouseAction
import io.github.xchacha20_poly1305.libghostty.GhosttyMouseButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun Modifier.terminalPointerInput(
    controller: TerminalController,
    focusRequester: FocusRequester,
    decay: DecayAnimationSpec<Float>,
): Modifier = pointerInput(controller, decay) {
    coroutineScope {
        var fling: Job? = null
        var lastClick: Pair<Long, Offset>? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue
                val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                fling?.cancel()
                if (down.type == PointerType.Mouse) {
                    val previous = lastClick
                    val doubleClick = previous != null &&
                            down.uptimeMillis - previous.first < viewConfiguration.doubleTapTimeoutMillis &&
                            (down.position - previous.second).getDistance() < viewConfiguration.touchSlop
                    lastClick = down.uptimeMillis to down.position
                    mouseGesture(controller, focusRequester, event, down, doubleClick)
                } else {
                    val velocity = touchGesture(controller, focusRequester, down)
                    if (velocity != 0f) {
                        fling = this@coroutineScope.launch {
                            fling(controller, velocity, down.position, decay)
                        }
                    }
                }
            }
        }
    }
}.pointerInput(controller) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val delta = change.scrollDelta.y
            if (delta == 0f) continue
            val rows = (if (delta > 0) WHEEL_SCROLL_ROWS else -WHEEL_SCROLL_ROWS) * maxOf(1, abs(delta).toInt())
            controller.scrollContent(rows, controller.cellAt(change.position))
            change.consume()
        }
    }
}

private fun PointerEvent.mods(): Int {
    var mods = 0
    if (keyboardModifiers.isShiftPressed) {
        mods = mods or GhosttyMods.SHIFT
    }
    if (keyboardModifiers.isCtrlPressed) {
        mods = mods or GhosttyMods.CTRL
    }
    return mods
}

/** A mouse press until its buttons are all released. */
private suspend fun AwaitPointerEventScope.mouseGesture(
    controller: TerminalController,
    focusRequester: FocusRequester,
    downEvent: PointerEvent,
    down: PointerInputChange,
    doubleClick: Boolean,
) {
    down.consume()
    focusRequester.requestFocus()
    val button = when {
        downEvent.buttons.isPrimaryPressed -> GhosttyMouseButton.LEFT
        downEvent.buttons.isSecondaryPressed -> GhosttyMouseButton.RIGHT
        downEvent.buttons.isTertiaryPressed -> GhosttyMouseButton.MIDDLE
        else -> return
    }
    val start = controller.cellAt(down.position)
    if (controller.isMouseTracking && !downEvent.keyboardModifiers.isShiftPressed) {
        controller.sendMouse(GhosttyMouseAction.PRESS, button, start, downEvent.mods())
        var last = start
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            val cell = controller.cellAt(change.position)
            if (!change.pressed) {
                controller.sendMouse(GhosttyMouseAction.RELEASE, button, cell, event.mods())
                return
            }
            if (cell != last) {
                controller.sendMouse(GhosttyMouseAction.MOTION, button, cell, event.mods())
                last = cell
            }
        }
    }

    when (button) {
        GhosttyMouseButton.RIGHT -> {
            controller.contextMenuAt = down.position
            awaitRelease(down)
        }

        GhosttyMouseButton.MIDDLE -> {
            controller.paste()
            awaitRelease(down)
        }

        else -> {
            val modifiers = downEvent.keyboardModifiers
            if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                controller.linkAt(start)?.let { controller.uriHandler.openUri(it) }
                awaitRelease(down)
                return
            }
            if (doubleClick) {
                controller.selectWord(start.column, start.row)
                awaitRelease(down)
                return
            }
            var anchor = start
            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return
                change.consume()
                if (!change.pressed) break
                if (!dragged && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                    continue
                }
                dragged = true
                anchor = controller.dragSelection(anchor, change.position)
            }
            if (!dragged && controller.selectionActive) {
                controller.clearSelection()
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitRelease(down: PointerInputChange) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/** A touch gesture until every finger is up; returns the fling velocity of a scroll, in pixels per second. */
private suspend fun AwaitPointerEventScope.touchGesture(
    controller: TerminalController,
    focusRequester: FocusRequester,
    down: PointerInputChange,
): Float {
    if (controller.selectionActive && dragSelectionHandle(controller, down)) return 0f

    // The tracker sees every move from the down on, so a quick flick that barely passes the slop still has samples.
    val tracker = VelocityTracker()
    tracker.addPointerInputChange(down)
    val start = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        awaitTouchStart(controller, down, tracker, viewConfiguration.touchSlop)
    } ?: TouchStart.LongPress
    return when (start) {
        TouchStart.Tap -> {
            tap(controller, focusRequester, down)
            0f
        }

        is TouchStart.Scroll -> scroll(controller, down, start.slopCrossing, tracker)

        TouchStart.LongPress -> {
            longPress(controller, down)
            0f
        }

        TouchStart.Pinch -> 0f
    }
}

/** How a touch gesture turned out once the finger moved, lifted, or held still for the long press timeout. */
private sealed interface TouchStart {
    data object Tap : TouchStart

    /** The finger moved past the touch slop; [slopCrossing] is the move that took it there. */
    class Scroll(val slopCrossing: PointerInputChange) : TouchStart

    data object LongPress : TouchStart

    data object Pinch : TouchStart
}

private suspend fun AwaitPointerEventScope.awaitTouchStart(
    controller: TerminalController,
    down: PointerInputChange,
    tracker: VelocityTracker,
    slop: Float,
): TouchStart {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) {
            pinch(controller, event)
            return TouchStart.Pinch
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: return TouchStart.Pinch
        if (change.changedToUp()) return TouchStart.Tap
        tracker.addPointerInputChange(change)
        if ((change.position - down.position).getDistance() > slop) return TouchStart.Scroll(change)
    }
}

private fun tap(controller: TerminalController, focusRequester: FocusRequester, down: PointerInputChange) {
    down.consume()
    if (controller.selectionActive) {
        controller.clearSelection()
        return
    }
    focusRequester.requestFocus()
    controller.requestKeyboard()
    if (controller.isMouseTracking) {
        val cell = controller.cellAt(down.position)
        controller.sendMouse(GhosttyMouseAction.PRESS, GhosttyMouseButton.LEFT, cell)
        controller.sendMouse(GhosttyMouseAction.RELEASE, GhosttyMouseButton.LEFT, cell)
    }
}

/**
 * Scrolls with the finger from [slopCrossing] on; returns the release velocity. The distance covered within the slop
 * scrolls too, so the content keeps up with the finger from the start of the gesture.
 */
private suspend fun AwaitPointerEventScope.scroll(
    controller: TerminalController,
    down: PointerInputChange,
    slopCrossing: PointerInputChange,
    tracker: VelocityTracker,
): Float {
    val cellHeight = controller.glyphs?.cellHeight?.toFloat() ?: return 0f
    val anchor = controller.cellAt(down.position)
    var accumulated = 0f
    var lastY = down.position.y
    var change = slopCrossing
    while (true) {
        change.consume()
        accumulated += lastY - change.position.y
        lastY = change.position.y
        val rows = (accumulated / cellHeight).toInt()
        if (rows != 0) {
            accumulated -= rows * cellHeight
            controller.scrollContent(rows, anchor)
        }

        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) {
            pinch(controller, event)
            return 0f
        }
        change = event.changes.firstOrNull { it.id == down.id } ?: return 0f
        tracker.addPointerInputChange(change)
        if (!change.pressed) {
            change.consume()
            return -tracker.calculateVelocity().y
        }
    }
}

private suspend fun fling(
    controller: TerminalController,
    velocity: Float,
    position: Offset,
    decay: DecayAnimationSpec<Float>,
) {
    val cellHeight = controller.glyphs?.cellHeight?.toFloat() ?: return
    val anchor = controller.cellAt(position)
    // A program gets a gentler fling, at most half a screen: each row is a key or wheel report it must handle.
    val program = controller.scrollsProgram
    val limit = if (program) controller.rows / 2 * cellHeight else Float.MAX_VALUE
    var last = 0f
    var accumulated = 0f
    AnimationState(0f, if (program) velocity * PROGRAM_FLING_SCALE else velocity).animateDecay(decay) {
        val clamped = value.coerceIn(-limit, limit)
        accumulated += clamped - last
        last = clamped
        val rows = (accumulated / cellHeight).toInt()
        if (rows != 0) {
            accumulated -= rows * cellHeight
            controller.scrollContent(rows, anchor)
        }
        if (clamped != value) cancelAnimation()
    }
}

/** Zooms the font while two fingers are down. */
private suspend fun AwaitPointerEventScope.pinch(controller: TerminalController, first: PointerEvent) {
    fun spread(event: PointerEvent): Float? {
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < 2) return null
        return (pressed[0].position - pressed[1].position).getDistance()
    }

    val initial = spread(first) ?: return
    var applied = 1f
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        val current = spread(event) ?: return
        val scale = current / initial
        val steps = ((scale - applied) / PINCH_SCALE_STEP).toInt()
        if (steps != 0) {
            applied += steps * PINCH_SCALE_STEP
            controller.changeFontSize(steps)
        }
    }
}

/** Opens the link under a long press, or selects the word and lets the finger extend the selection. */
private suspend fun AwaitPointerEventScope.longPress(controller: TerminalController, down: PointerInputChange) {
    val cell = controller.cellAt(down.position)
    controller.haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    val link = controller.linkAt(cell)
    if (link != null) {
        controller.showLinkMenu(link)
        awaitRelease(down)
        return
    }
    if (!controller.selectWord(cell.column, cell.row)) {
        awaitRelease(down)
        controller.showTextToolbar(cell)
        return
    }
    var anchor = cell
    var pastSlop = false
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
        if (!pastSlop && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
        pastSlop = true
        controller.textToolbar.hide()
        controller.magnifierCenter = change.position
        anchor = controller.dragSelection(anchor, change.position)
    }
    controller.magnifierCenter = Offset.Unspecified
    controller.showTextToolbar(cell)
}

/** Drags a selection handle if [down] lands on one; returns whether it did. */
private suspend fun AwaitPointerEventScope.dragSelectionHandle(
    controller: TerminalController,
    down: PointerInputChange,
): Boolean {
    val (start, end) = SelectionHandles.anchors(controller) ?: return false
    val bounds = controller.selectionBounds() ?: return false
    val density = controller.density
    val radius = SelectionHandles.radius.value * density
    val slop = SelectionHandles.hitSlop.value * density
    val startCenter = Offset(start.x - radius, start.y + radius)
    val endCenter = Offset(end.x + radius, end.y + radius)
    val toStart = (down.position - startCenter).getDistance()
    val toEnd = (down.position - endCenter).getDistance()
    // Dragging a handle moves that end of the selection; the other end stays put as the anchor.
    var anchor = when {
        toEnd <= slop && toEnd <= toStart -> bounds.first
        toStart <= slop -> bounds.second
        else -> return false
    }
    down.consume()
    controller.textToolbar.hide()
    // The handle hangs below the text, so the finger is a row below the cell it moves.
    val bias = 1
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
        controller.magnifierCenter = change.position - Offset(0f, controller.glyphs?.cellHeight?.toFloat() ?: 0f)
        anchor = controller.dragSelection(anchor, change.position, biasRows = bias)
    }
    controller.magnifierCenter = Offset.Unspecified
    controller.showTextToolbar(anchor)
    return true
}

private const val WHEEL_SCROLL_ROWS = 3
private const val PINCH_SCALE_STEP = 0.1f
private const val PROGRAM_FLING_SCALE = 0.25f
