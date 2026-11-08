package io.github.xchacha20_poly1305.libghostty.compose

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.foundation.magnifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.xchacha20_poly1305.libghostty.GhosttyAndroidClipboard
import io.github.xchacha20_poly1305.libghostty.GhosttyClipboard
import kotlin.math.ceil

/**
 * Measures and draws terminal text with a platform font: one cell at a time, at exact cell positions, which a
 * paragraph layout cannot do fast enough for a full screen every frame.
 */
internal interface GlyphPainter {
    val cellWidth: Float
    val cellHeight: Int

    /** Distance from the top of a cell to the text baseline. */
    val baseline: Float

    fun measure(text: String): Float

    fun draw(
        canvas: Canvas,
        text: CharArray,
        offset: Int,
        length: Int,
        x: Float,
        baselineY: Float,
        color: Int,
        bold: Boolean,
        italic: Boolean,
    )
}

internal fun GlyphPainter(
    fontFamily: FontFamily,
    fontSizePx: Float,
    resolver: FontFamily.Resolver,
): GlyphPainter {
    val typeface = resolver.resolve(fontFamily, FontWeight.Normal, FontStyle.Normal).value as? Typeface
        ?: Typeface.MONOSPACE
    return AndroidGlyphPainter(typeface, fontSizePx)
}

private class AndroidGlyphPainter(typeface: Typeface, sizePx: Float) : GlyphPainter {
    private val regular = paint(typeface, sizePx)
    private val bold = paint(Typeface.create(typeface, Typeface.BOLD), sizePx)
    private val italic = paint(Typeface.create(typeface, Typeface.ITALIC), sizePx)
    private val boldItalic = paint(Typeface.create(typeface, Typeface.BOLD_ITALIC), sizePx)

    override val cellWidth: Float = regular.measureText("M")
    override val cellHeight: Int = ceil(regular.fontMetrics.descent - regular.fontMetrics.ascent).toInt()
    override val baseline: Float = -regular.fontMetrics.ascent

    override fun measure(text: String): Float = regular.measureText(text)

    override fun draw(
        canvas: Canvas,
        text: CharArray,
        offset: Int,
        length: Int,
        x: Float,
        baselineY: Float,
        color: Int,
        bold: Boolean,
        italic: Boolean,
    ) {
        val paint = when {
            bold && italic -> boldItalic
            bold -> this.bold
            italic -> this.italic
            else -> regular
        }
        paint.color = color
        canvas.nativeCanvas.drawText(text, offset, length, x, baselineY, paint)
    }

    private fun paint(typeface: Typeface, sizePx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = sizePx
    }
}

internal fun imageBitmapOf(width: Int, height: Int, argb: IntArray): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

@Composable
internal fun rememberPlatformClipboard(): GhosttyClipboard {
    val context = LocalContext.current
    return remember(context) { GhosttyAndroidClipboard(context) }
}

/** Shows a string resource briefly over the app. */
@Composable
internal fun rememberShowMessage(): (Int) -> Unit {
    val context = LocalContext.current
    return remember(context) { { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }
}

/** Whether an accessibility service, such as a screen reader, is on. */
@Composable
internal fun rememberAccessibilityEnabled(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val enabled = remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled.value = it }
        manager?.addAccessibilityStateChangeListener(listener)
        enabled.value = manager?.isEnabled == true
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}

/** Shows a platform magnifier over [center] while it is specified. */
internal fun Modifier.selectionMagnifier(center: () -> Offset): Modifier =
    magnifier(sourceCenter = { center() })
