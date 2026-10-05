package tech.iflink.seuwiki.ui.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.CardCornerRadius
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.toolIconCorner
import tech.iflink.seuwiki.design.toolTintOf
import tech.iflink.seuwiki.models.ToolCatalog
import tech.iflink.seuwiki.models.ToolItem
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.toolGradientColors

/**
 * 工具.
 *
 * Port of `ToolsHomeView`: a two-column grid of shortcut-library cards. The
 * timetable card's subtitle is derived from the same `profile.courses` the Home
 * bento reads, so the two stay in step.
 */
@Composable
fun ToolsScreen(
    profile: UserProfileStore,
    onOpenProfile: () -> Unit,
    onOpenTool: (String) -> Unit,
) {
    val tools = remember { ToolCatalog.tools }

    TabPage {
        Column {
            ScreenHeader(title = stringResource(R.string.tools_title), onProfileClick = onOpenProfile)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = ListBottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tools, key = { it.id }) { tool ->
                    ToolCard(
                        tool = tool,
                        subtitle = liveSubtitle(tool, profile),
                        // 未上线的工具**不可点**：点了进去只有一个空占位页，
                        // 那比点不动更让人困惑。要么真能用，要么就别装成能用。
                        onClick = if (tool.isAvailable) {
                            { onOpenTool(tool.id) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * The timetable tile reads live course data; every other tile keeps its mock
 * subtitle, exactly as `liveSubtitle(for:)` does in the SwiftUI view.
 */
@Composable
private fun liveSubtitle(tool: ToolItem, profile: UserProfileStore): String? {
    if (tool.id != "timetable") return null
    val count = profile.todayCourseCount()
    return if (count > 0) {
        stringResource(R.string.tools_today_classes, count)
    } else {
        stringResource(R.string.tools_today_no_class)
    }
}

/**
 * 「快捷指令」library card.
 *
 * The whole tile is a saturated gradient — 8% toward white at the top leading
 * corner, 10% toward black at the bottom trailing — with a white glyph top-left
 * and the name bottom-left. Fixed at 118dp tall in both orientations, and
 * identical in light and dark, as in the SwiftUI source.
 */
@Composable
fun ToolCard(
    tool: ToolItem,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    val tint = toolTintOf(tool.tintKey, colors)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // ToolCardButtonStyle: 0.96 scale + 0.88 opacity while held.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "toolPress",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "toolPressAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            // heightIn 而不是固定 height：系统字号放大到 200% 时三行文字
            // 会被硬切掉，heightIn 让它自己长高。
            .heightIn(min = 118.dp)
            .scale(scale)
            .clip(ContinuousRoundedShape(CardCornerRadius))
            .background(Brush.linearGradient(toolGradientColors(tint)))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick?.invoke() },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Icon(
                imageVector = SeuIcons.of(tool.iconKey),
                contentDescription = null,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(tool.nameRes),
                style = SeuType.HeadlineBold,
                color = Color.White.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 没接通的工具必须说清楚还没上线，不能拿「借阅与研讨间」这种
                // 读起来完全可用的副标题糊弄过去 —— 点进去只有一个占位页。
                text = if (!tool.isAvailable) {
                    stringResource(R.string.tools_coming_soon)
                } else {
                    subtitle ?: stringResource(tool.subtitleRes)
                },
                style = SeuType.Caption,
                color = Color.White.copy(alpha = 0.7f * alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 未上线的工具压一层遮罩并明确标注，且不可点：
        // 让人一眼看出哪些能用、哪些不能，别点进去才发现是空页。
        if (!tool.isAvailable) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
            Text(
                text = stringResource(R.string.tools_coming_soon),
                style = SeuType.Caption2Medium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The tool glyph in its own gradient tile — used by the tool detail placeholder,
 * which shows the same icon treatment as the grid at a larger size.
 */
@Composable
fun ToolIconSquare(tool: ToolItem, size: Dp = 44.dp) {
    val colors = SeuTheme.colors
    val tint = toolTintOf(tool.tintKey, colors)
    Box(
        modifier = Modifier
            .size(size)
            .clip(ContinuousRoundedShape(toolIconCorner(size)))
            .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SeuIcons.of(tool.iconKey),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}
