package tech.iflink.seuwiki.ui.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.ForumFeedTab
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.HandbookSection
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.ForumPostCard
import tech.iflink.seuwiki.ui.rows.HandbookNodeCell
import tech.iflink.seuwiki.ui.rows.TopicCard

/**
 * 经验.
 *
 * Port of `ExperienceHomeView`: the console and a swipeable pager bound both
 * ways. Tapping a capsule animates the pager; swiping settles the selection and
 * the console follows.
 */
@Composable
fun ExperienceScreen(
    profile: UserProfileStore,
    onOpenPost: (String) -> Unit,
    onOpenTopic: (String) -> Unit,
    onOpenHandbookSection: (String) -> Unit,
) {
    val tabs = ForumFeedTab.all
    var tabLabel by rememberSaveable { mutableStateOf(ForumFeedTab.Hot.label) }
    val tab = tabs.firstOrNull { it.label == tabLabel } ?: ForumFeedTab.Hot
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // Console → pager.
    LaunchedEffect(tab) {
        val index = tabs.indexOf(tab)
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }
    // Pager → console.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            tabs.getOrNull(page)?.let { tabLabel = it.label }
        }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "经验")
            ConsoleBar(
                items = tabs,
                selection = tab,
                onSelect = {
                    tabLabel = it.label
                    scope.launch { pagerState.animateScrollToPage(tabs.indexOf(it)) }
                },
                title = { it.label },
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (tabs[page]) {
                    ForumFeedTab.Hot -> HotFeed(onOpenPost)
                    ForumFeedTab.Following -> FollowingFeed(profile, onOpenPost, onBrowseTopics = {
                        tabLabel = ForumFeedTab.Topics.label
                    })
                    ForumFeedTab.Topics -> TopicsSquare(onOpenTopic)
                    ForumFeedTab.Handbook -> HandbookHome(onOpenHandbookSection)
                }
            }
        }
    }
}

/** 热门 — every post, ordered by likes. */
@Composable
private fun HotFeed(onOpenPost: (String) -> Unit) {
    val posts = remember { MockData.forumPosts.sortedByDescending { it.likesCount } }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(posts, key = { it.id }) { post ->
            ForumPostCard(post = post, onClick = { onOpenPost(post.id) })
        }
    }
}

/** 关注 — newest posts from the topics the user follows, with an empty-state CTA. */
@Composable
private fun FollowingFeed(
    profile: UserProfileStore,
    onOpenPost: (String) -> Unit,
    onBrowseTopics: () -> Unit,
) {
    val posts = remember(profile.followedTopicIds) {
        MockData.forumPosts
            .filter { post -> post.tags.any { it in profile.followedTopicIds } }
            .sortedByDescending { it.createdAt ?: 0L }
    }
    if (posts.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyStateView(
                title = "还没有关注的话题",
                description = "去话题广场关注感兴趣的话题，相关新帖会出现在这里。",
                icon = {
                    Icon(
                        imageVector = SeuIcons.of("star"),
                        contentDescription = null,
                        tint = SeuTheme.colors.tertiaryLabel,
                        modifier = Modifier.size(40.dp),
                    )
                },
                topPadding = 64.dp,
            )
            Spacer(Modifier.height(16.dp))
            // `.borderedProminent` in the ContentUnavailableView actions block.
            Text(
                text = "浏览话题广场",
                style = SeuType.SubheadlineSemibold,
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SeuTheme.colors.accent)
                    .clickable(onClick = onBrowseTopics)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(posts, key = { it.id }) { post ->
                ForumPostCard(post = post, onClick = { onOpenPost(post.id) })
            }
        }
    }
}

/** 话题 — the 8-topic square, Apple Podcasts category style. */
@Composable
private fun TopicsSquare(onOpenTopic: (String) -> Unit) {
    val topics = remember { TopicCatalog.topics }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(topics, key = { it.slug }) { topic ->
            TopicCard(topic = topic, onClick = { onOpenTopic(topic.slug) })
        }
    }
}

/**
 * 生存手册 — node navigation, two categories per row inside one card, matching
 * `HandbookHomeView`'s `stride(from:to:by: 2)` pairing.
 */
@Composable
private fun HandbookHome(onOpenSection: (String) -> Unit) {
    val sections = remember { MockData.handbookSections }
    val pairs = remember(sections) { sections.chunked(2) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
    ) {
        item {
            Column(Modifier.cardStyle(padding = 0.dp)) {
                pairs.forEachIndexed { rowIndex, pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { section ->
                            HandbookCell(section, onOpenSection, Modifier.weight(1f))
                        }
                        // An odd final pair still needs a filler so the lone cell
                        // keeps the half-width it has on every other row.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    if (rowIndex < pairs.lastIndex) HorizontalHairline()
                }
            }
        }
    }
}

@Composable
private fun HandbookCell(
    section: HandbookSection,
    onOpenSection: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        HandbookNodeCell(
            section = section,
            showsDivider = false,
            onClick = { onOpenSection(section.id) },
        )
    }
}

@Composable
private fun HorizontalHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = 12.dp)
            .background(SeuTheme.colors.separator),
    )
}
