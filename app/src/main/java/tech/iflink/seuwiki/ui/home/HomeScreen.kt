package tech.iflink.seuwiki.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SectionHeader
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.Course
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.FeedRow
import tech.iflink.seuwiki.ui.rows.ForumRow

/**
 * 主页.
 *
 * Port of `HomeView`: a two-up bento row (reminder countdown + next course),
 * then the 「与我有关的通知」 and 「论坛新帖」 sections, each capped at three rows
 * behind a header that opens the full list.
 *
 * The feed section reads the for-you page and falls back to the mock selection,
 * which is what the SwiftUI `feedItems` computed property does while the store
 * has not loaded.
 */
@Composable
fun HomeScreen(
    profile: UserProfileStore,
    onOpenProfile: () -> Unit,
    onOpenFeedList: () -> Unit,
    onOpenForumList: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
) {
    // The live store supplies these once `/api/site/for-you` is wired in; until
    // then this is the same fallback the SwiftUI view uses.
    val feedItems = remember { MockData.feedItems.filter { it.isSelected } }
    val forumPosts = remember { MockData.forumPosts.take(3) }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "主页", onProfileClick = onOpenProfile)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = ListBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "bento") { BentoRow(profile) }
                item(key = "feed") {
                    FeedSection(feedItems, onOpenFeedList, onOpenFeed)
                }
                item(key = "forum") {
                    ForumSection(forumPosts, onOpenForumList, onOpenPost)
                }
            }
        }
    }
}

/**
 * The two bento tiles.
 *
 * `IntrinsicSize.Min` on the row reproduces the SwiftUI
 * `.fixedSize(horizontal: false, vertical: true)` on the `HStack`, which makes
 * both cards exactly as tall as the taller one.
 */
@Composable
private fun BentoRow(profile: UserProfileStore) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReminderCard(
            reminder = profile.nextReminder,
            modifier = Modifier.weight(1f),
        )
        NextCourseCard(
            course = profile.nextCourse,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Bento · 提醒 — the soonest deadline, counting down in days. */
@Composable
private fun ReminderCard(reminder: CampusReminder?, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier.cardStyle(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardLabel(text = "提醒", symbol = "bell.fill", tint = colors.orange)
        if (reminder != null) {
            val days = reminder.daysRemaining
            // `.contentTransition(.numericText())` — the value rolls instead of
            // snapping when the countdown crosses a day.
            AnimatedContent(
                targetState = if (days > 0) "$days 天" else "今天",
                transitionSpec = {
                    (slideInVertically(tween(300)) { it } + fadeIn(tween(300)))
                        .togetherWith(slideOutVertically(tween(300)) { -it } + fadeOut(tween(200)))
                },
                label = "countdown",
            ) { text ->
                Text(
                    text = text,
                    style = SeuType.CountdownLarge,
                    color = if (days <= 2) colors.red else colors.label,
                )
            }
            Text(
                text = reminder.title,
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("暂无提醒", style = SeuType.Title3, color = colors.label)
            Text(
                text = "在资讯详情页可设定提醒",
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** Bento · 下一节课 — the next class still to be attended today. */
@Composable
private fun NextCourseCard(course: Course?, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier.cardStyle(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardLabel(text = "下一节课", symbol = "calendar.day.timeline.left", tint = colors.blue)
        if (course != null) {
            Text(
                text = course.timeRangeText,
                style = SeuType.CountdownMedium,
                color = colors.label,
            )
            Text(
                text = course.name,
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = course.location,
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
        } else {
            Text("今天没课了", style = SeuType.Title3, color = colors.label)
            Text(
                text = "去工具页查看完整课表",
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** The tinted `Label` that heads every bento tile. */
@Composable
private fun CardLabel(text: String, symbol: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = SeuIcons.of(symbol),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Text(text, style = SeuType.SubheadlineSemibold, color = tint)
    }
}

@Composable
private fun FeedSection(
    items: List<FeedItem>,
    onOpenList: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val shown = items.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "与我有关的通知", onAction = onOpenList)
        Column(Modifier.cardStyle(padding = 0.dp)) {
            shown.forEachIndexed { index, item ->
                FeedRow(item = item, onClick = { onOpenItem(item.id) })
                if (index < shown.size - 1) InsetDivider()
            }
        }
    }
}

@Composable
private fun ForumSection(
    posts: List<ForumPost>,
    onOpenList: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "论坛新帖", onAction = onOpenList)
        Column(Modifier.cardStyle(padding = 0.dp)) {
            posts.forEachIndexed { index, post ->
                ForumRow(post = post, onClick = { onOpenPost(post.id) })
                if (index < posts.size - 1) InsetDivider()
            }
        }
    }
}
