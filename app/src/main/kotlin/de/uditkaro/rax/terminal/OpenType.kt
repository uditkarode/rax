package de.uditkaro.rax.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads what the font picker needs from a TrueType, OpenType or collection file: each face's family
 * and style names, weight, slant, and whether its ASCII glyphs share one advance width.
 */
object OpenType {

    data class Face(
        /** Index inside a collection; 0 for a single font. */
        val index: Int,
        val family: String,
        val style: String,
        val weight: Int,
        val italic: Boolean,
        val monospaced: Boolean,
    )

    class NotAFont(message: String) : Exception(message)

    fun read(bytes: ByteArray): List<Face> = try {
        readFaces(bytes)
    } catch (_: IndexOutOfBoundsException) {
        throw NotAFont("truncated")
    }

    private fun readFaces(bytes: ByteArray): List<Face> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 12) throw NotAFont("too short")
        return when (val tag = buf.getInt(0)) {
            TTCF -> {
                val count = buf.getInt(8)
                if (count !in 1..256) throw NotAFont("bad collection")
                (0 until count).map { i -> face(buf, buf.getInt(12 + 4 * i), i) }
            }
            TRUETYPE, OTTO, TRUE -> listOf(face(buf, 0, 0))
            else -> throw NotAFont("unknown signature 0x${tag.toUInt().toString(16)}")
        }
    }

    private fun face(buf: ByteBuffer, offset: Int, index: Int): Face {
        val tables = tables(buf, offset)
        val name = tables["name"] ?: throw NotAFont("no name table")
        val names = names(buf, name)
        val family = names[16] ?: names[1] ?: throw NotAFont("no family name")
        val style = names[17] ?: names[2] ?: "Regular"

        var weight = 400
        var italic = style.contains("italic", ignoreCase = true) || style.contains("oblique", ignoreCase = true)
        tables["OS/2"]?.let { os2 ->
            weight = buf.getShort(os2 + 4).toInt() and 0xFFFF
            val fsSelection = buf.getShort(os2 + 62).toInt()
            italic = italic || fsSelection and 1 != 0
        }
        tables["head"]?.let { head -> italic = italic || buf.getShort(head + 44).toInt() and 2 != 0 }

        return Face(index, family.trim(), style.trim(), weight.coerceIn(1, 1000), italic, monospaced(buf, tables))
    }

    private fun tables(buf: ByteBuffer, offset: Int): Map<String, Int> {
        val count = buf.getShort(offset + 4).toInt() and 0xFFFF
        if (count == 0 || offset + 12 + count * 16 > buf.limit()) throw NotAFont("bad table directory")
        return (0 until count).associate { i ->
            val record = offset + 12 + i * 16
            val tag = String(ByteArray(4) { buf.get(record + it) }, Charsets.ISO_8859_1)
            val tableOffset = buf.getInt(record + 8)
            if (tableOffset < 0 || tableOffset >= buf.limit()) throw NotAFont("bad table offset")
            tag to tableOffset
        }
    }

    /** Name ID to string, preferring Windows Unicode English, then any Unicode, then Mac Roman. */
    private fun names(buf: ByteBuffer, table: Int): Map<Int, String> {
        val count = buf.getShort(table + 2).toInt() and 0xFFFF
        val storage = table + (buf.getShort(table + 4).toInt() and 0xFFFF)
        val best = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val r = table + 6 + i * 12
            val platform = buf.getShort(r).toInt() and 0xFFFF
            val encoding = buf.getShort(r + 2).toInt() and 0xFFFF
            val language = buf.getShort(r + 4).toInt() and 0xFFFF
            val id = buf.getShort(r + 6).toInt() and 0xFFFF
            val length = buf.getShort(r + 8).toInt() and 0xFFFF
            val start = storage + (buf.getShort(r + 10).toInt() and 0xFFFF)
            if (id !in setOf(1, 2, 16, 17) || start + length > buf.limit()) continue
            val rank = when {
                platform == 3 && (encoding == 1 || encoding == 10) && language == 0x409 -> 3
                platform == 3 || platform == 0 -> 2
                platform == 1 && encoding == 0 -> 1
                else -> continue
            }
            if ((best[id]?.first ?: 0) >= rank) continue
            val raw = ByteArray(length) { buf.get(start + it) }
            val text = if (rank == 1) String(raw, Charsets.ISO_8859_1) else String(raw, Charsets.UTF_16BE)
            best[id] = rank to text
        }
        return best.mapValues { it.value.second }
    }

    /**
     * True when the printable ASCII glyphs the font has all advance by the same width. The `post`
     * table's fixed-pitch flag is not trusted: many terminal fonts leave it unset.
     */
    private fun monospaced(buf: ByteBuffer, tables: Map<String, Int>): Boolean {
        val cmap = tables["cmap"] ?: return false
        val hhea = tables["hhea"] ?: return false
        val hmtx = tables["hmtx"] ?: return false
        val metrics = buf.getShort(hhea + 34).toInt() and 0xFFFF
        if (metrics == 0) return false
        val glyphs = glyphLookup(buf, cmap) ?: return false
        val widths = HashSet<Int>()
        for (cp in 0x21..0x7E) {
            val glyph = glyphs(cp)
            if (glyph == 0) continue
            val entry = minOf(glyph, metrics - 1)
            widths += buf.getShort(hmtx + entry * 4).toInt() and 0xFFFF
            if (widths.size > 1) return false
        }
        return widths.size == 1
    }

    /** A codepoint-to-glyph function from the best Unicode subtable (format 12, then 4). */
    private fun glyphLookup(buf: ByteBuffer, cmap: Int): ((Int) -> Int)? {
        val count = buf.getShort(cmap + 2).toInt() and 0xFFFF
        var format4 = -1
        var format12 = -1
        for (i in 0 until count) {
            val r = cmap + 4 + i * 8
            val platform = buf.getShort(r).toInt() and 0xFFFF
            val encoding = buf.getShort(r + 2).toInt() and 0xFFFF
            val sub = cmap + buf.getInt(r + 4)
            if (platform != 0 && platform != 3) continue
            if (platform == 3 && encoding != 1 && encoding != 10) continue
            when (buf.getShort(sub).toInt() and 0xFFFF) {
                12 -> format12 = sub
                4 -> format4 = sub
            }
        }
        if (format12 >= 0) {
            val groups = buf.getInt(format12 + 12)
            return { cp ->
                var glyph = 0
                for (g in 0 until groups) {
                    val r = format12 + 16 + g * 12
                    val start = buf.getInt(r)
                    val end = buf.getInt(r + 4)
                    if (cp in start..end) {
                        glyph = buf.getInt(r + 8) + (cp - start)
                        break
                    }
                }
                glyph
            }
        }
        if (format4 >= 0) {
            val segX2 = buf.getShort(format4 + 6).toInt() and 0xFFFF
            val ends = format4 + 14
            val starts = ends + segX2 + 2
            val deltas = starts + segX2
            val ranges = deltas + segX2
            return { cp ->
                var glyph = 0
                for (s in 0 until segX2 / 2) {
                    val end = buf.getShort(ends + s * 2).toInt() and 0xFFFF
                    if (cp > end) continue
                    val start = buf.getShort(starts + s * 2).toInt() and 0xFFFF
                    if (cp < start) break
                    val delta = buf.getShort(deltas + s * 2).toInt()
                    val rangeOffset = buf.getShort(ranges + s * 2).toInt() and 0xFFFF
                    glyph = if (rangeOffset == 0) {
                        (cp + delta) and 0xFFFF
                    } else {
                        val at = ranges + s * 2 + rangeOffset + (cp - start) * 2
                        val g = buf.getShort(at).toInt() and 0xFFFF
                        if (g == 0) 0 else (g + delta) and 0xFFFF
                    }
                    break
                }
                glyph
            }
        }
        return null
    }

    private const val TTCF = 0x74746366
    private const val TRUETYPE = 0x00010000
    private const val OTTO = 0x4F54544F
    private const val TRUE = 0x74727565
}
