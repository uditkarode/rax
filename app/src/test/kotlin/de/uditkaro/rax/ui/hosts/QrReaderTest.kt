package de.uditkaro.rax.ui.hosts

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrReaderTest {
    private val text = "https://kuro-rex.tail1234.ts.net"

    /** A frame with the code in the middle, as dark modules on a light background or the other way round. */
    private fun frame(inverted: Boolean, width: Int = 640, height: Int = 480, rowStride: Int = 704): ByteArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        val dark: Byte = 20
        val light: Byte = 230.toByte()
        val bytes = ByteArray(rowStride * height) { if (inverted) dark else light }
        val left = (width - code.width) / 2
        val top = (height - code.height) / 2
        for (y in 0 until code.height) for (x in 0 until code.width) {
            val on = code[x, y] != inverted
            bytes[(top + y) * rowStride + left + x] = if (on) dark else light
        }
        return bytes
    }

    @Test
    fun readsACodeInAFrameWithPaddedRows() {
        assertEquals(text, QrReader().read(frame(inverted = false), 640, 480, 704))
    }

    @Test
    fun readsALightOnDarkCodeAsATerminalDrawsIt() {
        assertEquals(text, QrReader().read(frame(inverted = true), 640, 480, 704))
    }

    @Test
    fun aFrameWithoutACodeGivesNothing() {
        assertNull(QrReader().read(ByteArray(704 * 480) { 128.toByte() }, 640, 480, 704))
    }
}
