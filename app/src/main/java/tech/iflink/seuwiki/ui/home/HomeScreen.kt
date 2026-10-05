package tech.iflink.seuwiki.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.data.FeedStore
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.data.toFeedProfile
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SectionHeader
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.Course
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.DocEntry
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.feed.FeedScope
import tech.iflink.seuwiki.ui.rows.FeedRow
import tech.iflink.seuwiki.ui.rows.DocRow
import androidx.compose.ui.platform.LocalDensity

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
    feedStore: FeedStore,
    docs: DocsStore,
    onOpenProfile: () -> Unit,
    onOpenFeedList: () -> Unit,
    onOpenExperienceList: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    // 通知区用「为你精选」的第一页，与 SwiftUI 的 HomeView.feedItems 同源；
    // 该 scope 未加载过时会自动触发一次请求。
    val feedProfile = remember(profile.college, profile.degree, profile.grade, profile.interests) {
        profile.toFeedProfile()
    }
    LaunchedEffect(Unit) { feedStore.loadIfNeeded(FeedScope.ForYou, feedProfile) }
    val feedItems = feedStore.page(FeedScope.ForYou).items

    // 第二块改为真实的经验长文。原来这里渲染 `MockData.forumPosts` ——
    // 虚构作者与虚构互动数，社区接不通就不该拿假帖子占位。
    LaunchedEffect(Unit) { docs.loadExperience() }
    val experience = remember(docs.experience) { docs.experience.take(3) }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.nav_home), onProfileClick = onOpenProfile)
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
                item(key = "experience") {
                    ExperienceSection(experience, onOpenExperienceList, onOpenEntry)
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
 * both cards exactly as tall as the taller one: SwiftUI proposes the tallest
 * child's height to every sibling, and each card's trailing
 * `Spacer(minLength: 0)` swallows the slack. Compose needs `fillMaxHeight()`
 * on each tile (weight only distributes width in a `Row`) plus the same
 * flexible spacer, otherwise the shorter tile stops short and the two bottom
 * edges go out of alignment.
 */
@Composable
private fun BentoRow(profile: UserProfileStore) {
    // 大字号下并排会把「今天没课了」「去工具页查看完整课表」挤成两行细字，
    // 而这两张卡是主页信息量最大的入口。竖排后每张卡拿回整行宽度。
    // 阈值取 1.5×：再大就明显该换行了（UI/UX 方案 §6「大字号下两张卡改为竖排」）。
    val stacked = LocalDensity.current.fontScale >= 1.5f
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReminderCard(reminder = profile.nextReminder, modifier = Modifier.fillMaxWidth())
            NextCourseCard(course = profile.nextCourse, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReminderCard(
            reminder = profile.nextReminder,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        NextCourseCard(
            course = profile.nextCourse,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
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
        CardLabel(text = stringResource(R.string.home_card_reminder), symbol = "bell.fill", tint = colors.orange)
        if (reminder != null) {
            val days = reminder.daysRemaining
            // `.contentTransition(.numericText())` — the value rolls instead of
            // snapping when the countdown crosses a day.
            AnimatedContent(
                targetState = if (days > 0) {
                    stringResource(R.string.days_left, days)
                } else {
                    stringResource(R.string.today)
                },
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
            Text(stringResource(R.string.home_empty_reminder), style = SeuType.Title3, color = colors.label)
            Text(
                text = stringResource(R.string.home_empty_reminder_hint),
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
        }
        // `Spacer(minLength: 0)` — keeps the card flush to the taller sibling.
        Spacer(Modifier.weight(1f))
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
        CardLabel(text = stringResource(R.string.home_card_next_class), symbol = "calendar.day.timeline.left", tint = colors.blue)
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
            Text(stringResource(R.string.home_empty_class), style = SeuType.Title3, color = colors.label)
            Text(
                text = stringResource(R.string.home_empty_class_hint),
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
        }
        // `Spacer(minLength: 0)` — keeps the card flush to the taller sibling.
        Spacer(Modifier.weight(1f))
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
        SectionHeader(title = stringResource(R.string.home_section_related_feed), onAction = onOpenList)
        Column(Modifier.cardStyle(padding = 0.dp)) {
            shown.forEachIndexed { index, item ->
                FeedRow(item = item, onClick = { onOpenItem(item.id) })
                if (index < shown.size - 1) InsetDivider()
            }
        }
    }
}

@Composable
private fun ExperienceSection(
    entries: List<DocEntry>,
    onOpenList: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = stringResource(R.string.home_section_experience), onAction = onOpenList)
        Column(Modifier.cardStyle(padding = 0.dp)) {
            entries.forEachIndexed { index, entry ->
                DocRow(entry = entry, onClick = { onOpenEntry(entry.slug) })
                if (index < entries.size - 1) InsetDivider()
            }
        }
    }
}
