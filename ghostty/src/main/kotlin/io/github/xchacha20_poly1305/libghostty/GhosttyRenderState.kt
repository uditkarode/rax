package io.github.xchacha20_poly1305.libghostty

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a renderer draws: the viewport grid, cursor and images of a terminal. [update] applies the rows the
 * terminal changed since the last update, so the state is meant to live as long as the renderer.
 *
 * Cell colors are final ARGB values: defaults, inverse video and the selection are already applied.
 */
public class GhosttyRenderState {

    public var columns: Int = 0
        private set

    public var rows: Int = 0
        private set

    public var defaultForeground: Int = 0xFFFFFFFF.toInt()
        private set

    public var defaultBackground: Int = 0xFF000000.toInt()
        private set

    /** Cursor column, or -1 when the cursor is outside the viewport. */
    public var cursorColumn: Int = -1
        private set

    public var cursorRow: Int = -1
        private set

    public var cursorStyle: GhosttyCursorStyle = GhosttyCursorStyle.BLOCK
        private set

    public var cursorVisible: Boolean = false
        private set

    public var cursorBlinking: Boolean = false
        private set

    /** The cursor color the program or theme set, or null to use the cell's foreground. */
    public var cursorColor: Int? = null
        private set

    /** Whether the program reads a password: the terminal disabled echo at the cursor. */
    public var passwordInput: Boolean = false
        private set

    public var scrollbar: GhosttyScrollbar = GhosttyScrollbar(0, 0, 0)
        private set

    /** Visible Kitty graphics placements, ordered by z. */
    public var kittyPlacements: List<GhosttyKittyPlacement> = emptyList()
        private set

    private var buffer = ByteBuffer.allocateDirect(0)

    /** A new state has seen no rows yet, whatever the terminal already reported to an earlier renderer. */
    private var needsFullSnapshot = true
    private var kittyPlacementsDirty = false
    private var foreground = IntArray(0)
    private var background = IntArray(0)
    private var textOffset = IntArray(0)
    private var textLength = IntArray(0)
    private var flags = IntArray(0)
    private var rowChars = emptyArray<CharArray>()
    private var rowSelectionStart = IntArray(0)
    private var rowSelectionEnd = IntArray(0)

    public fun foreground(column: Int, row: Int): Int = foreground[row * columns + column]

    public fun background(column: Int, row: Int): Int = background[row * columns + column]

    /** [GhosttyCellFlags] bits of a cell. */
    public fun flags(column: Int, row: Int): Int = flags[row * columns + column] and FLAGS_MASK

    /** The underline style of a cell, one of the `GhosttyCellFlags.UNDERLINE_*` values. */
    public fun underline(column: Int, row: Int): Int = flags[row * columns + column] ushr UNDERLINE_SHIFT

    /** The UTF-16 text of a row; each cell's text is at [textOffset] with [textLength] chars. */
    public fun rowText(row: Int): CharArray = rowChars[row]

    public fun textOffset(column: Int, row: Int): Int = textOffset[row * columns + column]

    public fun textLength(column: Int, row: Int): Int = textLength[row * columns + column]

    /** The selected columns of a row, both included. */
    public fun selection(row: Int): IntRange? {
        val start = rowSelectionStart[row]
        return if (start < 0) null else start..rowSelectionEnd[row]
    }

    /**
     * Reads the terminal's changes. [selectionBackground] and [selectionForeground] color selected cells; without
     * them selection inverts the cell colors. Returns false when nothing changed.
     */
    public fun update(
        terminal: GhosttyTerminal,
        selectionBackground: Int? = null,
        selectionForeground: Int? = null,
    ): Boolean {
        var changed = false
        while (true) {
            if (buffer.capacity() < HEADER_BYTES) {
                buffer = allocate(HEADER_BYTES)
            }
            val rowCount = terminal.snapshot(buffer, full = needsFullSnapshot)
            if (rowCount < 0) return false
            needsFullSnapshot = false
            changed = applyRows(rowCount, selectionBackground, selectionForeground) || changed
            if (buffer.getInt(TRUNCATED_OFFSET) == 0) break
            // The rows that did not fit stay dirty in the terminal; read them into a larger buffer.
            buffer = allocate(maxOf(buffer.capacity() * 2, requiredCapacity()))
        }
        val newScrollbar = terminal.scrollbar
        val scrolled = newScrollbar != scrollbar
        scrollbar = newScrollbar
        val placementsDirty = kittyPlacementsDirty
        kittyPlacementsDirty = false
        val imagesChanged = if (changed || scrolled || placementsDirty) {
            val placements = readKittyPlacements(terminal)
            val placementsChanged = placements != kittyPlacements
            kittyPlacements = placements
            placementsChanged
        } else {
            false
        }
        return changed || scrolled || imagesChanged
    }

    private fun allocate(capacity: Int): ByteBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)

    private fun requiredCapacity(): Int {
        val cols = buffer.getInt(COLUMNS_OFFSET).coerceAtLeast(columns)
        val rows = buffer.getInt(ROWS_OFFSET).coerceAtLeast(rows)
        return HEADER_BYTES + rows * (ROW_HEADER_BYTES + cols * (CELL_RECORD_BYTES + TEXT_UNITS_PER_CELL * 2) + 4)
    }

    /** Applies the header and [rowCount] rows of the snapshot in [buffer]. */
    private fun applyRows(rowCount: Int, selectionBackground: Int?, selectionForeground: Int?): Boolean {
        val buffer = buffer
        buffer.position(0)
        check(buffer.int == SNAPSHOT_VERSION) { "snapshot version mismatch" }
        val dirty = buffer.int
        if (dirty and KITTY_PLACEMENTS_DIRTY_BIT != 0) kittyPlacementsDirty = true
        val snapshotColumns = buffer.int
        val snapshotRows = buffer.int
        val newBackground = buffer.int
        val newForeground = buffer.int
        val newCursorColumn = buffer.int
        val newCursorRow = buffer.int
        val cursorStyleValue = buffer.int
        val newCursorStyle = GhosttyCursorStyle.entries.firstOrNull {
            it.nativeValue == cursorStyleValue
        } ?: GhosttyCursorStyle.BLOCK
        val newCursorVisible = buffer.int != 0
        buffer.int
        val newCursorBlinking = buffer.int != 0
        val newCursorColor = buffer.int.takeIf { it != 0 }
        buffer.int
        val newPasswordInput = buffer.int != 0

        val changed = dirty and RENDER_DIRTY_MASK != DIRTY_FALSE
                || newCursorColumn != cursorColumn
                || newCursorRow != cursorRow
                || newCursorStyle != cursorStyle
                || newCursorVisible != cursorVisible
                || newCursorBlinking != cursorBlinking
                || newCursorColor != cursorColor
                || newPasswordInput != passwordInput
        resize(snapshotColumns, snapshotRows)
        defaultBackground = newBackground
        defaultForeground = newForeground
        cursorColumn = newCursorColumn
        cursorRow = newCursorRow
        cursorStyle = newCursorStyle
        cursorVisible = newCursorVisible
        cursorBlinking = newCursorBlinking
        cursorColor = newCursorColor
        passwordInput = newPasswordInput
        repeat(rowCount) { readRow(buffer, selectionBackground, selectionForeground) }
        return changed
    }

    private fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        columns = newColumns
        rows = newRows
        val cells = newColumns * newRows
        foreground = IntArray(cells)
        background = IntArray(cells)
        textOffset = IntArray(cells)
        textLength = IntArray(cells)
        flags = IntArray(cells)
        rowChars = Array(newRows) { CharArray(0) }
        rowSelectionStart = IntArray(newRows) { -1 }
        rowSelectionEnd = IntArray(newRows) { -1 }
    }

    private fun readRow(buffer: ByteBuffer, selectionBackground: Int?, selectionForeground: Int?) {
        val row = buffer.int
        val selectionStart = buffer.int
        var selectionEnd = buffer.int
        val textUnits = buffer.int
        val cellsPosition = buffer.position()
        val textPosition = cellsPosition + columns * CELL_RECORD_BYTES
        val next = textPosition + textUnits * 2 + if (textUnits % 2 != 0) 2 else 0
        if (row !in 0 until rows) {
            buffer.position(next)
            return
        }

        buffer.position(textPosition)
        if (rowChars[row].size < textUnits) rowChars[row] = CharArray(textUnits)
        val chars = rowChars[row]
        for (i in 0 until textUnits) chars[i] = buffer.char

        buffer.position(cellsPosition)
        val base = row * columns
        for (col in 0 until columns) {
            var fg = buffer.int
            var bg = buffer.int
            val offset = buffer.short.toInt() and 0xFFFF
            val length = buffer.short.toInt() and 0xFFFF
            val cellFlags = buffer.short.toInt() and 0xFFFF
            val underline = buffer.short.toInt() and UNDERLINE_MASK
            // A selection ending on a wide character covers its spacer too.
            if (col == selectionEnd && cellFlags and GhosttyCellFlags.WIDE != 0) selectionEnd++
            if (cellFlags and GhosttyCellFlags.FG_DEFAULT != 0) fg = defaultForeground
            if (cellFlags and GhosttyCellFlags.BG_NONE != 0) bg = defaultBackground
            if (cellFlags and GhosttyCellFlags.INVERSE != 0) fg = bg.also { bg = fg }
            if (selectionStart >= 0 && col in selectionStart..selectionEnd) {
                if (selectionBackground != null) {
                    bg = selectionBackground
                    if (selectionForeground != null) {
                        fg = selectionForeground
                    }
                } else {
                    fg = bg.also { bg = fg }
                }
            }
            foreground[base + col] = fg
            background[base + col] = bg
            textOffset[base + col] = offset
            textLength[base + col] = length
            flags[base + col] = cellFlags or (underline shl UNDERLINE_SHIFT)
        }
        rowSelectionStart[row] = selectionStart
        rowSelectionEnd[row] = selectionEnd.coerceAtMost(columns - 1)
        buffer.position(next)
    }

    private fun readKittyPlacements(terminal: GhosttyTerminal): List<GhosttyKittyPlacement> {
        val data = terminal.kittyPlacements() ?: return emptyList()
        return (0 until data.size / GhosttyVt.KITTY_PLACEMENT_LONGS).map { index ->
            val i = index * GhosttyVt.KITTY_PLACEMENT_LONGS
            GhosttyKittyPlacement(
                imageId = data[i].toInt(),
                imageGeneration = data[i + 1],
                column = data[i + 2].toInt(),
                row = data[i + 3].toInt(),
                offsetX = data[i + 4].toInt(),
                offsetY = data[i + 5].toInt(),
                width = data[i + 6].toInt(),
                height = data[i + 7].toInt(),
                sourceX = data[i + 8].toInt(),
                sourceY = data[i + 9].toInt(),
                sourceWidth = data[i + 10].toInt(),
                sourceHeight = data[i + 11].toInt(),
                z = data[i + 12].toInt(),
            )
        }
    }

    private companion object {
        const val SNAPSHOT_VERSION = 2
        const val HEADER_BYTES = 60
        const val ROW_HEADER_BYTES = 16
        const val CELL_RECORD_BYTES = 16
        const val TEXT_UNITS_PER_CELL = 4
        const val COLUMNS_OFFSET = 8
        const val ROWS_OFFSET = 12
        const val TRUNCATED_OFFSET = 52
        const val DIRTY_FALSE = 0
        const val RENDER_DIRTY_MASK = 0xFF
        const val KITTY_PLACEMENTS_DIRTY_BIT = 1 shl 8
        const val UNDERLINE_MASK = 0x7
        const val UNDERLINE_SHIFT = 16
        const val FLAGS_MASK = (1 shl UNDERLINE_SHIFT) - 1
    }
}

/** Cell attribute bits of [GhosttyRenderState.flags]. */
public object GhosttyCellFlags {
    public const val BOLD: Int = 1 shl 0
    public const val ITALIC: Int = 1 shl 1
    public const val FAINT: Int = 1 shl 2
    public const val UNDERLINE: Int = 1 shl 3
    public const val STRIKETHROUGH: Int = 1 shl 4
    public const val INVERSE: Int = 1 shl 5
    public const val INVISIBLE: Int = 1 shl 6

    /** The first cell of a two-cell character. */
    public const val WIDE: Int = 1 shl 7

    /** The second cell of a two-cell character, which has nothing to draw. */
    public const val SPACER: Int = 1 shl 8
    public const val FG_DEFAULT: Int = 1 shl 9
    public const val BG_NONE: Int = 1 shl 10

    public const val UNDERLINE_SINGLE: Int = 1
    public const val UNDERLINE_DOUBLE: Int = 2
    public const val UNDERLINE_CURLY: Int = 3
    public const val UNDERLINE_DOTTED: Int = 4
    public const val UNDERLINE_DASHED: Int = 5
}

/**
 * Where to draw part of a Kitty graphics image: the source rectangle of the image, in pixels, onto a
 * [width] x [height] pixel rectangle that starts [offsetX], [offsetY] pixels into the viewport cell at [column],
 * [row]. The row is negative when the image starts above the viewport. Placements with a negative [z] are drawn
 * below the text.
 */
public data class GhosttyKittyPlacement(
    val imageId: Int,
    /** Changes whenever the image pixels do. */
    val imageGeneration: Long,
    val column: Int,
    val row: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val z: Int,
)
