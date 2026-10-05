package tech.iflink.seuwiki.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tech.iflink.seuwiki.data.AuthStore
import tech.iflink.seuwiki.data.FeedApiClient
import tech.iflink.seuwiki.data.FeedStore
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.ui.detail.FeedItemDetailScreen
import tech.iflink.seuwiki.ui.detail.HomeFeedListScreen
import tech.iflink.seuwiki.ui.detail.HomeExperienceListScreen
import tech.iflink.seuwiki.ui.detail.CommunityComingSoonScreen
import tech.iflink.seuwiki.ui.detail.DocEntryDetailScreen
import tech.iflink.seuwiki.ui.detail.HandbookPartScreen
import tech.iflink.seuwiki.ui.detail.SearchSourceListScreen
import tech.iflink.seuwiki.ui.experience.ExperienceScreen
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
    // 单例：OIDC 回调由 AuthCallbackActivity 处理，必须和这里看到同一个 session。
    val auth = remember { AuthStore.get(context.applicationContext) }
    // 资讯侧接 seu.wiki 线上接口。手册 / 经验长文 / 统一搜索走 DocsStore。
    //
    // 注意**不带 token**：`api/site` 这一组全是公开只读 GET，后端根本不校验鉴权
    // （实测带一个乱写的 Bearer 一样 200）。挂了 token 只会把「能不能读到内容」
    // 和「登录态是否健康」耦合起来，还可能让一次刷列表被续期失败牵连。
    val feedStore = remember { FeedStore(client = FeedApiClient()) }
    val docsStore = remember { DocsStore() }
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
                        navController.navigate(Routes.searchSourceList(scope.label, keyword))
                    },
                )
            }

            // --- pushed screens ---
            composable(Routes.PROFILE) {
                ProfileScreen(
                    profile = profile,
                    auth = auth,
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
            // 社区详情入口保留路由但只给「即将上线」：seu-wiki-forum 至今没有任何
            // HTTP API 路由，发帖/点赞/评论/关注都接不通。原来的 ForumPostDetailScreen
            // 是拿 MockData 里编造的帖子正文与「林晚舟」等虚构用户渲染的，
            // 已从生产路径移除 —— 编造内容不该出现在用户面前。
            composable(
                route = Routes.FORUM_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                CommunityComingSoonScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.TOPIC_DETAIL,
                arguments = listOf(navArgument("slug") { type = NavType.StringType }),
            ) {
                CommunityComingSoonScreen(onBack = { navController.popBackStack() })
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
                    scope = Routes.decode(entry.arguments?.getString("scope")),
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

/**
 * 沿 [shape] 自身曲线向外描若干圈淡墨，形成一圈柔和的边缘光晕 —— 充当悬浮 tab 栏的
 * 投影。**必须在 clip 之前调用**（光晕要画到 shape 之外）。
 *
 * 为什么不直接用 `Modifier.shadow(elevation, shape)`：elevation 阴影是「形状 ⊕ 模糊」，
 * 模糊量相对圆角半径越大，阴影外轮廓的等效圆角就越"方"，和外框那条弧对不齐。
 * 而这里描的是**同一条路径**，只是向外偏移，所以内外弧在构造上严格同心；
 * 清晰度也完全由下面这几圈的线宽与 alpha 控制，不受设备 blur 实现差异影响。
 *
 * 多圈叠加而非单圈：单圈描边边缘会有一条硬边，叠加几圈低 alpha 才是渐变，
 * 靠近轮廓处 alpha 累加、向外迅速衰减。
 *
 * 为什么要自己扛这个边界：iOS 系统 tab 栏靠材质里的真实背景模糊撑起轮廓，而本工程的
 * surface 只是 `0xF2FFFFFF` 的扁平近白色、没有 blur。去掉描边后栏体两侧一旦压到白色
 * 卡片上就没有任何东西定义边界（栏体本身也是白的）。所以光晕不能省。
 *
 * @param rings 由内到外的 (线宽 dp, alpha) 列表。
 */
private fun DrawScope.drawEdgeHalo(
    shape: Shape,
    color: Color,
    rings: List<Pair<Dp, Float>> = listOf(1.2.dp to 0.045f, 3.dp to 0.05f, 5.5.dp to 0.055f),
) {
    val path = when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }
    rings.forEach { (width, alpha) ->
        drawPath(
            path = path,
            color = color.copy(alpha = alpha),
            style = Stroke(width = width.toPx()),
        )
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
    // 浅色阴影的 alpha 看着定：0x1F(12%) 在**白卡片**上几乎读不出来，栏体两端就会
    // 直接和内容融在一起（这是去掉描边后必须自己扛起边界的唯一手段）。
    val shadow = if (colors.isDark) Color(0x40000000) else Color(0x333C3C43)
    // 顶部发丝线，对应 iOS **系统** tab bar 的真实构造（材质 + 顶部一条 hairline，见
    // iOS RootTabView 用的是系统 TabView，不是手搓胶囊）。
    //
    // 原来这里是一圈完整的 1dp 描边，但胶囊圆角有 29dp（栏高的一半），描边弧与阴影的
    // 膨胀弧在这么大的半径上必然不共心，两端会看到"双重弧线"。只留顶部一条，
    // 既对上 iOS，也去掉了打架的那条轮廓。
    val hairline = if (colors.isDark) Color(0x1FFFFFFF) else Color(0x14000000)
    val hairlineWidth = 1.dp
    // 栏体高度先取出来：drawBehind 的 lambda 不是 composable，取不到 tabBarHeight。
    val barHeight = tabBarHeight
    // 胶囊的圆角 = 半径，所以跟着栏体高度走（大字体下栏体长高，圆角也要跟着圆）。
    val radius = barHeight / 2
    // C：改用超椭圆，与全站卡片（cardStyle 一律 ContinuousRoundedShape）以及 iOS 的
    // RoundedRectangleStyle.continuous 是同一套曲线语言，不再是工程里唯一的圆弧孤岛。
    val shape = ContinuousRoundedShape(radius)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 高度随字体缩放（见 ui/Screen.kt 的 tabBarHeight）：固定 58dp 在大字体下
                // 会裁掉标签。上下都要有界 —— TabItem 用了 fillMaxHeight()，父容器若只给
                // min 而不给 max，测量会拿到无界高度约束，整棵布局会塌掉。
                .height(barHeight)
                // 刻意**不用** Modifier.shadow(elevation, shape)。elevation 阴影是
                // 「形状 ⊕ 模糊」：模糊量相对圆角半径一大，阴影外轮廓的等效圆角就跟着
                // 变"方"，和外框那条弧对不上 —— 这正是最初"弧度差异很奇怪"的来源
                // （29dp 半径配 8dp 模糊，22px 对 80px，27%）。
                //
                // 改用下面 drawBehind 里那条「同源描边光晕」：它就是把同一条曲线向外
                // 描若干圈，构造上与栏体边缘严格同心，不可能错位，而且清晰度可控。
                //
                // 必须在 clip **之前**：光晕要画到 shape 之外，clip 之后就只能被裁掉了。
                .drawBehind { drawEdgeHalo(shape, shadow) }
                .clip(shape)
                .background(surface)
                // 顶部发丝线只画在两段圆角之间的直边上，缩进半个线宽，避免被 clip 的
                // 抗锯齿边缘吃掉半条线。
                .drawBehind {
                    val inset = hairlineWidth.toPx() / 2f
                    val insetX = radius.toPx()
                    drawLine(
                        color = hairline,
                        start = Offset(insetX, inset),
                        end = Offset(size.width - insetX, inset),
                        strokeWidth = hairlineWidth.toPx(),
                    )
                }
                .clip(shape)
                .background(surface),
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
                .clip(ContinuousRoundedShape(50.dp))
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
