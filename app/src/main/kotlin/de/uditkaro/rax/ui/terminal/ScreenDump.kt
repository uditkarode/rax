package de.uditkaro.rax.ui.terminal

import android.util.Log
import de.uditkaro.rax.rex.RexException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A pane's screen and scrollback as escape sequences, laid out for a terminal of [columns] by [rows]. [parts] go to the
 * emulator in order. [drawnAt] is about when output that came after the drawing began to reach the phone, on the
 * [System.nanoTime] clock: output that arrived before then is part of it.
 */
internal class ScreenDump(val columns: Int, val rows: Int, val parts: List<ByteArray>, val drawnAt: Long)

/**
 * Reads a pane's screen from Rex, for a snapshot this build cannot read. [call] calls one of the pane's methods, and
 * [onProgress] runs after each reply. Returns null if Rex cannot send all of it. Throws the [RexException] of a call
 * that can work later: its answer was lost, or Rex has not attached the session to a new connection yet.
 */
internal suspend fun readScreenDump(
    call: suspend (method: String, args: JsonObject?) -> JsonObject,
    onProgress: () -> Unit = {},
    now: () -> Long = System::nanoTime,
): ScreenDump? {
    try {
        // A size read before and after the screen tells whether the pane was resized in between, which would lay the
        // screen out for another grid. The short size calls also time a round trip.
        var sent = now()
        var size = call("size", null).grid() ?: return null
        var roundTrip = now() - sent
        onProgress()
        var tries = 0
        while (true) {
            sent = now()
            val reply = call("format", FORMAT_ARGS)
            // Rex draws the screen about half a round trip after the call goes out, and its output from then on
            // reaches the phone about half a round trip later. A long screen's reply can take much longer than that.
            val drawnAt = minOf(sent + roundTrip, now())
            onProgress()
            sent = now()
            val after = call("size", null).grid() ?: return null
            roundTrip = now() - sent
            onProgress()
            if (after != size && ++tries < SIZE_TRIES) {
                size = after
                continue
            }
            val parts = readParts(reply, call, onProgress) ?: return null
            return ScreenDump(after.columns, after.rows, parts, drawnAt)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RexException) {
        if (e.replyLost || e.code == "conflict") throw e
        Log.w(TAG, "could not read the screen", e)
        return null
    } catch (e: Exception) {
        Log.w(TAG, "could not read the screen", e)
        return null
    }
}

private data class Grid(val columns: Int, val rows: Int)

private fun JsonObject.grid(): Grid? {
    val columns = int("columns")?.takeIf { it > 0 } ?: return null
    val rows = int("rows")?.takeIf { it > 0 } ?: return null
    return Grid(columns, rows)
}

/** The screen in [reply], and after it, for a longer screen, the parts read from where the last one ended. */
private suspend fun readParts(
    reply: JsonObject,
    call: suspend (method: String, args: JsonObject?) -> JsonObject,
    onProgress: () -> Unit,
): List<ByteArray>? {
    val first = reply.string("content")?.encodeToByteArray() ?: return null
    val parts = mutableListOf(first)
    val id = reply.long("id")
    if (id == null || reply.bool("eof") == true) return parts
    var offset = first.size.toLong()
    do {
        val next = call("blob_read", buildJsonObject {
            put("id", id)
            put("offset", offset)
            put("max_bytes", MAX_PART_BYTES)
        })
        onProgress()
        val data = next.string("data")?.encodeToByteArray() ?: return null
        val eof = next.bool("eof") == true
        if (data.isEmpty() && !eof) return null
        parts += data
        offset += data.size
    } while (!eof)
    return parts
}

// The phone's theme sets the palette, so Rex's palette stays out.
private val FORMAT_ARGS = buildJsonObject {
    put("format", "vt")
    put("extra_cursor", true)
    put("extra_modes", true)
    put("extra_style", true)
    put("extra_scrolling_region", true)
    put("extra_tabstops", true)
    put("extra_charsets", true)
    put("extra_keyboard", true)
    put("extra_kitty_keyboard", true)
    put("extra_pwd", true)
    put("max_bytes", MAX_PART_BYTES)
}

/** The largest part Rex sends in one reply. */
private const val MAX_PART_BYTES = 960 * 1024

/** How many times the screen is read again while the pane keeps changing size. */
private const val SIZE_TRIES = 3

private const val TAG = "ScreenDump"

private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull
private fun JsonObject.int(key: String) = primitive(key)?.intOrNull
private fun JsonObject.long(key: String) = primitive(key)?.longOrNull
private fun JsonObject.bool(key: String) = primitive(key)?.booleanOrNull
