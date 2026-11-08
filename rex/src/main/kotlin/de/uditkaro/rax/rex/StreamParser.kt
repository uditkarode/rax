package de.uditkaro.rax.rex

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a terminal data stream carries, split into the parts the app acts on. */
sealed interface StreamPart {
    /** The terminal's state when the stream began, as libghostty's GHOSTSNP snapshot; null if the stream had none. */
    class Snapshot(val bytes: ByteArray?) : StreamPart
    /** Program output, to feed to the emulator in order. */
    class Output(val bytes: ByteArray) : StreamPart
    /** The terminal's size changed at this point in the stream. [owner] is the client whose size it is. */
    data class Resize(val columns: Int, val rows: Int, val owner: String?) : StreamPart
    data class Exited(val code: Int) : StreamPart
}

/**
 * Reads the data stream for one pane. The stream starts with a GHOSTSNP snapshot of the screen.
 * After the snapshot, the stream carries the program's output, and Rex mixes its own control
 * messages into it as `ESC _ rex1;<nonce>;<json> ESC \`. The parser removes these messages and
 * returns the rest as output. If a message has a different nonce, a program printed it, so it
 * stays in the output.
 */
class StreamParser(nonce: Long) {
    // The nonce is an unsigned 64-bit number on the wire and in the message text.
    private val prefix = "\u001b_rex1;${java.lang.Long.toUnsignedString(nonce)};".toByteArray()
    /** How far the search for [prefix] moves on after a window that ends in a given byte. */
    private val skip = IntArray(256) { prefix.size }.also { table ->
        for (k in 0 until prefix.size - 1) table[prefix[k].toInt() and 0xFF] = prefix.size - 1 - k
    }
    private var readingSnapshot = true
    private var snap = ByteArray(0)
    private var snapLength = 0
    /** Offset of the next snapshot record header not yet read. */
    private var cursor = HEADER
    /** False once the snapshot's version or records turn out to be ones Rax cannot read. */
    private var framingKnown = true
    /** The search for Rex's first message in [snap] goes on from this offset. */
    private var searched = 0

    private var matched = 0
    private var inBody = false
    private var sawEsc = false
    private val held = ByteArrayOutputStream()
    private val body = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<StreamPart> {
        val parts = ArrayList<StreamPart>(2)
        if (readingSnapshot) {
            val needed = snapLength + bytes.size
            if (snap.size < needed) snap = snap.copyOf(maxOf(needed, minOf(snap.size * 2, MAX_SNAPSHOT), 64 * 1024))
            bytes.copyInto(snap, snapLength)
            snapLength = needed
            val end = findEnd() ?: return parts
            readingSnapshot = false
            parts += StreamPart.Snapshot(end.snapshot)
            scan(snap, end.output, snapLength, parts)
            snap = ByteArray(0)
            snapLength = 0
        } else {
            scan(bytes, 0, bytes.size, parts)
        }
        return parts
    }

    private fun scan(bytes: ByteArray, from: Int, to: Int, parts: MutableList<StreamPart>) {
        if (from == 0 && to == bytes.size && !inBody && matched == 0 && bytes.indexOf(ESC) < 0) {
            if (to > 0) parts += StreamPart.Output(bytes)
            return
        }
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            if (!inBody && matched == 0) {
                // Output up to the next ESC goes out in one piece.
                var end = i
                while (end < to && bytes[end] != ESC) end++
                out.write(bytes, i, end - i)
                i = end
                if (i == to) break
            }
            val b = bytes[i]
            if (inBody) {
                if (sawEsc) {
                    sawEsc = false
                    if (b == '\\'.code.toByte()) {
                        inBody = false
                        flush(out, parts)
                        message(body.toByteArray())?.let(parts::add)
                        body.reset()
                        held.reset()
                    } else {
                        body.write(ESC.toInt())
                        body.write(b.toInt())
                    }
                } else if (b == ESC) {
                    sawEsc = true
                } else {
                    body.write(b.toInt())
                }
                if (body.size() > MAX_MESSAGE) {
                    // Not a message Rex would send; hand it to the terminal as it came.
                    held.writeTo(out)
                    body.writeTo(out)
                    body.reset()
                    held.reset()
                    inBody = false
                }
            } else if (b == prefix[matched]) {
                held.write(b.toInt())
                matched++
                if (matched == prefix.size) {
                    matched = 0
                    inBody = true
                }
            } else {
                // The bytes held so far were output after all; look at this byte again from the start.
                held.writeTo(out)
                held.reset()
                matched = 0
                continue
            }
            i++
        }
        flush(out, parts)
    }

    private class End(val snapshot: ByteArray?, val output: Int)

    /**
     * Finds where the snapshot ends: after its FINISH record, or at Rex's first message, which comes
     * right after the snapshot. Rex's message marks the end when the version or the records are
     * ones Rax cannot read, or when it arrives before the records end. A snapshot found that way is
     * dropped, and so is one larger than [MAX_SNAPSHOT]. Returns null while the end has not arrived.
     */
    private fun findEnd(): End? {
        val head = minOf(snapLength, MAGIC.size)
        for (k in 0 until head) if (snap[k] != MAGIC[k]) return End(null, 0)
        if (framingKnown) {
            val finish = walk()
            if (finish == BAD) framingKnown = false else if (finish != null) return End(snap.copyOfRange(0, finish), finish)
        }
        val marker = findMarker()
        if (marker >= 0) return End(null, marker)
        if (snapLength > MAX_SNAPSHOT) return End(null, snapLength)
        return null
    }

    /**
     * Reads records from [cursor], which are `tag u16, length u32, crc u32, payload`, little-endian,
     * after an 8-byte magic and a u16 version. The record tagged 6 is the last. Returns the offset
     * after it, null if more bytes are needed, or [BAD].
     */
    private fun walk(): Int? {
        if (snapLength < HEADER) return null
        if (u16(snap, MAGIC.size) != VERSION) return BAD
        while (true) {
            if (snapLength < cursor + RECORD_HEADER) return null
            val tag = u16(snap, cursor)
            val next = cursor + RECORD_HEADER + u32(snap, cursor + 2)
            if (next > MAX_SNAPSHOT) return BAD
            if (snapLength < next) return null
            if (tag == FINISH) return next.toInt()
            cursor = next.toInt()
        }
    }

    /**
     * The offset of Rex's first message in [snap], or -1. A Horspool search: it reads the last byte of each window
     * and moves on by up to the length of [prefix], so it reads a small part of a long snapshot.
     */
    private fun findMarker(): Int {
        val n = prefix.size
        var i = searched
        while (i <= snapLength - n) {
            val last = snap[i + n - 1]
            if (last == prefix[n - 1] && (0 until n - 1).all { snap[i + it] == prefix[it] }) return i
            i += skip[last.toInt() and 0xFF]
        }
        searched = i
        return -1
    }

    private fun flush(out: ByteArrayOutputStream, parts: MutableList<StreamPart>) {
        if (out.size() > 0) {
            parts += StreamPart.Output(out.toByteArray())
            out.reset()
        }
    }

    private fun message(json: ByteArray): StreamPart? {
        val m = runCatching { RexJson.parseToJsonElement(json.decodeToString()).jsonObject }.getOrNull() ?: return null
        return when (m.str("type")) {
            "resize" -> StreamPart.Resize(
                columns = m.int("cols") ?: return null,
                rows = m.int("rows") ?: return null,
                owner = (m["owner"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            )
            "child_exited" -> StreamPart.Exited(m.int("exit_code") ?: 0)
            else -> null
        }
    }

    private companion object {
        const val ESC: Byte = 0x1b
        const val MAX_MESSAGE = 64 * 1024
        val MAGIC = "GHOSTSNP".toByteArray()
        const val VERSION = 1
        const val HEADER = 10
        const val RECORD_HEADER = 10
        const val FINISH = 6
        const val MAX_SNAPSHOT = 128 * 1024 * 1024
        const val BAD = -1

        fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
