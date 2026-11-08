package de.uditkaro.rax.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType

private val BarHeight = 56.dp

/**
 * A screen with a large title that scrolls away under a top bar, which then shows the title small.
 * [content] fills a lazy list below the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backIcon: Int = R.drawable.ic_arrow_back,
    backLabel: String = stringResource(R.string.back),
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    bottomBar: (@Composable () -> Unit)? = null,
    /** Pull to refresh, with [refreshing] while it runs. */
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val c = Rax.colors
    val density = LocalDensity.current
    val collapseAt = with(density) { 30.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > collapseAt }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    Column(modifier.fillMaxSize().background(c.page).imePadding()) {
        Box(Modifier.weight(1f)) {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BarHeight
            val bottom = if (bottomBar == null) {
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp
            } else {
                24.dp
            }
            val list = @Composable {
                LazyColumn(
                    Modifier.fillMaxSize().windowInsetsPadding(sides),
                    state = state,
                    contentPadding = PaddingValues(top = top, bottom = bottom),
                ) {
                    item(key = "large-title", contentType = "large-title") {
                        Text(
                            title,
                            Modifier
                                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp)
                                .graphicsLayer { alpha = 1f - barAlpha }
                                .semantics { heading() },
                            style = RaxType.largeTitle,
                            color = c.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    content()
                }
            }
            if (onRefresh == null) {
                list()
            } else {
                val pull = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    state = pull,
                    indicator = {
                        // Below the top bar, which covers the top of the list.
                        PullToRefreshDefaults.Indicator(
                            state = pull,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
                            containerColor = c.card,
                            color = c.accent,
                        )
                    },
                ) { list() }
            }

            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.page.copy(alpha = barAlpha))
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(sides)
                        .height(BarHeight)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconAction(backIcon, backLabel, onBack)
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        title,
                        Modifier.weight(1f).padding(horizontal = 4.dp).graphicsLayer { alpha = barAlpha },
                        style = RaxType.headline,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    actions()
                }
                Hairline(Modifier.graphicsLayer { alpha = barAlpha })
            }
        }
        if (bottomBar != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(sides)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            ) { bottomBar() }
        }
    }
}
