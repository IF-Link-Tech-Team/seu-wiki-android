package tech.iflink.seuwiki.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.TintPill
import tech.iflink.seuwiki.design.forumCompactCount
import tech.iflink.seuwiki.design.forumSolidTint
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.ForumAvatar
import tech.iflink.seuwiki.ui.rows.ForumPostCard

/**
 * 经验帖详情.
 *
 * The post body, its engagement counts and tags, a bookmark toggle backed by
 * `profile.bookmarkedPostIds`, and the comment thread. The live build fills the
 * thread from `GET /api/comments`; until seu-forum is reachable this renders an
 * empty thread rather than inventing replies.
 */
@Composable
fun ForumPostDetailScreen(
    profile: UserProfileStore,
    postId: String,
    onBack: () -> Unit,
) {
    val post = remember(postId) { MockData.forumPosts.firstOrNull { it.id == postId } }
    if (post == null) {
        TabPage {
            Column {
                DetailHeader(title = "经验", onBack = onBack)
                EmptyStateView(title = "帖子不存在", description = "这篇帖子可能已被删除")
            }
        }
        return
    }

    val colors = SeuTheme.colors
    val bookmarked = post.id in profile.bookmarkedPostIds

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = "经验",
                onBack = onBack,
                trailing = {
                    Icon(
                        imageVector = SeuIcons.of(if (bookmarked) "star.fill" else "star"),
                        contentDescription = if (bookmarked) "取消收藏" else "收藏",
                        tint = if (bookmarked) colors.orange else colors.accent,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { profile.toggleBookmark(post.id) }
                            .padding(7.dp),
                    )
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (post.isFeatured) TintPill("精选", colors.accent)
                    post.tags.forEach { slug ->
                        TopicCatalog.nameForSlug(slug)?.let { TintPill(it, colors.accent) }
                    }
                }

                Text(post.title, style = SeuType.Title2, color = colors.label)
                Text(
                    text = post.excerpt,
                    style = SeuType.Body,
                    color = colors.label,
                    lineHeight = SeuType.Body.fontSize * 1.35f,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForumAvatar(post.authorName, size = 34.dp)
                    Column {
                        Text(
                            text = post.authorName,
                            style = SeuType.SubheadlineSemibold,
                            color = colors.label,
                        )
                        Text(
                            text = post.authorHeadline,
                            style = SeuType.Caption,
                            color = colors.secondaryLabel,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Format.relative(post.createdAt),
                        style = SeuType.Caption,
                        color = colors.tertiaryLabel,
                    )
                }

                CardColumn(spacing = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        EngagementStat("heart", forumCompactCount(post.likesCount), "点赞")
                        EngagementStat("bubble.right", forumCompactCount(post.commentsCount), "评论")
                        EngagementStat("eye", forumCompactCount(post.viewsCount), "浏览")
                    }
                }
            }
        }
    }
}

@Composable
private fun EngagementStat(symbol: String, value: String, label: String) {
    val colors = SeuTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = SeuIcons.of(symbol),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(15.dp),
            )
            Text(value, style = SeuType.Headline, color = colors.label)
        }
        Text(label, style = SeuType.Caption, color = colors.secondaryLabel)
    }
}

/**
 * 话题详情.
 *
 * Header card with the topic glyph, name, post count and a follow toggle, a
 * horizontal subtag chip row that filters the list (nil = 全部), then that
 * topic's posts ranked by likes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicDetailScreen(
    profile: UserProfileStore,
    topicSlug: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    val topic = remember(topicSlug) { TopicCatalog.topic(topicSlug) }
    if (topic == null) {
        TabPage {
            Column {
                DetailHeader(title = "话题", onBack = onBack)
                EmptyStateView(title = "话题不存在", description = "这个话题可能已下线")
            }
        }
        return
    }

    val colors = SeuTheme.colors
    val tint = forumSolidTint(topic.slug)
    val followed = topic.slug in profile.followedTopicIds
    var selectedSubtag by rememberSaveable(topicSlug) { mutableStateOf<String?>(null) }

    val posts = remember(topicSlug, selectedSubtag) {
        MockData.forumPosts
            .filter { post ->
                post.tags.contains(topic.slug) &&
                    (selectedSubtag == null || post.tags.contains(selectedSubtag))
            }
            .sortedByDescending { it.likesCount }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = topic.name, onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = ListBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CardColumn(spacing = 0.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(ContinuousRoundedShape(16.dp))
                                    .background(tint.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = SeuIcons.of(topic.iconKey),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(topic.name, style = SeuType.Title2, color = colors.label)
                                Text(
                                    text = "${forumCompactCount(topic.postCount)} 篇帖子",
                                    style = SeuType.Subheadline,
                                    color = colors.secondaryLabel,
                                )
                            }
                            Text(
                                text = if (followed) "已关注" else "关注",
                                style = SeuType.SubheadlineSemibold,
                                color = if (followed) colors.secondaryLabel else Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (followed) colors.tertiaryFill else colors.accent,
                                    )
                                    .clickable { profile.toggleFollowTopic(topic.slug) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtagChip("全部", selectedSubtag == null) { selectedSubtag = null }
                        topic.subtags.forEach { subtag ->
                            SubtagChip(subtag.name, selectedSubtag == subtag.slug) {
                                selectedSubtag = subtag.slug
                            }
                        }
                    }
                }
                if (posts.isEmpty()) {
                    item {
                        EmptyStateView(
                            title = "暂无帖子",
                            description = "「${selectedSubtag?.let { TopicCatalog.nameForSlug(it) } ?: topic.name}」下还没有帖子，欢迎来发第一篇。",
                            icon = {
                                Icon(
                                    imageVector = SeuIcons.of("tray"),
                                    contentDescription = null,
                                    tint = colors.tertiaryLabel,
                                    modifier = Modifier.size(40.dp),
                                )
                            },
                            topPadding = 32.dp,
                        )
                    }
                } else {
                    items(posts, key = { it.id }) { post ->
                        ForumPostCard(post = post, onClick = { onOpenPost(post.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Text(
        text = label,
        style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
        color = if (selected) Color.White else colors.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.secondaryGroupedBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
