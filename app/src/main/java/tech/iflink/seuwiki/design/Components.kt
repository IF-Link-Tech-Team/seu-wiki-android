package tech.iflink.seuwiki.design

import tech.iflink.seuwiki.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The iOS `cardStyle()` modifier: a raised card on the grouped background.
 *
 * Mirrors `.padding(padding).frame(maxWidth: .infinity, alignment: .leading)`
 * over `Color(.secondarySystemGroupedBackground)` clipped to a 20pt continuous
 * corner.
 *
 * Modifier order matters here. In SwiftUI the `.padding` is applied *first*, so
 * the background and clip that follow cover the padding as well — the card spans
 * the full available width and the 16pt inset is inside it. Writing
 * `.padding(16).background(...)` in Compose would instead reserve 16pt of page
 * background on every side and shrink the card by 32pt, so the chain below is
 * deliberately full-width → clip → background → padding.
 */
@Composable
fun Modifier.cardStyle(
    padding: Dp = 16.dp,
    background: Color? = null,
    onClick: (() -> Unit)? = null,
): Modifier {
    val colors = SeuTheme.colors
    val shape = ContinuousRoundedShape(CardCornerRadius)
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(background ?: colors.secondaryGroupedBackground)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(padding)
}

/** Card that lays its children out on one axis with the given spacing. */
@Composable
fun CardColumn(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = 0.dp,
    background: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.cardStyle(padding = padding, background = background, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
    }
}

/** Full-screen container using `Color(.systemGroupedBackground)`. */
@Composable
fun GroupedBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(SeuTheme.colors.groupedBackground)) { content() }
}

/** A page's content padding — SwiftUI's `.padding()` default of 16pt. */
val PagePadding: PaddingValues = PaddingValues(16.dp)

/**
 * The top capsule console used for in-tab section switching.
 *
 * Port of the SwiftUI `ConsoleBar`: a horizontally scrolling row of capsules,
 * the selected one filled with the accent color and shown in semibold. The
 * capsule grows from zero width on selection, standing in for SwiftUI's
 * `matchedGeometryEffect` morph.
 */
@Composable
fun <T> ConsoleBar(
    items: List<T>,
    selection: T,
    onSelect: (T) -> Unit,
    title: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = SeuTheme.colors

    // The background lives on the viewport, not the scrollable content, so the
    // bar still paints full-bleed when the capsules do not fill the width.
    Box(modifier.fillMaxWidth().background(colors.groupedBackground)) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                val selected = item == selection
                val background by animateColorAsState(
                    targetValue = if (selected) colors.accent else colors.secondaryGroupedBackground,
                    animationSpec = tween(250),
                    label = "capsuleFill",
                )
                // 选中态文字不能用纯白：深色模式的 accent 是 #34D6AB 亮绿，
                // 白字压上去对比度只有 1.83:1，基本读不出来（WCAG AA 要求 4.5:1）。
                // 改用 onAccentInverted —— 深色下是 #00382B，对亮绿 7.08:1。
                val content by animateColorAsState(
                    targetValue = if (selected) colors.onAccentInverted else colors.label,
                    animationSpec = tween(250),
                    label = "capsuleLabel",
                )
                Text(
                    text = title(item),
                    style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
                    color = content,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(background)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Section heading with a trailing "查看全部" action.
 *
 * Port of `HomeSectionHeader`: bold title3 on the left, `.subheadline` link on
 * the right, 4pt horizontal inset.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    // @Composable 函数的默认参数在 composable 上下文求值，所以这里可以直接调 stringResource。
    actionLabel: String? = stringResource(R.string.view_all),
    onAction: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = SeuType.Title3,
            color = colors.label,
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = SeuType.Subheadline,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * A hairline separator inset from the leading edge.
 *
 * The iOS code uses `Divider().padding(.leading, 52)` inside grouped lists to
 * align the rule past the leading icon gutter.
 */
@Composable
fun InsetDivider(leading: Dp = 52.dp, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = leading),
        thickness = 0.5.dp,
        color = SeuTheme.colors.separator,
    )
}

/** A 32dp circular tinted icon well — the row leading glyph across the app. */
@Composable
fun IconWell(
    tint: Color,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** Small pill used for match reasons and tags. */
@Composable
fun TintPill(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Text(
        text = text,
        style = SeuType.CaptionMedium,
        color = if (filled) Color.White else tint,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) tint else tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Spacer helper matching SwiftUI's `Spacer(minLength:)`. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
