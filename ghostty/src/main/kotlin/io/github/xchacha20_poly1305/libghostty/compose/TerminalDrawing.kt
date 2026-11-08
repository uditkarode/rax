package io.github.xchacha20_poly1305.libghostty.compose

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.xchacha20_poly1305.libghostty.GhosttyCellFlags
import io.github.xchacha20_poly1305.libghostty.GhosttyCursorStyle
import io.github.xchacha20_poly1305.libghostty.GhosttyRenderState
import kotlin.math.max
import kotlin.math.min

/** Where the selection handles are drawn, shared with hit testing. */
internal object SelectionHandles {
    val radius = 11.dp
    val hitSlop = 24.dp

    /** The point a handle's stem touches: the bottom corner of the first or last selected cell. */
    fun anchors(controller: TerminalController): Pair<Offset, Offset>? {
        val glyphs = controller.glyphs ?: return null
        val (first, last) = controller.selectionBounds() ?: return null
        val origin = controller.gridOrigin
        return origin + Offset(first.column * glyphs.cellWidth, (first.row + 1f) * glyphs.cellHeight) to
                origin + Offset((last.column + 1) * glyphs.cellWidth, (last.row + 1f) * glyphs.cellHeight)
    }
}

internal fun DrawScope.drawTerminal(controller: TerminalController, handleColor: Color, showHandles: Boolean) {
    val glyphs = controller.glyphs ?: return
    val render = controller.render
    val background = Color(render.defaultBackground)
    drawRect(background)
    if (render.columns == 0 || render.rows == 0) return

    val cellWidth = glyphs.cellWidth
    val cellHeight = glyphs.cellHeight.toFloat()
    val origin = controller.gridOrigin
    translate(origin.x, origin.y) {
        clipRect(right = render.columns * cellWidth, bottom = render.rows * cellHeight) {
            drawBackgrounds(render, cellWidth, cellHeight)
            drawKittyImages(controller, cellWidth, cellHeight, belowText = true)
            drawText(render, glyphs, cellWidth, cellHeight)
            drawKittyImages(controller, cellWidth, cellHeight, belowText = false)
            if (controller.cursorBlinkOn || !render.cursorBlinking) drawCursor(render, glyphs, cellWidth, cellHeight)
            drawComposingText(controller, glyphs, cellWidth, cellHeight)
            drawPasswordIndicator(render, cellWidth, cellHeight)
        }
    }
    if (showHandles && controller.selectionActive) drawSelectionHandles(controller, handleColor)
}

private fun DrawScope.drawBackgrounds(render: GhosttyRenderState, cellWidth: Float, cellHeight: Float) {
    for (row in 0 until render.rows) {
        val top = row * cellHeight
        var col = 0
        while (col < render.columns) {
            val color = render.background(col, row)
            var end = col + 1
            while (end < render.columns && render.background(end, row) == color) end++
            if (color != render.defaultBackground) {
                drawRect(Color(color), Offset(col * cellWidth, top), Size((end - col) * cellWidth, cellHeight))
            }
            col = end
        }
    }
}

private fun DrawScope.drawText(render: GhosttyRenderState, glyphs: GlyphPainter, cellWidth: Float, cellHeight: Float) {
    val thickness = underlineThickness()
    drawIntoCanvas { canvas ->
        for (row in 0 until render.rows) {
            val top = row * cellHeight
            val chars = render.rowText(row)
            for (col in 0 until render.columns) {
                val flags = render.flags(col, row)
                val length = render.textLength(col, row)
                if (flags and (GhosttyCellFlags.SPACER or GhosttyCellFlags.INVISIBLE) != 0) continue
                val color = textColor(render.foreground(col, row), flags)
                val x = col * cellWidth
                if (length > 0) {
                    glyphs.draw(
                        canvas, chars, render.textOffset(col, row), length, x, top + glyphs.baseline, color,
                        bold = flags and GhosttyCellFlags.BOLD != 0,
                        italic = flags and GhosttyCellFlags.ITALIC != 0,
                    )
                }
                val runWidth = if (flags and GhosttyCellFlags.WIDE != 0) cellWidth * 2 else cellWidth
                if (flags and GhosttyCellFlags.UNDERLINE != 0) {
                    drawUnderline(x, top + cellHeight, runWidth, render.underline(col, row), Color(color), thickness)
                }
                if (flags and GhosttyCellFlags.STRIKETHROUGH != 0) {
                    drawRect(Color(color), Offset(x, top + cellHeight / 2 - thickness / 2), Size(runWidth, thickness))
                }
            }
        }
    }
}

private fun textColor(color: Int, flags: Int): Int =
    if (flags and GhosttyCellFlags.FAINT != 0) {
        (color and 0x00FFFFFF) or (FAINT_ALPHA shl 24)
    } else {
        color
    }

private fun DrawScope.underlineThickness(): Float = max(1f, 1.dp.toPx())

private fun DrawScope.drawUnderline(x: Float, bottom: Float, width: Float, style: Int, color: Color, thickness: Float) {
    when (style) {
        GhosttyCellFlags.UNDERLINE_DOUBLE -> {
            drawRect(color, Offset(x, bottom - 3 * thickness), Size(width, thickness))
            drawRect(color, Offset(x, bottom - thickness), Size(width, thickness))
        }

        GhosttyCellFlags.UNDERLINE_CURLY -> {
            val amplitude = max(2f, thickness * 1.5f)
            val halfPeriod = max(2f, thickness * 2)
            val path = Path().apply {
                moveTo(x, bottom - thickness / 2)
                var waveX = x
                var crest = true
                while (waveX < x + width) {
                    waveX = min(waveX + halfPeriod, x + width)
                    lineTo(waveX, bottom - thickness / 2 - if (crest) amplitude else 0f)
                    crest = !crest
                }
            }
            drawPath(path, color, style = Stroke(thickness))
        }

        GhosttyCellFlags.UNDERLINE_DOTTED -> {
            var dotX = x
            while (dotX < x + width) {
                drawRect(color, Offset(dotX, bottom - thickness), Size(min(thickness, x + width - dotX), thickness))
                dotX += thickness * 3
            }
        }

        GhosttyCellFlags.UNDERLINE_DASHED -> {
            val dash = width / 4
            var dashX = x
            while (dashX < x + width) {
                drawRect(color, Offset(dashX, bottom - thickness), Size(min(dash, x + width - dashX), thickness))
                dashX += dash * 2
            }
        }

        else -> drawRect(color, Offset(x, bottom - thickness), Size(width, thickness))
    }
}

private fun DrawScope.drawKittyImages(
    controller: TerminalController,
    cellWidth: Float,
    cellHeight: Float,
    belowText: Boolean,
) {
    for (placement in controller.render.kittyPlacements) {
        if ((placement.z < 0) != belowText) continue
        val image = controller.kittyImage(placement.imageGeneration) ?: continue
        drawImage(
            image,
            srcOffset = IntOffset(placement.sourceX, placement.sourceY),
            srcSize = IntSize(placement.sourceWidth, placement.sourceHeight),
            dstOffset = IntOffset(
                (placement.column * cellWidth + placement.offsetX).toInt(),
                (placement.row * cellHeight + placement.offsetY).toInt(),
            ),
            dstSize = IntSize(placement.width, placement.height),
            filterQuality = FilterQuality.Low,
        )
    }
}

private fun DrawScope.drawCursor(
    render: GhosttyRenderState,
    glyphs: GlyphPainter,
    cellWidth: Float,
    cellHeight: Float
) {
    if (!render.cursorVisible || render.cursorColumn < 0 || render.cursorRow < 0) return
    if (render.cursorRow >= render.rows || render.cursorColumn >= render.columns) return
    var col = render.cursorColumn
    val row = render.cursorRow
    // A cursor on the spacer of a wide character covers the character.
    if (col > 0 && render.flags(col, row) and GhosttyCellFlags.SPACER != 0 &&
        render.flags(col - 1, row) and GhosttyCellFlags.WIDE != 0
    ) {
        col--
    }
    val flags = render.flags(col, row)
    val width = if (flags and GhosttyCellFlags.WIDE != 0) {
        cellWidth * 2
    } else {
        cellWidth
    }
    val x = col * cellWidth
    val top = row * cellHeight
    val color = Color(render.cursorColor ?: render.defaultForeground)
    when (render.cursorStyle) {
        GhosttyCursorStyle.BAR -> drawRect(color, Offset(x, top), Size(cellWidth / 4, cellHeight))
        GhosttyCursorStyle.UNDERLINE -> drawRect(
            color,
            Offset(x, top + cellHeight * 3 / 4),
            Size(width, cellHeight / 4)
        )

        GhosttyCursorStyle.BLOCK_HOLLOW -> {
            val thickness = underlineThickness()
            drawRect(
                color,
                Offset(x + thickness / 2, top + thickness / 2),
                Size(width - thickness, cellHeight - thickness),
                style = Stroke(thickness),
            )
        }

        GhosttyCursorStyle.BLOCK -> {
            drawRect(color, Offset(x, top), Size(width, cellHeight))
            val length = render.textLength(col, row)
            if (length == 0 || flags and GhosttyCellFlags.INVISIBLE != 0) return
            drawIntoCanvas {
                glyphs.draw(
                    it, render.rowText(row), render.textOffset(col, row), length, x, top + glyphs.baseline,
                    render.background(col, row),
                    bold = flags and GhosttyCellFlags.BOLD != 0,
                    italic = flags and GhosttyCellFlags.ITALIC != 0,
                )
            }
        }
    }
}

/** Input method composition, drawn inverted at the cursor until it is committed. */
private fun DrawScope.drawComposingText(
    controller: TerminalController,
    glyphs: GlyphPainter,
    cellWidth: Float,
    cellHeight: Float,
) {
    val text = controller.composingText ?: return
    val render = controller.render
    if (render.cursorColumn < 0 || render.cursorRow < 0) return
    val width = glyphs.measure(text)
    val right = render.columns * cellWidth
    val left = (render.cursorColumn * cellWidth).let {
        if (it + width > right) {
            max(0f, right - width)
        } else {
            it
        }
    }
    val top = render.cursorRow * cellHeight
    drawRect(Color(render.defaultForeground), Offset(left, top), Size(width, cellHeight))
    val chars = text.toCharArray()
    drawIntoCanvas {
        glyphs.draw(it, chars, 0, chars.size, left, top + glyphs.baseline, render.defaultBackground, false, false)
    }
}

/** A padlock next to the cursor while the program reads a password. */
private fun DrawScope.drawPasswordIndicator(render: GhosttyRenderState, cellWidth: Float, cellHeight: Float) {
    if (!render.passwordInput || render.cursorColumn < 0 || render.cursorRow < 0) return
    val size = cellHeight * 0.5f
    var left = (render.cursorColumn + 1) * cellWidth + size * 0.25f
    if (left + size > render.columns * cellWidth) left = max(0f, render.cursorColumn * cellWidth - size * 1.25f)
    val top = render.cursorRow * cellHeight + (cellHeight - size) / 2f
    val color = Color(render.cursorColor ?: render.defaultForeground)
    val thickness = underlineThickness()
    val shackleRadius = size * 0.28f
    drawArc(
        color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(left + size / 2 - shackleRadius, top),
        size = Size(shackleRadius * 2, shackleRadius * 2),
        style = Stroke(thickness),
    )
    drawRoundRect(
        color,
        Offset(left, top + shackleRadius),
        Size(size, size - shackleRadius),
        CornerRadius(thickness),
    )
}

private fun DrawScope.drawSelectionHandles(controller: TerminalController, color: Color) {
    val (start, end) = SelectionHandles.anchors(controller) ?: return
    val radius = SelectionHandles.radius.toPx()
    // Teardrops pointing at the anchor: the start handle hangs to the left of it, the end handle to the right.
    drawCircle(color, radius, Offset(start.x - radius, start.y + radius))
    drawRect(color, Offset(start.x - radius, start.y), Size(radius, radius))
    drawCircle(color, radius, Offset(end.x + radius, end.y + radius))
    drawRect(color, Offset(end.x, end.y), Size(radius, radius))
}

private const val FAINT_ALPHA = 160
