package tech.iflink.seuwiki.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tech.iflink.seuwiki.data.FeedStore
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.ui.detail.FeedItemDetailScreen
import tech.iflink.seuwiki.ui.detail.ForumPostDetailScreen
import tech.iflink.seuwiki.ui.detail.HomeFeedListScreen
import tech.iflink.seuwiki.ui.detail.HomeForumListScreen
import tech.iflink.seuwiki.ui.detail.HandbookEntryScreen
import tech.iflink.seuwiki.ui.detail.HandbookSectionScreen
import tech.iflink.seuwiki.ui.detail.SearchSourceListScreen
import tech.iflink.seuwiki.ui.detail.TopicDetailScreen
import tech.iflink.seuwiki.ui.experience.ExperienceScreen
import tech.iflink.seuwiki.ui.feed.FeedScreen
import tech.iflink.seuwiki.ui.home.HomeScreen
import tech.iflink.seuwiki.ui.profile.ProfileScreen
import tech.iflink.seuwiki.ui.search.SearchScreen
import tech.iflink.seuwiki.ui.tools.GPACalculatorScreen
import tech.iflink.seuwiki.ui.tools.TimetableScreen
import tech.iflink.seuwiki.ui.tools.ToolPlaceholderScreen
import tech.iflink.seuwiki.ui.tools.ToolsScreen

/** The five tabs, in the order the SwiftUI `TabView` declares them. */
enum class AppTab(
    val label: String,
    val icon: ImageVector,
    val route: String,
) {
    Home("主页", SeuIcons.Home, "home"),
    Feed("资讯", SeuIcons.Feed, "feed"),
    Experience("经验", SeuIcons.Experience, "experience"),
    Tools("工具", SeuIcons.Tools, "tools"),
    Search("搜索", SeuIcons.Search, "search");
}

/** Route templates. The five tab roots double as `AppTab.route`. */
object Routes {
    const val PROFILE = "profile"
    const val HOME_FEED_LIST = "home/feed-list"
    const val HOME_FORUM_LIST = "home/forum-list"
    const val FEED_DETAIL = "feed/detail/{id}"
    const val FORUM_DETAIL = "forum/detail/{id}"
    const val TOPIC_DETAIL = "forum/topic/{slug}"
    const val HANDBOOK_SECTION = "handbook/section/{id}"
    const val HANDBOOK_ENTRY = "handbook/entry/{id}"
    const val SEARCH_SOURCE_LIST = "search/list/{scope}/{keyword}"
    const val TOOL_TIMETABLE = "tools/timetable"
    const val TOOL_GPA = "tools/gpa"
    const val TOOL_PLACEHOLDER = "tools/other/{id}"

    fun feedDetail(id: String) = "feed/detail/$id"
    fun forumDetail(id: String) = "forum/detail/$id"
    fun topicDetail(slug: String) = "forum/topic/$slug"
    fun handbookSection(id: String) = "handbook/section/$id"
    fun handbookEntry(id: String) = "handbook/entry/$id"
    fun searchSourceList(scope: String, keyword: String) =
        "search/list/${java.net.URLEncoder.encode(scope, "UTF-8")}/" +
            java.net.URLEncoder.encode(keyword, "UTF-8")

    fun toolRoute(id: String): String = when (id) {
        "timetable" -> TOOL_TIMETABLE
        "gpa" -> TOOL_GPA
        else -> "tools/other/$id"
    }
}

/**
 * App shell.
 *
 * The SwiftUI root is a `TabView`; here a `NavHost` plays that role so each tab
 * keeps its own back stack, and the tab bar sits on top as a floating capsule.
 *
 * Like `TabView`, the bar stays on screen for screens pushed *inside* a tab — a
 * feed detail, a topic, the timetable — and the active item follows whichever
 * tab's stack you are on rather than disappearing, so `switchTab` still works
 * from a detail page.
 */
@Composable
fun RootView() {
    val context = LocalContext.current
    val profile = remember { UserProfileStore(context.applicationContext) }
    // 资讯侧接 seu.wiki 线上接口；论坛/手册/工具/个人页按 iOS 现状仍是本地数据。
    val feedStore = remember { FeedStore() }
    val navController = rememberNavController()

    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val owningTab = remember(backStack) {
        backStack.asReversed()
            .firstNotNullOfOrNull { entry ->
                AppTab.entries.firstOrNull { it.route == entry.destination.route }
            } ?: AppTab.Home
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SeuTheme.colors.groupedBackground),
    ) {
        NavHost(
            navController = navController,
            startDestination = AppTab.Home.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            // --- tab roots ---
            composable(AppTab.Home.route) {
                HomeScreen(
                    profile = profile,
                    feedStore = feedStore,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenFeedList = { navController.navigate(Routes.HOME_FEED_LIST) },
                    onOpenForumList = { navController.navigate(Routes.HOME_FORUM_LIST) },
                    onOpenFeed = { navController.navigate(Routes.feedDetail(it)) },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                )
            }
            composable(AppTab.Feed.route) {
                FeedScreen(
                    profile = profile,
                    store = feedStore,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenItem = { navController.navigate(Routes.feedDetail(it)) },
                )
            }
            composable(AppTab.Experience.route) {
                ExperienceScreen(
                    profile = profile,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                    onOpenTopic = { navController.navigate(Routes.topicDetail(it)) },
                    onOpenHandbookSection = { navController.navigate(Routes.handbookSection(it)) },
                )
            }
            composable(AppTab.Tools.route) {
                ToolsScreen(
                    profile = profile,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenTool = { navController.navigate(Routes.toolRoute(it)) },
                )
            }
            composable(AppTab.Search.route) {
                SearchScreen(
                    feedStore = feedStore,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenFeed = { navController.navigate(Routes.feedDetail(it)) },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                    onOpenHandbookEntry = { navController.navigate(Routes.handbookEntry(it)) },
                    onOpenSourceList = { scope, keyword ->
                        navController.navigate(Routes.searchSourceList(scope.label, keyword))
                    },
                )
            }

            // --- pushed screens ---
            composable(Routes.PROFILE) {
                ProfileScreen(profile = profile, onBack = { navController.popBackStack() })
            }
            composable(Routes.HOME_FEED_LIST) {
                HomeFeedListScreen(
                    items = MockData.feedItems.filter { it.isSelected },
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigate(Routes.feedDetail(it)) },
                )
            }
            composable(Routes.HOME_FORUM_LIST) {
                HomeForumListScreen(
                    posts = MockData.forumPosts,
                    onBack = { navController.popBackStack() },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                )
            }
            composable(
                route = Routes.FEED_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                FeedItemDetailScreen(
                    profile = profile,
                    store = feedStore,
                    itemId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.FORUM_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ForumPostDetailScreen(
                    profile = profile,
                    postId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.TOPIC_DETAIL,
                arguments = listOf(navArgument("slug") { type = NavType.StringType }),
            ) { entry ->
                TopicDetailScreen(
                    profile = profile,
                    topicSlug = entry.arguments?.getString("slug").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                )
            }
            composable(
                route = Routes.HANDBOOK_SECTION,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                HandbookSectionScreen(
                    sectionId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                )
            }
            composable(
                route = Routes.HANDBOOK_ENTRY,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                HandbookEntryScreen(
                    entryId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.SEARCH_SOURCE_LIST,
                arguments = listOf(
                    navArgument("scope") { type = NavType.StringType },
                    navArgument("keyword") { type = NavType.StringType },
                ),
            ) { entry ->
                SearchSourceListScreen(
                    scope = entry.arguments?.getString("scope").orEmpty(),
                    keyword = entry.arguments?.getString("keyword").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenFeed = { navController.navigate(Routes.feedDetail(it)) },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                )
            }
            composable(Routes.TOOL_TIMETABLE) {
                TimetableScreen(profile = profile, onBack = { navController.popBackStack() })
            }
            composable(Routes.TOOL_GPA) {
                GPACalculatorScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.TOOL_PLACEHOLDER,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ToolPlaceholderScreen(
                    toolId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }

        FloatingTabBar(
            selected = owningTab,
            onSelect = { navController.switchTab(it) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Switches tabs while preserving each tab's own back stack, the behaviour
 * `TabView` gets for free and a plain `navigate` would throw away.
 */
private fun NavHostController.switchTab(tab: AppTab) {
    navigate(tab.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun FloatingTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SeuTheme.colors
    // Translucent so content scrolling underneath reads as "behind glass",
    // matching the iOS tab bar's material treatment.
    val surface = if (colors.isDark) Color(0xF21C1C1E) else Color(0xF2FFFFFF)
    val border = if (colors.isDark) Color(0x1FFFFFFF) else Color(0x14000000)
    val shadow = if (colors.isDark) Color(0x40000000) else Color(0x1F3C3C43)
    val shape = RoundedCornerShape(TabBarHeight / 2)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TabBarHeight)
                .shadow(8.dp, shape, ambientColor = shadow, spotColor = shadow)
                .clip(shape)
                .background(surface)
                .border(1.dp, border, shape),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** The floating capsule reads ~58pt tall on every iPhone; 58dp matches it. */
private val TabBarHeight = 58.dp

/**
 * One tab.
 *
 * The SwiftUI root never draws this itself — it hands the labels to the system
 * `TabView`, and iOS 26 renders a glass capsule with a full-height rounded fill
 * behind the active item, covering icon *and* caption. Measured off the
 * simulator: the pill is 63pt tall (the whole bar) and ~76pt wide for a
 * two-character label, filled with a neutral 15% black rather than a tinted
 * accent, and inactive items stay in the primary label colour instead of grey.
 */
@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SeuTheme.colors
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.label,
        animationSpec = tween(220),
        label = "tabTint",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) {
            if (colors.isDark) Color(0x24FFFFFF) else Color(0x26000000)
        } else {
            Color.Transparent
        },
        animationSpec = tween(220),
        label = "tabFill",
    )
    // The pill widens around the label when selected, which is what sells the
    // "the glass moves between tabs" read of the iOS bar.
    val inset by animateDpAsState(
        targetValue = if (selected) 16.dp else 9.dp,
        animationSpec = tween(220),
        label = "tabInset",
    )

    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        // The pill runs the full height of the bar rather than hugging the two
        // lines of content — that tall shape is what makes the iOS bar read as
        // one sliding pill instead of five separate highlights.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(fill)
                .padding(horizontal = inset),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tab.label,
                    style = SeuType.TabLabel,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}
