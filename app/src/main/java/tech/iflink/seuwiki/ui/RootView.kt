package tech.iflink.seuwiki.ui

import androidx.annotation.StringRes
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.ReminderReceiver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tech.iflink.seuwiki.data.AuthStore
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.data.FeedStore
import tech.iflink.seuwiki.data.ForumStore
import tech.iflink.seuwiki.data.launchSignIn
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.ui.detail.FeedItemDetailScreen
import tech.iflink.seuwiki.ui.detail.HomeFeedListScreen
import tech.iflink.seuwiki.ui.detail.HomeExperienceListScreen
import tech.iflink.seuwiki.ui.detail.DocEntryDetailScreen
import tech.iflink.seuwiki.ui.detail.HandbookPartScreen
import tech.iflink.seuwiki.ui.detail.SearchSourceListScreen
import tech.iflink.seuwiki.ui.experience.ExperienceScreen
import tech.iflink.seuwiki.ui.forum.ForumBookmarksScreen
import tech.iflink.seuwiki.ui.forum.ForumComposeScreen
import tech.iflink.seuwiki.ui.forum.ForumPostDetailScreen
import tech.iflink.seuwiki.ui.forum.ForumPostListScreen
import tech.iflink.seuwiki.ui.feed.FeedScope
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
    @get:StringRes
    val labelRes: Int,
    val icon: ImageVector,
    val route: String,
) {
    Home(R.string.nav_home, SeuIcons.Home, "home"),
    Feed(R.string.nav_feed, SeuIcons.Feed, "feed"),
    Experience(R.string.nav_experience, SeuIcons.Experience, "experience"),
    Tools(R.string.nav_tools, SeuIcons.Tools, "tools"),
    Search(R.string.nav_search, SeuIcons.Search, "search");
}

/**
 * 路由模板。五个一级页面同时作为 [AppTab.route]。
 *
 * 所有**动态路径段**（id / slug / 关键词）都必须经 [seg] 编码后拼进来，
 * 不能直接插值。原来的写法有两个真实故障：
 *
 * 1. id 直接插值，而 id 里可能出现 `?`、`#`、`/`。`?` 之后的内容会被当成 query、
 *    `#` 之后根本到不了 Navigation，结果是路由匹配失败甚至抛异常。
 * 2. 关键词用 `URLEncoder.encode` —— 它是 **form 编码**，空格会编成 `+`。
 *    而 path 段里 `+` 是字面的加号，Navigation 也不会把它还原成空格，
 *    于是搜「machine learning」点查看更多会变成搜「machine+learning」，永远搜不到。
 *
 * **斜杠必须编码成 `%2F`**，这是这里最关键的一点：手册 slug 形如
 * `survival/观点篇/1-认识`，本身含斜杠。若把 `/` 原样留在路由里，
 * 它会被当成**路径分隔符**，一个 `{slug}` 参数匹配不到多段路径，
 * `navController.navigate` 直接抛 `IllegalArgumentException` 把 App 带走 ——
 * 这正是实测点搜索结果就崩的原因。编码后整条 slug 只占一个 path 段，
 * 读取时用 [Routes.decode] 还原。
 */
object Routes {
    const val PROFILE = "profile"
    const val HOME_FEED_LIST = "home/feed-list"
    const val HOME_FORUM_LIST = "home/forum-list"
    const val FEED_DETAIL = "feed/detail/{id}"
    const val FORUM_DETAIL = "forum/detail/{id}"
    const val TOPIC_DETAIL = "forum/topic/{slug}"
    const val HANDBOOK_SECTION = "handbook/section/{id}"
    const val HANDBOOK_ENTRY = "handbook/entry/{slug}"
    const val SEARCH_SOURCE_LIST = "search/list/{scope}/{keyword}"
    const val TOOL_TIMETABLE = "tools/timetable"
    const val TOOL_GPA = "tools/gpa"
    const val TOOL_PLACEHOLDER = "tools/other/{id}"
    const val FORUM_COMPOSE = "forum/compose"
    const val FORUM_BOOKMARKS = "forum/bookmarks"

    /**
     * 编码**单个**路径段。
     *
     * 只保留 RFC 3986 的 unreserved 字符（`A-Za-z0-9-._~`）与 `:`
     * （允许出现在 path 段中）。**斜杠、`+`、空格全部编码**：
     * - `+` → `%2B`：form 编码会把空格变成 `+`，但在 path 段里 `+` 是字面加号；
     * - 空格 → `%20`；
     * - `/` → `%2F`：否则会被当成路径分隔符，路由匹配不上。
     */
    fun seg(value: String): String = buildString(value.length + 8) {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~' || c == ':'
            ) {
                append(c)
            } else {
                append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
    }

    /** [seg] 的逆运算。Navigation 是否已解码并不保证，这里统一再解一次。 */
    fun decode(value: String?): String =
        runCatching { Uri.decode(value.orEmpty()) }.getOrElse { value.orEmpty() }

    fun feedDetail(id: String) = "feed/detail/${seg(id)}"
    fun forumDetail(id: String) = "forum/detail/${seg(id)}"
    fun topicDetail(slug: String) = "forum/topic/${seg(slug)}"
    fun handbookSection(id: String) = "handbook/section/${seg(id)}"
    fun handbookEntry(slug: String) = "handbook/entry/${seg(slug)}"
    fun searchSourceList(scope: String, keyword: String) =
        "search/list/${seg(scope)}/${seg(keyword)}"

    fun toolRoute(id: String): String = when (id) {
        "timetable" -> TOOL_TIMETABLE
        "gpa" -> TOOL_GPA
        else -> "tools/other/${seg(id)}"
    }
}

/**
 * App shell.
 *
 * The SwiftUI root is a `TabView`; here a `NavHost` plays that role so each tab
 * keeps its own back stack, and [BottomTabBar] sits on top of it.
 *
 * **显隐规则与 iOS 相反，这是有意的机制层差异**（UI/UX 对齐方案 v2 §3.1）：
 * 安卓有系统返回链路，子页面里切 tab 的需求很弱，所以 bar 只在 5 个一级页面出现；
 * iOS 没有这一层返回链路，bar 常驻。见 [showTabBar] 的判据与 [BottomTabBar] 的说明。
 */
@Composable
fun RootView(
    /**
     * 点提醒通知要打开的资讯 id，null 表示没有待处理的深链。
     *
     * 消费后必须回调 [onPendingFeedItemConsumed] 置空 —— 否则进程重建时会拿同一个
     * id 再跳一次，把用户拽回一个早已退出的详情页。
     */
    pendingFeedItemId: String? = null,
    onPendingFeedItemConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    // A-3：三个 store 都改成了 ViewModel，用 viewModel() 取而不是 remember{}。
    // remember 会在配置变更后重建实例（分页状态与手册缓存随之丢失，
    // UserProfileStore 还会在构造时用磁盘值覆盖用户正在编辑的输入）。
    val profile: UserProfileStore = viewModel(factory = UserProfileStore.factory(context))
    // 单例：OIDC 回调由 AuthCallbackActivity 处理，必须和这里看到同一个 session。
    val auth = remember { AuthStore.get(context.applicationContext) }
    // 资讯侧接 seu.wiki 线上接口。手册 / 经验长文 / 统一搜索走 DocsStore。
    //
    // 注意**不带 token**：`api/site` 这一组全是公开只读 GET，后端根本不校验鉴权
    // （实测带一个乱写的 Bearer 一样 200）。挂了 token 只会把「能不能读到内容」
    // 和「登录态是否健康」耦合起来，还可能让一次刷列表被续期失败牵连。
    val feedStore: FeedStore = viewModel(factory = FeedStore.Factory)
    val docsStore: DocsStore = viewModel(factory = DocsStore.Factory)
    // 论坛与资讯**共用同一个 Logto 会话**：tokenProvider 直接指向 AuthStore 的
    // accessToken()（自带续期），所以用户登录一次就能同时用在两边，不存在第二次登录。
    val forumStore: ForumStore = viewModel(factory = ForumStore.factory { auth.accessToken() })
    // 论坛里任何需要登录的动作（点赞/评论/发帖）都走这个回调，与个人页同一个登录流程。
    val launchLogin: () -> Unit = { auth.launchSignIn(context) }
    val navController = rememberNavController()

    // 冷启动对账：补回被系统清掉的闹钟、撤掉已删除的提醒。
    // 必须在提醒列表**已加载**之后跑，否则会把「还没读到」误判成「用户删了」。
    LaunchedEffect(Unit) {
        ReminderReceiver.ensureChannel(context.applicationContext)
        profile.reconcileReminderAlarms()
    }

    // 点提醒通知 → 直接落到对应资讯详情。
    // 走 navigate 而不是切换 tab：用户点的是「这一条通知」，不是「资讯 tab」。
    LaunchedEffect(pendingFeedItemId) {
        val itemId = pendingFeedItemId ?: return@LaunchedEffect
        if (itemId.isNotBlank()) {
            navController.navigate(Routes.feedDetail(itemId))
        }
        onPendingFeedItemConsumed()
    }

    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val owningTab = remember(backStack) {
        backStack.asReversed()
            .firstNotNullOfOrNull { entry ->
                AppTab.entries.firstOrNull { it.route == entry.destination.route }
            } ?: AppTab.Home
    }
    // tab 栏只在 5 个一级页面显示（UI/UX 对齐方案 v2 §3.1）。子页面隐藏后，
    // 底部空间让给当前页面的操作条，末尾内容也不用再靠 padding 补丁躲开它。
    //
    // 判据是**栈顶**那一项：详情页、话题页、手册条目、课表、绩点、个人页、
    // 「查看全部」列表的 route 都不在 AppTab 里。
    val showTabBar = remember(backStack) {
        AppTab.entries.any { it.route == backStack.lastOrNull()?.destination?.route }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SeuTheme.colors.groupedBackground),
    ) {
        // 子页面上 [ListBottomPadding] / [TabBarClearance] 要少留一截，
        // 所以必须让 NavHost 树读到「当前 tab 栏不可见」。
        CompositionLocalProvider(LocalTabBarVisible provides showTabBar) {
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
                    docs = docsStore,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenFeedList = { navController.navigate(Routes.HOME_FEED_LIST) },
                    onOpenExperienceList = { navController.navigate(Routes.HOME_FORUM_LIST) },
                    onOpenFeed = { navController.navigate(Routes.feedDetail(it)) },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
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
                    docs = docsStore,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                    onOpenHandbookPart = { navController.navigate(Routes.handbookSection(it)) },
                    forumStore = forumStore,
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                    onCompose = { navController.navigate(Routes.FORUM_COMPOSE) },
                    onOpenTopic = { navController.navigate(Routes.topicDetail(it)) },
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
                    docs = docsStore,
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenFeed = { navController.navigate(Routes.feedDetail(it)) },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                    onOpenSourceList = { scope, keyword ->
                        navController.navigate(Routes.searchSourceList(scope.key, keyword))
                    },
                )
            }

            // --- pushed screens ---
            composable(Routes.PROFILE) {
                ProfileScreen(
                    profile = profile,
                    auth = auth,
                    docs = docsStore,
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                    onOpenForumBookmarks = { navController.navigate(Routes.FORUM_BOOKMARKS) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.HOME_FEED_LIST) {
                // 「为你精选」里的精选条目取自真实 for-you 结果，不再是本地假数据。
                val selected = feedStore.page(FeedScope.ForYou).items.filter { it.isSelected }
                HomeFeedListScreen(
                    items = selected,
                    onBack = { navController.popBackStack() },
                    onOpenItem = { navController.navigate(Routes.feedDetail(it)) },
                )
            }
            composable(Routes.HOME_FORUM_LIST) {
                // 社区列表改为经验长文：forum 那套帖子是编造的，不再走生产路径。
                HomeExperienceListScreen(
                    docs = docsStore,
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                )
            }
            composable(
                route = Routes.FEED_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                FeedItemDetailScreen(
                    profile = profile,
                    store = feedStore,
                    itemId = Routes.decode(entry.arguments?.getString("id")),
                    onBack = { navController.popBackStack() },
                )
            }
            // 论坛详情：接真实后端。
            //
            // **与 App 现有登录共用同一个 Logto 会话**，不另开一套登录：
            // [ForumApiClient] 每次请求都把 [AuthStore.accessToken] 放进
            // `Authorization: Bearer`，后端 `src/lib/logto/bearer.ts` 见到这个头
            // 就只走 Bearer 校验、绝不回退 Cookie。
            //
            // 前置条件（不在本 App 侧）：论坛服务器必须配 `LOGTO_NATIVE_APP_ID`，
            // 否则后端拒绝一切 Bearer token。登录态本身由 AuthStore 管。
            composable(
                route = Routes.FORUM_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ForumPostDetailScreen(
                    store = forumStore,
                    postId = Routes.decode(entry.arguments?.getString("id")),
                    onBack = { navController.popBackStack() },
                    onLoginRequired = launchLogin,
                )
            }
            // 话题页 = 按 tag slug 过滤的帖子列表。后端没有 /api/tags，
            // 中文话题名从本地镜像的 TopicCatalog 取，slug 直接透传给后端。
            composable(
                route = Routes.TOPIC_DETAIL,
                arguments = listOf(navArgument("slug") { type = NavType.StringType }),
            ) { entry ->
                val slug = Routes.decode(entry.arguments?.getString("slug"))
                val topicName = TopicCatalog.topics
                    .firstOrNull { it.slug == slug }?.name
                    ?: TopicCatalog.topics.firstOrNull { t -> t.subtags.any { it.slug == slug } }
                        ?.let { t -> "${t.name} · ${t.subtags.first { it.slug == slug }.name}" }
                    ?: "话题"
                ForumPostListScreen(
                    store = forumStore,
                    title = topicName,
                    tagSlug = slug,
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                    onOpenTopic = { navController.navigate(Routes.topicDetail(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.FORUM_BOOKMARKS) {
                ForumBookmarksScreen(
                    store = forumStore,
                    onBack = { navController.popBackStack() },
                    onOpenPost = { navController.navigate(Routes.forumDetail(it)) },
                    onOpenTopic = { navController.navigate(Routes.topicDetail(it)) },
                    onGoLogin = launchLogin,
                )
            }
            composable(Routes.FORUM_COMPOSE) {
                ForumComposeScreen(
                    store = forumStore,
                    onBack = { navController.popBackStack() },
                    onLoginRequired = launchLogin,
                    onPosted = { id ->
                        // 建完帖直接进详情，并把这个帖子从栈里去掉，用户返回时回列表而不是发帖页。
                        navController.popBackStack()
                        navController.navigate(Routes.forumDetail(id))
                    },
                )
            }
            composable(
                route = Routes.HANDBOOK_SECTION,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                HandbookPartScreen(
                    docs = docsStore,
                    partKey = Routes.decode(entry.arguments?.getString("id")),
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { navController.navigate(Routes.handbookEntry(it)) },
                )
            }
            composable(
                route = Routes.HANDBOOK_ENTRY,
                arguments = listOf(navArgument("slug") { type = NavType.StringType }),
            ) { entry ->
                // slug 形如 survival/观点篇/1-认识，Navigation 解码后原样传出。
                DocEntryDetailScreen(
                    docs = docsStore,
                    profile = profile,
                    slug = Routes.decode(entry.arguments?.getString("slug")),
                    anchor = entry.arguments?.getString("anchor"),
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
                    scopeKey = Routes.decode(entry.arguments?.getString("scope")),
                    keyword = Routes.decode(entry.arguments?.getString("keyword")),
                    onBack = { navController.popBackStack() },
                    docs = docsStore,
                    onOpenFeed = { navController.navigate(Routes.feedDetail(it)) },
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
                    toolId = Routes.decode(entry.arguments?.getString("id")),
                    onBack = { navController.popBackStack() },
                )
            }
            }   // NavHost
        }       // CompositionLocalProvider

        // 下滑淡出而不是直接消失：推入子页面时让栏体"沉下去"，
        // 退回来时它"浮上来"，用户能看出这一层是被推走的。
        AnimatedVisibility(
            visible = showTabBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(animationSpec = tween(200)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(animationSpec = tween(200)) { it } + fadeOut(tween(200)),
        ) {
            BottomTabBar(
                selected = owningTab,
                onSelect = { navController.switchTab(it) },
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

/**
 * 贴底的 tab 栏。
 *
 * **为什么安卓贴底、iOS 常驻**（UI/UX 对齐方案 v2 §2 / §3.1）：
 * 两端栏体形态不同不是视觉偏好，而是**平台机制不同**的直接结果。
 *
 * - **安卓**：有系统返回手势/返回键，「退回一级页面」几乎零成本，子页面里切 tab 的
 *   需求很弱，所以子页面可以放心把栏体撤掉（[LocalTabBarVisible]），底部空间让给
 *   页面自身的操作。栏体既然只在一级页面出现，悬浮遮挡内容就换不回任何东西 ——
 *   国产 Android 的底栏一律通宽贴底，本栏跟着这个惯例。
 * - **iOS**：没有这一层返回链路，详情页里直接切 tab 是正常动线，Apple 也要求 tab 栏
 *   常驻。那边用系统 `TabView`（见 `RootTabView.swift`），栏体由系统绘制，形态不由
 *   我们的代码决定，所以 iOS 侧不需要、也不该在这里对齐安卓的手绘细节。
 *
 * 收敛的是**观感**（通宽、贴底、顶部一条发丝线），分开的是**显隐规则** ——
 * 后者属于机制层的有意差异，方案 §3.1 明确允许两端不一致。
 *
 * 选中态只有**品牌绿**加**一次性按压波纹**（[Modifier.selectable] 的默认 indication）。
 * 刻意不再保留常驻灰底：常驻底色和「按到了没有」是两件事，同时挂着既冗余，
 * 又会在通宽底栏上多出一块长期不消失的视觉噪声。
 */
@Composable
private fun BottomTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SeuTheme.colors
    // **不透明**底。悬浮时这里曾是 0xF2（95%）的半透明，为的是让内容从玻璃下透出来。
    // 贴底之后那个前提不成立了：实测列表正文会隔着栏体读出来（「工程采用周志华…」）
    // 一清二楚，看着像渲染错位而不是玻璃。方案 §5 写的就是「白色或深色底」。
    // secondaryGroupedBackground 正是卡片那层底色（浅色纯白 / 深色 #1C1C1E），
    // 与 iOS 的 secondarySystemGroupedBackground 同一个角色。
    val surface = colors.secondaryGroupedBackground
    // 顶部发丝线，对应 iOS **系统** tab bar 的真实构造（材质 + 顶部一条 hairline）。
    //
    // 原来这行还兼着"给胶囊描边"的作用：悬浮时两侧压在白卡片上没有任何东西定义
    // 边界（栏体本身也是白的），靠自绘光晕 + 描边撑轮廓。贴底之后栏体通宽、顶边
    // 就是分界，一条发丝线足够，也不再需要投影和描边。
    val hairline = if (colors.isDark) Color(0x1FFFFFFF) else Color(0x14000000)
    val hairlineWidth = 1.dp
    // 栏体高度先取出来：drawBehind 的 lambda 不是 composable，取不到 tabBarHeight。
    val barHeight = tabBarHeight

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(surface)
            // 发丝线通宽画在栏体最上沿，缩进半个线宽避免被抗锯齿边缘吃掉半条。
            .drawBehind {
                val y = hairlineWidth.toPx() / 2f
                drawLine(
                    color = hairline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = hairlineWidth.toPx(),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 高度随字体缩放（见 ui/Screen.kt 的 tabBarHeight）：固定 58dp 在大字体下
                // 会裁掉标签。上下都要有界 —— TabItem 用了 fillMaxHeight()，父容器若只给
                // min 而不给 max，测量会拿到无界高度约束，整棵布局会塌掉。
                .height(barHeight)
                // 让读屏把这 5 个 item 当成一组 tab 播报（"主页，标签，5 个中的第 1 个"），
                // 否则 TalkBack 会把它们当成 5 个互不相干的按钮。
                .selectableGroup(),
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
        // 导航栏 inset 那一截也铺在同一个 surface 里，材质一路落到屏幕底边。
        // 不补这个 Spacer 的话，手势导航条后面会直接露出根 Box 的 groupedBackground，
        // 栏体下方形成一道色带。
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/**
 * One tab.
 *
 * 选中态只由两样东西表达：**品牌绿**（图标与文字一起变，报告 §5）与**一次性按压波纹**。
 *
 * 原来这里还有一个常驻的灰底胶囊（`fill` 0x26000000 / 0x24FFFFFF）加一圈宽度动画
 * （`inset` 16dp ↔ 9dp），照的是 iOS 26 玻璃 tab 栏的滑胶囊。两层反馈叠在一起是冗余的：
 * 一次性的按压动画负责"我按到了"，常驻底色负责"我在哪"，而"我在哪"已经由品牌绿说完了，
 * 灰底在通宽底栏上还多出一块长期不消失的灰斑。现在只留一次性的那层。
 *
 * 图标不做「线框 / 实心」两态：跨端约定是按 SF Symbol 名称查表（报告 §7 明确保留），
 * 而 `house` / `newspaper` / `bubble.left.and.text.bubble.right` / `square.grid.2x2` /
 * `magnifyingglass` 在 [SeuIcons] 里各只有一个字形，选中靠颜色区分。
 */
@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SeuTheme.colors
    // 未选中用主文字色而不是灰的：与 iOS 系统 tab 栏一致，灰底去掉后这层对比
    // 就全靠明度差撑住，再压暗一档会让五个 item 显得发闷。
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.label,
        animationSpec = tween(220),
        label = "tabTint",
    )

    Box(
        // 用 `selectable(role = Role.Tab)` 而不是 `clickable`：前者才带上
        // 「这是一个标签页 / 当前是否选中」的语义，读屏才会播报选中状态；
        // 后者只是无差别的可点击元素。
        //
        // 指示层**用默认的**（M3 波纹）—— 这是选中之外唯一的动效：按下去亮一圈，
        // 手抬起来就没了。UI/UX 对齐方案 v2 §3.3 点名的就是原来 `indication = null`
        // 完全没有反应的那一处。
        modifier = modifier.selectable(
            selected = selected,
            role = Role.Tab,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = tab.icon,
                // 置空：下面紧挨着的 Text 已经带了同样的标签，
                // 两处都报读会变成"主页 主页"（UI/UX 方案 §3.5 点名）。
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(tab.labelRes),
                style = SeuType.TabLabel,
                color = tint,
                maxLines = 1,
            )
        }
    }
}
