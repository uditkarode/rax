package de.uditkaro.rax.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import de.uditkaro.rax.theme.Rax
import kotlinx.coroutines.flow.collectLatest

/**
 * The dim behind a [RaxSheet]. Material's sheet fades its own dim out as soon as a drag passes the
 * point where letting go would dismiss it, so Rax draws the dim itself, in the activity's window
 * under the sheet's window.
 */
@Stable
class SheetScrim {
    internal var alpha: (() -> Float)? by mutableStateOf(null)
}

val LocalSheetScrim = staticCompositionLocalOf<SheetScrim> { error("No SheetScrim") }

/** Draws [scrim] over the screen while a sheet shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScrimLayer(scrim: SheetScrim) {
    val alpha = scrim.alpha ?: return
    val color = BottomSheetDefaults.ScrimColor
    Box(Modifier.fillMaxSize().drawBehind { drawRect(color, alpha = alpha()) })
}

/** A modal bottom sheet whose dim stays while the sheet is dragged, and fades only when it leaves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaxSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrim = LocalSheetScrim.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(sheetState) {
        // A finger drag runs no animation, so only a sheet on its way out counts as leaving.
        snapshotFlow { sheetState.isAnimationRunning && sheetState.targetValue == SheetValue.Hidden }
            .collectLatest { leaving -> alpha.animateTo(if (leaving) 0f else 1f) }
    }
    DisposableEffect(scrim) {
        val mine = { alpha.value }
        scrim.alpha = mine
        onDispose { if (scrim.alpha === mine) scrim.alpha = null }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Rax.colors.sheet,
        scrimColor = Color.Transparent,
        content = content,
    )
}
