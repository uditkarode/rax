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
    private var readingSnapshot = true
    private var snap = ByteArray(0)
    private var snapLength = 0
    /** Offset of the next snapshot record header not yet read. */
    private var cursor = 10

    private var matched = 0
    private var inBody = false
    private var sawEsc = false
    private val held = ByteArrayOutputStream()
    private val body = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<StreamPart> {
        val parts = ArrayList<StreamPart>(2)
        if (readingSnapshot) {
            if (snap.size < snapLength + bytes.size) snap = snap.copyOf(maxOf(snap.size * 2, snapLength + bytes.size, 64 * 1024))
            bytes.copyInto(snap, snapLength)
            snapLength += bytes.size
            val end = findEnd() ?: return parts
            readingSnapshot = false
            parts += StreamPart.Snapshot(if (end == 0) null else snap.copyOfRange(0, end))
            scan(snap, end, snapLength, parts)
            snap = ByteArray(0)
            snapLength = 0
        } else {
            scan(bytes, 0, bytes.size, parts)
        }
        return parts
    }

    private fun scan(bytes: ByteArray, from: Int, to: Int, parts: MutableList<StreamPart>) {
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val b = bytes[i]
            when {
                inBody -> {
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
                        out.write(held.toByteArray())
                        out.write(body.toByteArray())
                        body.reset()
                        held.reset()
                        inBody = false
                    }
                }
                matched > 0 || b == ESC -> {
                    if (b == prefix[matched]) {
                        held.write(b.toInt())
                        matched++
                        if (matched == prefix.size) {
                            matched = 0
                            inBody = true
                        }
                    } else {
                        out.write(held.toByteArray())
                        held.reset()
                        val restart = matched > 0
                        matched = 0
                        if (restart) continue
                        out.write(b.toInt())
                    }
                }
                else -> out.write(b.toInt())
            }
            i++
        }
        flush(out, parts)
    }

    /** See [snapshotEnd]; this one resumes from the last record it read. */
    private fun findEnd(): Int? {
        val head = minOf(snapLength, MAGIC.size)
        for (k in 0 until head) if (snap[k] != MAGIC[k]) return 0
        while (true) {
            if (snapLength < cursor + 10) return null
            val tag = u16(snap, cursor)
            val length = u32(snap, cursor + 2)
            if (length > MAX_SNAPSHOT) throw IllegalStateException("snapshot record too large")
            val next = cursor + 10 + length.toInt()
            if (snapLength < next) return null
            if (tag == FINISH) return next
            cursor = next
        }
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

    companion object {
        private const val ESC: Byte = 0x1b
        private const val MAX_MESSAGE = 64 * 1024
        private val MAGIC = "GHOSTSNP".toByteArray()
        private const val FINISH = 6
        private const val MAX_SNAPSHOT = 128 * 1024 * 1024

        /**
         * Returns the offset where the snapshot ends. Returns 0 if the bytes don't start with
         * GHOSTSNP, and null if the whole snapshot hasn't arrived yet. Records are `tag u16, length u32, crc u32, payload`, little-endian,
         * after an 8-byte magic and a u16 version, and the record tagged 6 is the last.
         */
        fun snapshotEnd(bytes: ByteArray): Int? {
            val head = minOf(bytes.size, MAGIC.size)
            for (k in 0 until head) if (bytes[k] != MAGIC[k]) return 0
            if (bytes.size < 10) return null
            var at = 10
            while (true) {
                if (bytes.size < at + 10) return null
                val tag = u16(bytes, at)
                val length = u32(bytes, at + 2)
                if (length < 0 || length > MAX_SNAPSHOT) throw IllegalStateException("snapshot record too large")
                val next = at + 10 + length.toInt()
                if (bytes.size < next) return null
                if (tag == FINISH) return next
                at = next
            }
        }

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
