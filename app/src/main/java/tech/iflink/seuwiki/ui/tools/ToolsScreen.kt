package tech.iflink.seuwiki.ui.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
            ScreenHeader(title = "工具", onProfileClick = onOpenProfile)
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
                        onClick = { onOpenTool(tool.id) },
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
private fun liveSubtitle(tool: ToolItem, profile: UserProfileStore): String? {
    if (tool.id != "timetable") return null
    val count = profile.todayCourseCount()
    return if (count > 0) "今日 $count 节课" else "今日无课"
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
            .height(118.dp)
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
                text = tool.name,
                style = SeuType.HeadlineBold,
                color = Color.White.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle ?: tool.subtitle,
                style = SeuType.Caption,
                color = Color.White.copy(alpha = 0.7f * alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
