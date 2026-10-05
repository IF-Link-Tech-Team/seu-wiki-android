package tech.iflink.seuwiki.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.iflink.seuwiki.design.CardCornerRadius
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.TintPill
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.design.forumCompactCount
import tech.iflink.seuwiki.design.forumSolidTint
import tech.iflink.seuwiki.design.forumTint
import tech.iflink.seuwiki.design.mixWith
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumTopic
import tech.iflink.seuwiki.models.HandbookSection
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.Format

/** Click affordance applied only when a handler is supplied. */
private fun Modifier.maybeClick(onClick: (() -> Unit)?): Modifier =
    if (onClick != null) this.clickable(onClick = onClick) else this

/**
 * Home · 与我有关的通知 row.
 *
 * Port of the private `HomeFeedRow`: a 32dp accent icon well, a two-line title,
 * and a caption meta line that surfaces the first match reason in accent colour.
 */
@Composable
fun FeedRow(
    item: FeedItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .maybeClick(onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(tint = colors.accent) {
            Icon(
                imageVector = SeuIcons.of(item.category.iconKey),
                contentDescription = item.category.label,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title,
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // SwiftUI lets the leading texts compress to zero width and truncate
            // while the relative timestamp keeps its intrinsic size, so the
            // shrinkable half is nested in a weighted row and the stamp is not.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.sourceName,
                        style = SeuType.Caption,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val reason = item.matchReasons.firstOrNull()
                    if (reason != null) {
                        Text(
                            text = "·",
                            style = SeuType.Caption,
                            color = colors.secondaryLabel,
                            maxLines = 1,
                        )
                        Text(
                            text = reason,
                            style = SeuType.Caption,
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Text(
                    text = Format.relative(item.publishedAt),
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Home · 论坛新帖 row — same shape as [FeedRow] with the forum's orange well. */
@Composable
fun ForumRow(
    post: ForumPost,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .maybeClick(onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(tint = colors.orange) {
            Icon(
                imageVector = SeuIcons.of("bubble.left.and.text.bubble.right.fill"),
                contentDescription = null,
                tint = colors.orange,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = post.title,
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = post.authorName,
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("·", style = SeuType.Caption, color = colors.secondaryLabel, maxLines = 1)
                StatLabel(symbol = "heart", text = Format.count(post.likesCount))
                StatLabel(symbol = "bubble.right", text = post.commentsCount.toString())
            }
        }
    }
}

/** A `Label` from the SwiftUI metadata line: glyph + number, both secondary. */
@Composable
private fun StatLabel(symbol: String, text: String, tint: Color? = null) {
    val colors = SeuTheme.colors
    val color = tint ?: colors.secondaryLabel
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = SeuIcons.of(symbol),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(11.dp),
        )
        Text(text, style = SeuType.Caption, color = color)
    }
}

/**
 * 资讯 · 为你精选 card.
 *
 * Port of the private `ForYouCard`: caption source line with the category well
 * and an optional 精选 star, a two-line headline, a two-line summary, and accent
 * chips for the match reasons.
 */
@Composable
fun ForYouCard(
    item: FeedItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier
            .cardStyle(onClick = onClick)
            .maybeClick(onClick),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconWell(tint = colors.accent, size = 28.dp) {
                Icon(
                    imageVector = SeuIcons.of(item.category.iconKey),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = item.sourceName,
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text("·", style = SeuType.Caption, color = colors.secondaryLabel, maxLines = 1)
            Text(
                text = Format.relative(item.publishedAt),
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (item.isSelected) {
                StatLabel(symbol = "star.fill", text = "精选", tint = colors.orange)
            }
        }

        Text(
            text = item.title,
            style = SeuType.Headline,
            color = colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.summary,
            style = SeuType.Subheadline,
            color = colors.secondaryLabel,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.matchReasons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.matchReasons.forEach { reason ->
                    TintPill(reason, colors.accent)
                }
            }
        }
    }
}

/** 资讯 · 全部 / 分类 list card — the denser sibling of [ForYouCard]. */
@Composable
fun FeedItemCard(
    item: FeedItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .cardStyle(padding = 14.dp, onClick = onClick)
            .maybeClick(onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(tint = colors.accent) {
            Icon(
                imageVector = SeuIcons.of(item.category.iconKey),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.title,
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary,
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(item.sourceName, style = SeuType.Caption, color = colors.secondaryLabel)
                Text("·", style = SeuType.Caption, color = colors.secondaryLabel)
                Text(
                    text = Format.relative(item.publishedAt),
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                )
                if (item.isSelected) {
                    Text("·", style = SeuType.Caption, color = colors.secondaryLabel)
                    StatLabel(symbol = "star.fill", text = "精选", tint = colors.orange)
                }
            }
        }
    }
}

/** Circular initials avatar, coloured deterministically by author name. */
@Composable
fun ForumAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val tint = forumTint(name)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = SeuType.CaptionMedium.copy(fontSize = (size.value * 0.44f).sp),
            color = tint,
        )
    }
}

/** 经验 · post card — the full treatment used by 热门 / 关注 / 话题. */
@Composable
fun ForumPostCard(
    post: ForumPost,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier
            .cardStyle(onClick = onClick)
            .maybeClick(onClick),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (post.isFeatured) {
                TintPill("精选", colors.accent)
            }
            val topicName = post.tags.firstNotNullOfOrNull { TopicCatalog.nameForSlug(it) }
            if (topicName != null) {
                Text(
                    text = topicName,
                    style = SeuType.CaptionMedium,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = Format.relative(post.createdAt),
                style = SeuType.Caption,
                color = colors.tertiaryLabel,
                maxLines = 1,
            )
        }

        Text(
            text = post.title,
            style = SeuType.Headline,
            color = colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = post.excerpt,
            style = SeuType.Subheadline,
            color = colors.secondaryLabel,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ForumAvatar(post.authorName, size = 22.dp)
            Text(
                text = post.authorName,
                style = SeuType.CaptionMedium,
                color = colors.label,
            )
            Text("·", style = SeuType.Caption, color = colors.tertiaryLabel)
            Text(
                text = post.authorHeadline,
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatLabel("heart", forumCompactCount(post.likesCount))
            StatLabel("bubble.right", forumCompactCount(post.commentsCount))
            StatLabel("eye", forumCompactCount(post.viewsCount))
        }
    }
}

/**
 * 经验 · 话题广场 card.
 *
 * The Apple Podcasts category treatment: a saturated fill, an oversized
 * translucent glyph bleeding off the trailing edge, and the topic name anchored
 * to the leading bottom corner.
 */
@Composable
fun TopicCard(
    topic: ForumTopic,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp)
            .clip(ContinuousRoundedShape(16.dp))
            .background(forumSolidTint(topic.slug))
            .maybeClick(onClick),
    ) {
        Icon(
            imageVector = SeuIcons.of(topic.iconKey),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.25f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 16.dp, y = 6.dp)
                .size(56.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "${forumCompactCount(topic.postCount)} 篇帖子",
                style = SeuType.Caption2Medium,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                text = topic.name,
                style = SeuType.Headline,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 经验 · 生存手册 node row.
 *
 * Port of `HandbookNodeCell`: a 36dp solid-tint circle glyph, the section name
 * and its entry count, with a hairline inset past the icon gutter.
 */
@Composable
fun HandbookNodeCell(
    section: HandbookSection,
    showsDivider: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    val tint = forumSolidTint(section.id)
    Column(modifier = modifier.fillMaxWidth().maybeClick(onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SeuIcons.of(section.iconKey),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.name,
                    style = SeuType.SubheadlineMedium,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${section.entries.size} 篇条目",
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                )
            }
        }
        if (showsDivider) InsetDivider(leading = 58.dp)
    }
}

/** The `wifi.slash` offline notice shown above mock fallback content. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedShape(CardCornerRadius))
            .background(colors.tertiaryFill)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = SeuIcons.of("wifi.slash"),
            contentDescription = null,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(5.dp))
        Text(
            text = "暂时无法连接服务器，显示离线示例内容",
            style = SeuType.Caption,
            color = colors.secondaryLabel,
        )
    }
}

/** Saturated two-stop gradient used by Tools cards (white-mixed top, black-mixed bottom). */
fun toolGradientColors(tint: Color): List<Color> =
    listOf(tint.mixWith(Color.White, 0.08f), tint.mixWith(Color.Black, 0.1f))
