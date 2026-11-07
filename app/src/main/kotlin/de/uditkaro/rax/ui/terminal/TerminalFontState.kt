package de.uditkaro.rax.ui.terminal

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import de.uditkaro.rax.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The terminal font's regular face as a Compose family, loaded off the main thread. */
@Composable
fun rememberTerminalFontFamily(graph: AppGraph, fontId: String): FontFamily {
    val typeface by produceState<Typeface?>(null, fontId) {
        value = withContext(Dispatchers.IO) { graph.fonts.preview(fontId) }
    }
    return remember(typeface) { typeface?.let { FontFamily(it) } ?: FontFamily.Monospace }
}

/** The terminal font's full family for the terminal view, or null while it loads. */
@Composable
fun rememberTerminalTypeface(graph: AppGraph, fontId: String): Typeface? {
    val typeface by produceState<Typeface?>(null, fontId) {
        value = withContext(Dispatchers.IO) { graph.fonts.typeface(fontId) }
    }
    return typeface
}
