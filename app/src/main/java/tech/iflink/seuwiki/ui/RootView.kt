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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
 * The bar is hidden on pushed screens, which is the Android equivalent of iOS
 * replacing the tab bar with a back button once you navigate deeper.
 */
@Composable
fun RootView() {
    val context = LocalContext.current
    val profile = remember { UserProfileStore(context.applicationContext) }
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoot = AppTab.entries.any { it.route == currentRoute }

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

        if (isTabRoot) {
            FloatingTabBar(
                selected = AppTab.entries.firstOrNull { it.route == currentRoute } ?: AppTab.Home,
                onSelect = { navController.switchTab(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
    val border = if (colors.isDark) Color(0x1FFFFFFF) else Color(0x1F000000)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(50))
                .background(surface)
                .border(1.dp, border, RoundedCornerShape(50)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SeuTheme.colors
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.secondaryLabel,
        animationSpec = tween(220),
        label = "tabTint",
    )
    val indicator by animateDpAsState(
        targetValue = if (selected) 40.dp else 0.dp,
        animationSpec = tween(220),
        label = "tabIndicator",
    )
    val indicatorAlpha by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(220),
        label = "tabIndicatorAlpha",
    )

    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = indicator.coerceAtLeast(38.dp), height = 30.dp)
                .clip(CircleShape)
                .background(indicatorAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = tab.label,
            style = SeuType.Caption,
            color = tint,
        )
    }
}
