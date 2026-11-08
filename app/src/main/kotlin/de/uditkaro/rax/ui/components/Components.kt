package de.uditkaro.rax.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.Rax
import de.uditkaro.rax.theme.RaxType

object Metrics {
    val screenPadding = 16.dp
    val rowPadding = 16.dp
    val rowHeight = 52.dp
    val groupRadius = 16.dp
    val groupShape = RoundedCornerShape(groupRadius)
    val touch = 48.dp
}

@Composable
fun RaxIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Rax.colors.text,
    size: Dp = 24.dp,
) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}

/** An icon with a 48dp touch target and a round ripple. */
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Rax.colors.text,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(Metrics.touch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        RaxIcon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = Metrics.screenPadding + 4.dp, end = Metrics.screenPadding, top = 24.dp, bottom = 8.dp),
        style = RaxType.subhead,
        color = Rax.colors.textSecondary,
    )
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = Metrics.screenPadding + 4.dp, end = Metrics.screenPadding + 4.dp, top = 8.dp),
        style = RaxType.footnote,
        color = Rax.colors.textSecondary,
    )
}

class GroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** Rows on one card with hairlines between them, inset to where the text starts. */
@Composable
fun Group(modifier: Modifier = Modifier, dividerInset: Dp = Metrics.rowPadding, build: GroupScope.() -> Unit) {
    val rows = GroupScope().apply(build).rows
    Column(
        modifier
            .padding(horizontal = Metrics.screenPadding)
            .fillMaxWidth()
            .clip(Metrics.groupShape)
            .background(Rax.colors.card),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Hairline(Modifier.padding(start = dividerInset))
            row()
        }
    }
}

/** One physical pixel. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    Box(modifier.fillMaxWidth().height(onePixel).background(Rax.colors.stroke))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowBase(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Metrics.rowPadding, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.rowHeight)
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(role = role, onLongClick = onLongClick, onClick = onClick)
                    onClick != null -> Modifier.clickable(role = role, onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Opens another screen: a title, the current value, and a chevron. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    subtitleFamily: FontFamily? = null,
    trailingIcon: Int = R.drawable.ic_chevron_right,
) {
    RowBase(onClick, modifier) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RaxType.body, color = Rax.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = RaxType.footnote.copy(fontFamily = subtitleFamily ?: RaxType.footnote.fontFamily),
                    color = Rax.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(value, style = RaxType.body, color = Rax.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RaxIcon(trailingIcon, null, tint = Rax.colors.textTertiary, size = 20.dp)
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // The row carries the on or off state for screen readers, so the switch takes no input itself.
    RowBase(
        onClick = null,
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentPadding = PaddingValues(start = Metrics.rowPadding, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(title, Modifier.weight(1f), style = RaxType.body, color = Rax.colors.text)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Rax.colors.accent,
                checkedThumbColor = Rax.colors.onAccent,
                checkedBorderColor = Rax.colors.accent,
                uncheckedTrackColor = Rax.colors.control,
                uncheckedThumbColor = Rax.colors.textSecondary,
                uncheckedBorderColor = Rax.colors.strokeStrong,
            ),
        )
    }
}

/** A row that does something right away, in the accent colour, or the danger colour when it removes. */
@Composable
fun ActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val color = (if (destructive) Rax.colors.danger else Rax.colors.link).let { if (enabled) it else it.copy(alpha = 0.38f) }
    RowBase(if (enabled) onClick else null, modifier) {
        if (icon != null) RaxIcon(icon, null, tint = color, size = 22.dp)
        Text(text, style = RaxType.body, color = color)
    }
}

@Composable
fun CheckRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = RaxType.body,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    RowBase(
        onClick,
        modifier.semantics { this.selected = selected },
        role = Role.RadioButton,
        onLongClick = onLongClick,
    ) {
        Text(title, Modifier.weight(1f), style = titleStyle, color = Rax.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) RaxIcon(R.drawable.ic_check, null, tint = Rax.colors.link, size = 22.dp)
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Rax.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(if (enabled) c.accent else c.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RaxType.headline, color = if (enabled) c.onAccent else c.textTertiary)
    }
}

/** Connectivity only: filled when the computer is reachable, a ring when it is not. */
@Composable
fun StatusDot(online: Boolean, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val c = Rax.colors
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .then(
                if (online) Modifier.background(c.success)
                else Modifier.border(1.5.dp, c.textSecondary, CircleShape),
            )
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    )
}

@Composable
fun ColumnScope.VerticalSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun RowScope.HorizontalSpace(width: Dp) = Spacer(Modifier.width(width))
