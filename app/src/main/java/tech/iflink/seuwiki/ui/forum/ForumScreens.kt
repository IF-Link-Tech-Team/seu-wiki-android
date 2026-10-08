package tech.iflink.seuwiki.ui.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.ForumApiClient
import tech.iflink.seuwiki.data.ForumStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.TintPill
import tech.iflink.seuwiki.design.VSpace
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.models.ForumTargetType
import tech.iflink.seuwiki.models.SuggestedTopic
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.LocalTabBarVisible
import tech.iflink.seuwiki.ui.TabBarClearance
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.InitialsAvatar

/**
 * 论坛帖子列表。
 *
 * **匿名可读**，登录后按 App 现有的 Logto 身份返回个人化内容 —— 所以未登录时
 * 这个页面照常有东西看，不该弹登录墙。
 *
 * @param tagSlug 非空时按话题过滤，对应「话题页」。后端对**未收录的 slug 返回
 * 200 + 空数组**而不是 404，所以空态文案要说「这个话题还没有帖子」而不是「出错」。
 */
/**
 * 排序状态的持有者。
 *
 * 列表可能被**同时**渲染两份（页头里的切换器 + 列表体），两份要共享同一个
 * 「当前排序」，否则用户在页头切了「热门」、列表还停在「最新」。
 *
 * **只能存枚举本身，不能存自定义 holder 类**：`rememberSaveable` 的默认
 * Saver 只认能塞进 Bundle 的类型，传一个普通类进去会在**首次进入组合**时就
 * `IllegalArgumentException` 直接崩（实测在经验页的 HorizontalPager 里一进
 * 「关注」分区就崩到桌面，编译期与 JVM 单测都抓不到，只有真机能）。
 * [ForumSort] 是枚举，走 Compose 内置的 `mutableStateSaver` 可以正常存取。
 */
@Composable
private fun rememberForumListState(tagSlug: String?): MutableState<ForumSort> =
    rememberSaveable(tagSlug) { mutableStateOf(ForumSort.Latest) }

/** 话题页 / 首页论坛区：自带页头的完整列表页。 */
@Composable
fun ForumPostListScreen(
    store: ForumStore,
    title: String,
    modifier: Modifier = Modifier,
    tagSlug: String? = null,
    onOpenPost: (String) -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onCompose: (() -> Unit)? = null,
) {
    if (onBack != null) {
        DetailHeader(title = title, onBack = onBack)
    } else {
        var sort by rememberForumListState(tagSlug)
        ScreenHeader(title = title, onProfileClick = null, trailing = { SortToggle(sort) { sort = it } })
    }
    ForumPostList(
        store = store,
        modifier = modifier,
        tagSlug = tagSlug,
        onOpenPost = onOpenPost,
        onOpenTopic = onOpenTopic,
        onCompose = onCompose,
    )
}

/**
 * 只有列表、没有页头 —— 给嵌在别人页头下面的场合用（经验页的「关注」分页就在
 * HorizontalPager 里，外面已经有 ScreenHeader 了，再套一层会出双页头）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumPostList(
    store: ForumStore,
    modifier: Modifier = Modifier,
    tagSlug: String? = null,
    onOpenPost: (String) -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
    onCompose: (() -> Unit)? = null,
) {
    val state = store.list
    val sort by rememberForumListState(tagSlug)
    val listState = rememberLazyListState()

    LaunchedEffect(tagSlug, sort) { store.refresh(sort, tagSlug) }

    // 滚到离底部 3 屏就预取下一页，用户基本感觉不到加载。
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.nextCursor) {
        if (shouldLoadMore && state.nextCursor != null) store.loadMore()
    }

    val colors = SeuTheme.colors
    TabPage {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                state.isLoading && !state.hasLoaded -> LoadingView(topPadding = 40.dp)
                state.posts.isEmpty() -> EmptyStateView(
                    title = if (state.isOffline) "加载失败" else "还没有帖子",
                    description = state.errorMessage
                        ?: if (tagSlug != null) "这个话题下暂时没有内容，来发第一条吧。" else "成为第一个发帖的人。",
                    icon = { ForumGlyph(icon = "text.bubble", tint = SeuTheme.colors.tertiaryLabel) },
                    topPadding = 40.dp,
                )
                else -> PullToRefreshBox(
                    // 下拉真的重拉第一页（对齐资讯流的 PullToRefreshBox 用法）。
                    isRefreshing = state.isLoading,
                    onRefresh = { store.refresh(sort, tagSlug) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        // 末尾留出 tab 栏（子页面 tab 栏是隐藏的，这里是保险）
                        bottom = ListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.posts, key = { it.id }) { post ->
                        ForumPostRow(
                            post = post,
                            onClick = { onOpenPost(post.id) },
                            onOpenTopic = onOpenTopic,
                        )
                    }
                    if (state.isLoadingMore) {
                        item(key = "__loading_more__") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("加载中…", style = SeuType.Footnote, color = colors.secondaryLabel) }
                        }
                    }
                }
                }
            }
        }
        ComposeFab(onCompose = onCompose)
    }
    }
}

/**
 * 热榜（`GET /api/posts?sort=hot`，置顶优先）。
 *
 * 与 [ForumPostList] 的差别在分页：热榜是 **offset 分页**（响应给 `next_offset` /
 * `total_count`），keyset 游标在热度分实时变化时会错位；对 hot 传 cursor 后端直接
 * 400 `INVALID_CURSOR`。headless：外面已有页头（经验页 pager / 主页查看全部的
 * DetailHeader），这里不叠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumHotList(
    store: ForumStore,
    modifier: Modifier = Modifier,
    onOpenPost: (String) -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
    onCompose: (() -> Unit)? = null,
) {
    val state = store.hot
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { store.loadHotIfNeeded() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.nextOffset) {
        if (shouldLoadMore && state.nextOffset != null) store.loadMoreHot()
    }

    val colors = SeuTheme.colors
    TabPage {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                state.isLoading && !state.hasLoaded -> LoadingView(topPadding = 40.dp)
                state.posts.isEmpty() -> EmptyStateView(
                    title = if (state.isOffline) "加载失败" else "还没有热门帖子",
                    description = state.errorMessage ?: "论坛刚开张，去「经验」页写下第一篇分享。",
                    icon = { ForumGlyph(icon = "flame.fill", tint = colors.tertiaryLabel) },
                    topPadding = 40.dp,
                )
                else -> PullToRefreshBox(
                    // 下拉真的重拉热榜第一页。
                    isRefreshing = state.isLoading,
                    onRefresh = { store.refreshHot() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = ListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.posts, key = { it.id }) { post ->
                        ForumPostRow(
                            post = post,
                            onClick = { onOpenPost(post.id) },
                            onOpenTopic = onOpenTopic,
                        )
                    }
                    if (state.isLoadingMore) {
                        item(key = "__loading_more__") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("加载中…", style = SeuType.Footnote, color = colors.secondaryLabel) }
                        }
                    }
                }
                }
            }
        }
        ComposeFab(onCompose = onCompose)
    }
    }
}

/** 主页「社区热议」查看全部的落点：带页头的完整热榜。 */
@Composable
fun ForumHotListScreen(
    store: ForumStore,
    title: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
) {
    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = title, onBack = onBack)
            ForumHotList(store = store, onOpenPost = onOpenPost, onOpenTopic = onOpenTopic)
        }
    }
}

/**
 * 关注流（`GET /api/feed/following`，需登录）。
 *
 * 四态，对齐 iOS `ForumFollowingFeedView`：
 * 1. **未登录 / 401** → 登录引导。401 是正常路径（未登录），不是网络错误，
 *    由 [ForumStore.refreshFollowing] 翻成 `needsLogin`。
 * 2. **零关注** → 推荐板块列表，可直接点「关注」（服务端零关注时随空列表下发
 *    `suggested_tags`；没下发时回退到本地目录的 8 个主题，slug 与后端同源）。
 * 3. **有关注、流为空** → 已关注板块卡 + 「还没有新帖」。
 * 4. 正常 → 已关注板块卡（点 chip 取关）+ 帖子流。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ForumFollowingList(
    store: ForumStore,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
) {
    val state = store.following
    val colors = SeuTheme.colors

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            store.loadFollows()
            store.refreshFollowing()
        }
    }

    // 1) 未登录 / 401：登录引导，与论坛收藏页的空态同一形态。
    if (!isLoggedIn || state.needsLogin) {
        EmptyStateView(
            title = "需要登录",
            description = "登录 IF.Link 账号后，这里会聚合你关注板块的新帖。",
            icon = { ForumGlyph(icon = "person.crop.circle", tint = colors.tertiaryLabel) },
            topPadding = 48.dp,
            action = {
                Button(
                    onClick = onLogin,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("登录 IF.Link 账号") }
            },
        )
        return
    }

    val followed = store.followedTags
    val followedSlugs = followed.map { it.slug }.toSet()
    val suggestions = store.suggestedTags.ifEmpty {
        TopicCatalog.topics.map { SuggestedTopic(slug = it.slug, name = it.name, postCount = 0) }
    }

    when {
        state.isLoading && !state.hasLoaded -> LoadingView(topPadding = 40.dp)

        state.isOffline && state.posts.isEmpty() && followed.isEmpty() -> EmptyStateView(
            title = "加载失败",
            description = state.errorMessage,
            icon = { ForumGlyph(icon = "wifi.exclamationmark", tint = colors.tertiaryLabel) },
            topPadding = 48.dp,
            action = {
                Button(
                    onClick = { store.refreshFollowing(); store.loadFollows() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("重试") }
            },
        )

        // 2) 零关注：推荐板块。loadFollows 还没回来时先不给这个态（闪烁），
        //    用 followsLoaded 区分「真的零关注」和「还没查完」。
        store.followsLoaded && followed.isEmpty() && state.posts.isEmpty() -> LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = ListBottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "guide") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForumGlyph(icon = "tag", tint = colors.tertiaryLabel)
                    Text("还没有关注任何板块", style = SeuType.Title3, color = colors.secondaryLabel)
                    Text(
                        "关注感兴趣的板块，相关新帖会聚合到这里。",
                        style = SeuType.Footnote,
                        color = colors.tertiaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "suggested_title") {
                Text("推荐板块", style = SeuType.Headline, color = colors.label)
            }
            items(suggestions, key = { it.slug }) { topic ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cardStyle()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconWell(tint = colors.accent, size = 32.dp) {
                        Icon(
                            imageVector = SeuIcons.of("tag"),
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(topic.name, style = SeuType.SubheadlineMedium, color = colors.label)
                        if (topic.postCount > 0) {
                            Text(
                                "${topic.postCount} 篇帖子",
                                style = SeuType.Caption,
                                color = colors.secondaryLabel,
                            )
                        }
                    }
                    ForumChip(
                        label = if (topic.slug in followedSlugs) "已关注" else "关注",
                        selected = topic.slug in followedSlugs,
                        onClick = { store.toggleFollowTag(topic.slug) },
                    )
                }
            }
        }

        else -> {
            val listState = rememberLazyListState()
            val shouldLoadMore by remember {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val total = listState.layoutInfo.totalItemsCount
                    total > 0 && last >= total - 3
                }
            }
            LaunchedEffect(shouldLoadMore, state.nextCursor) {
                if (shouldLoadMore && state.nextCursor != null) store.loadMoreFollowing()
            }
            PullToRefreshBox(
                // 下拉重拉关注流与关注目录。
                isRefreshing = state.isLoading,
                onRefresh = { store.refreshFollowing(); store.loadFollows() },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (followed.isNotEmpty()) {
                    item(key = "followed_tags") {
                        Column(Modifier.fillMaxWidth().cardStyle().padding(14.dp)) {
                            Text("我关注的板块", style = SeuType.Headline, color = colors.label)
                            VSpace(10.dp)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                followed.forEach { tag ->
                                    // 点一下取关（toggle），与 iOS FollowedTagsCard 一致。
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(colors.accent.copy(alpha = 0.12f))
                                            .clickable { store.toggleFollowTag(tag.slug) }
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                    ) {
                                        Icon(
                                            imageVector = SeuIcons.of("checkmark"),
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = tag.name,
                                            style = SeuType.CaptionMedium,
                                            color = colors.accent,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.posts.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ForumGlyph(icon = "bubble.left.and.text.bubble.right", tint = colors.tertiaryLabel)
                            Text("还没有新帖", style = SeuType.Title3, color = colors.secondaryLabel)
                            Text(
                                "你关注的板块最近没有新帖。",
                                style = SeuType.Footnote,
                                color = colors.tertiaryLabel,
                            )
                        }
                    }
                } else {
                    items(state.posts, key = { it.id }) { post ->
                        ForumPostRow(
                            post = post,
                            onClick = { onOpenPost(post.id) },
                            onOpenTopic = onOpenTopic,
                        )
                    }
                    if (state.isLoadingMore) {
                        item(key = "__loading_more__") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("加载中…", style = SeuType.Footnote, color = colors.secondaryLabel) }
                        }
                    }
                }
            }
            }
        }
    }
}

/** 详情页头部的小标记（置顶 / 精选），对齐 iOS 的橙色 Label。 */
@Composable
private fun DetailPill(icon: String, label: String) {
    val colors = SeuTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = SeuIcons.of(icon),
            contentDescription = null,
            tint = colors.orange,
            modifier = Modifier.size(12.dp),
        )
        Text(label, style = SeuType.CaptionMedium, color = colors.orange)
    }
}

/**
 * 右下角发帖按钮。
 *
 * 国内社区 App 的通行做法；iOS 那侧用 toolbar 按钮，属平台机制差异
 * （UI/UX 方案 §6「经验」条目：论坛接通后安卓用悬浮发帖按钮，iOS 用 toolbar）。
 *
 * 让位跟着 [LocalTabBarVisible] 走：这个列表既可能是一级页（经验页「关注」分区，
 * tab 栏可见），也可能是子页面（话题页，tab 栏隐藏），写死偏移会有一边悬空或被压住。
 *
 * [tabBarHeight] 按定义**不含系统导航栏 inset**（见 Screen.kt 的注释），而根容器是
 * 普通 Box 不是 Scaffold，没人替我们消费 inset —— 所以这里必须自己把 navigationBars
 * 那一截加上，否则按钮底边会被 tab 栏/手势条压掉一截（实测就是这么发现的）。
 * 显式算而不是套 `navigationBarsPadding()`：那个会连左右也一起内缩，把按钮推离屏幕右边。
 */
@Composable
private fun BoxScope.ComposeFab(onCompose: (() -> Unit)?) {
    if (onCompose == null) return
    val colors = SeuTheme.colors
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(
                end = 16.dp,
                bottom = navBottom + if (LocalTabBarVisible.current) TabBarClearance + 12.dp else 24.dp,
            )
            .size(52.dp)   // 52dp 直径已超 Android 的 48dp 最小触控目标
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = onCompose)
            .semantics { this.contentDescription = "发帖" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SeuIcons.of("plus"),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}


/**
 * 可点胶囊。抄 [ExperienceScreen] 的 FacetChip 范式：`CircleShape` + 选中语义。
 *
 * 不用 design 层的 `TintPill` —— 那个是**纯展示**的（没有 onClick），
 * 拿它当按钮会得到一个点不动的死元素。
 */
@Composable
private fun ForumChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Text(
        text = label,
        style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
        color = if (selected) colors.onAccentInverted else colors.label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.secondaryGroupedBackground)
            .selectableChip(selected = selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** chip 的选中语义 + 按下反馈（与 ExperienceScreen 保持一致）。 */
private fun Modifier.selectableChip(selected: Boolean, onClick: () -> Unit): Modifier =
    this
        .semantics { this.selected = selected }
        .clickable(onClick = onClick)

/**
 * 话题标签。
 *
 * [onClick] 为 null 时是纯展示的 TintPill；非 null 时换成 `CircleShape` + 可点语义，
 * 触控区补到 32dp（Android 最小目标 48dp 是给主要控件的，帖子里的标签是次要入口，
 * 但仍不该只有 20dp 高可点）。
 */
@Composable
private fun ForumTagPill(text: String, onClick: (() -> Unit)? = null) {
    val tint = SeuTheme.colors.accent
    if (onClick == null) {
        TintPill(text = text, tint = tint)
        return
    }
    Text(
        text = text,
        style = SeuType.CaptionMedium,
        color = tint,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .selectableChip(selected = false, onClick = onClick)
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * 话题页的「最新 / 赞最多」切换。排序值直接对应后端 `sort` 参数，写错会被判 `INVALID_SORT`。
 *
 * **只放 Latest / Top 两项**：热榜（[ForumSort.Hot]）是 offset 分页，与这里的
 * keyset 游标不兼容，而且它有自己专属的入口（经验 tab 默认页）。Top 叫「赞最多」
 * 而不是「热门」，避免和热榜的「热门」混淆（后端 top = likes_count 倒序）。
 */
@Composable
private fun SortToggle(current: ForumSort, onChange: (ForumSort) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(ForumSort.Latest to "最新", ForumSort.Top to "赞最多").forEach { (option, label) ->
            val selected = option == current
            ForumChip(
                label = label,
                selected = selected,
                onClick = { if (!selected) onChange(option) },
            )
        }
    }
}

/**
 * 一行帖子。对齐 iOS `ForumPostCard`：
 * 置顶 pill（pin.fill）→ 标题 → 摘要 → 作者 + 相对时间 + eye/heart/bubble 三个计数。
 *
 * 三个计数都是服务端的反范式列（`views_count` / `likes_count` / `comments_count`），
 * 是真实数据；只有「我是否已赞」这个状态服务端没有只读接口，所以列表里不给红心
 * 填充态，只给数字（iOS 同）。
 *
 * [onOpenTopic] 非空时标签可点 —— 这是话题页的入口。`Routes.topicDetail` 之前只定义
 * 没人调用，整个话题页没有入口。
 */
@Composable
fun ForumPostRow(post: ForumPost, onClick: () -> Unit, onOpenTopic: ((String) -> Unit)? = null) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardStyle()
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        if (post.isPinned) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = SeuIcons.of("pin.fill"),
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(12.dp),
                )
                Text("置顶", style = SeuType.CaptionMedium, color = colors.orange)
            }
            VSpace(6.dp)
        }
        post.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = SeuType.Headline,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VSpace(6.dp)
        }
        Text(
            text = post.excerpt,
            style = SeuType.Body,
            color = colors.secondaryLabel,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (post.images.isNotEmpty()) {
            VSpace(10.dp)
            PostImageStrip(images = post.images)
        }
        VSpace(10.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            post.author?.let { author ->
                InitialsAvatar(name = author.nameOrFallback, size = 22.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = author.nameOrFallback,
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(88.dp),
                )
            }
            post.createdAt?.let {
                val rel = Format.relative(context, it)
                if (rel.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(rel, style = SeuType.Caption, color = colors.tertiaryLabel, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            StatGlyph(icon = "eye", count = post.viewsCount)
            Spacer(Modifier.width(10.dp))
            StatGlyph(icon = "heart", count = post.likesCount)
            Spacer(Modifier.width(10.dp))
            StatGlyph(icon = "bubble.left", count = post.commentsCount)
        }
        if (post.tags.isNotEmpty()) {
            VSpace(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                post.tags.take(3).forEach { tag ->
                    // slug 为空的标签不给入口：话题页要按 slug 过滤，传空串后端
                    // 会返回全站帖子，那不是用户点的那个话题。
                    ForumTagPill(
                        text = tag.name,
                        onClick = onOpenTopic?.takeIf { tag.slug.isNotBlank() }
                            ?.let { open -> { open(tag.slug) } },
                    )
                }
            }
        }
    }
}

/** 列表卡右下角的计数：图标 + 数字（eye / heart / bubble.left），对齐 iOS statLabel。 */
@Composable
private fun StatGlyph(icon: String, count: Int) {
    val colors = SeuTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = SeuIcons.of(icon),
            contentDescription = null,
            tint = colors.tertiaryLabel,
            modifier = Modifier.size(11.dp),
        )
        Text("$count", style = SeuType.Caption, color = colors.tertiaryLabel)
    }
}

/** 帖子详情 + 评论。 */
@Composable
fun ForumPostDetailScreen(
    store: ForumStore,
    postId: String,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    onOpenHandbookArticle: (String) -> Unit = {},
    onEditPost: (String) -> Unit = {},
) {
    val colors = SeuTheme.colors
    val state = store.detail
    var commentDraft by remember(postId) { mutableStateOf("") }
    // 待确认的删除动作：first=目标类型，second=目标 id，third=是否管理删除。
    var pendingDelete by remember(postId) {
        mutableStateOf<Triple<ForumTargetType, String, Boolean>?>(null)
    }

    LaunchedEffect(postId) { store.loadDetail(postId) }

    pendingDelete?.let { (targetType, targetId, isAdminAction) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (isAdminAction) "管理删除" else "删除") },
            text = {
                Text(
                    if (isAdminAction) "以管理员身份删除这条内容？关联的评论、点赞等数据会一并删除，此操作无法恢复。"
                    else "删除后无法恢复，确定删除吗？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        val done: () -> Unit =
                            if (targetType == ForumTargetType.Post) {
                                // 帖没了就回列表；信息流已由 store 内部作废重拉。
                                onBack
                            } else {
                                { store.loadDetail(postId) }
                            }
                        if (isAdminAction) store.adminDeleteContent(targetType, targetId, done)
                        else store.deleteContent(targetType, targetId, done)
                    },
                ) { Text("删除", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "帖子", onBack = onBack)
            val post = state.post
            if (post == null) {
                if (state.isLoading) {
                    LoadingView(topPadding = 40.dp)
                } else {
                    EmptyStateView(
                        title = if (state.needsLogin) "需要登录" else "打不开这篇帖子",
                        description = state.errorMessage ?: "它可能已被作者删除。",
                        icon = { ForumGlyph(icon = "doc.text", tint = colors.tertiaryLabel) },
                        topPadding = 40.dp,
                    )
                }
                return@TabPage
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "post") {
                    Column(Modifier.fillMaxWidth().cardStyle().padding(14.dp)) {
                        if (post.isPinned || post.featured) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (post.isPinned) {
                                    DetailPill(icon = "pin.fill", label = "置顶")
                                }
                                if (post.featured) {
                                    DetailPill(icon = "star.fill", label = "精选")
                                }
                            }
                            VSpace(8.dp)
                        }
                        post.title?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = SeuType.Headline, color = colors.label)
                            VSpace(8.dp)
                        }
                        Text(post.content, style = SeuType.Body, color = colors.label)
                        if (post.images.isNotEmpty()) {
                            VSpace(12.dp)
                            PostImageStrip(images = post.images)
                        }
                        VSpace(12.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            post.author?.let {
                                InitialsAvatar(name = it.nameOrFallback, size = 26.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(it.nameOrFallback, style = SeuType.Caption, color = colors.secondaryLabel)
                            }
                        }
                        VSpace(12.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ForumChip(
                                label = if (post.likedByMe) "已赞" else "赞 ${post.likesCount}",
                                selected = post.likedByMe,
                                onClick = {
                                    if (!isLoggedIn) onLoginRequired() else store.toggleLike(post.id)
                                },
                            )
                            ForumChip(
                                label = if (post.bookmarked) "已收藏" else "收藏",
                                selected = post.bookmarked,
                                onClick = {
                                    if (!isLoggedIn) onLoginRequired() else store.toggleBookmark(post.id)
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            // 浏览数：loadDetail 内部已打过 +1（失败静默），这里显示的是
                            // 服务端权威值，可能比列表里的大 1。
                            StatGlyph(icon = "eye", count = post.viewsCount)
                            Spacer(Modifier.width(10.dp))
                            StatGlyph(icon = "bubble.left", count = post.commentsCount)
                        }
                        // 作者自管（编辑/删除）与管理删除。可见性按 viewer 投影判断，
                        // 真正的门禁在服务端（403 兜底），与 AGENTS.md 的登录门禁规则不冲突。
                        val viewerId = store.viewer?.id
                        val isAuthor = viewerId != null && post.author?.id == viewerId
                        val canAdminDelete = store.hasCapability("admin:content:delete")
                        if (isAuthor || canAdminDelete) {
                            VSpace(10.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isAuthor) {
                                    ForumChip(
                                        label = "编辑",
                                        selected = false,
                                        onClick = { onEditPost(post.id) },
                                    )
                                    ForumChip(
                                        label = "删除",
                                        selected = false,
                                        onClick = {
                                            pendingDelete =
                                                Triple(ForumTargetType.Post, post.id, false)
                                        },
                                    )
                                }
                                if (canAdminDelete && !isAuthor) {
                                    ForumChip(
                                        label = "管理删除",
                                        selected = false,
                                        onClick = {
                                            pendingDelete =
                                                Triple(ForumTargetType.Post, post.id, true)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 反向入口：该帖已沉淀成手册文章时给「查看手册文章」卡片。
                post.handbookArticle?.let { article ->
                    item(key = "handbook_article") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cardStyle()
                                .clickable { onOpenHandbookArticle(article.id) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = SeuIcons.of("books.vertical.fill"),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "已收录进东大生存手册",
                                    style = SeuType.Caption,
                                    color = colors.secondaryLabel,
                                )
                                Text(
                                    article.title,
                                    style = SeuType.SubheadlineMedium,
                                    color = colors.label,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = SeuIcons.of("chevron.right"),
                                contentDescription = null,
                                tint = colors.tertiaryLabel,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                item(key = "comments_header") {
                    Text(
                        text = if (state.comments.isEmpty()) "还没有评论" else "${state.comments.size} 条评论",
                        style = SeuType.Footnote,
                        color = colors.secondaryLabel,
                    )
                }

                if (state.comments.isEmpty()) {
                    item(key = "no_comments") {
                        Text("来抢沙发。", style = SeuType.Body, color = colors.tertiaryLabel)
                    }
                } else {
                    items(state.comments, key = { it.id }) { comment ->
                        val author = state.commentAuthors[comment.authorId]
                        Row(Modifier.fillMaxWidth().cardStyle().padding(12.dp)) {
                            InitialsAvatar(name = author?.nameOrFallback ?: "匿", size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = author?.nameOrFallback ?: "已注销用户",
                                    style = SeuType.Caption,
                                    color = colors.secondaryLabel,
                                )
                                VSpace(4.dp)
                                Text(comment.content, style = SeuType.Body, color = colors.label)
                            }
                            // 评论作者可删自己的；管理员可删任何评论。
                            val commentViewerId = store.viewer?.id
                            val ownComment = commentViewerId != null && comment.authorId == commentViewerId
                            val canAdminDeleteComment = store.hasCapability("admin:content:delete")
                            if (ownComment || canAdminDeleteComment) {
                                Text(
                                    text = if (!ownComment && canAdminDeleteComment) "管理删除" else "删除",
                                    style = SeuType.Caption,
                                    color = colors.accent,
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .clickable {
                                            pendingDelete = Triple(
                                                ForumTargetType.Comment,
                                                comment.id,
                                                !ownComment && canAdminDeleteComment,
                                            )
                                        }
                                        .padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 评论输入框贴底（子页面 tab 栏隐藏，所以这里直接到底，底部让给导航栏/键盘）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.groupedBackground)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                OutlinedTextField(
                    value = commentDraft,
                    onValueChange = { commentDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("说点什么…", style = SeuType.Body) },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    ForumChip(
                        label = if (state.isSendingComment) "发送中…" else "发送",
                        selected = true,
                        onClick = {
                            if (!isLoggedIn) {
                                onLoginRequired()
                            } else {
                                store.sendComment(post.id, commentDraft) { commentDraft = "" }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 待上传配图：相册选图后统一转 JPEG（后端只收 jpeg/png/webp，相册常是 HEIC/其他）。 */
private data class PendingComposerImage(
    val bytes: ByteArray,
    val preview: android.graphics.Bitmap,
)

/**
 * 发帖页（豆瓣式专业论坛布局）。
 *
 * 顶栏 = 返回 / 板块选择胶囊（编辑模式隐藏，后端不让改标签）/ 发布胶囊；
 * 标题单行 + 通栏正文，无卡片边框；底部工具栏（图片、#板块）贴键盘上方。
 * [editPostId] 非空时为编辑模式：预填现有标题/正文，提交走 PATCH。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumComposeScreen(
    store: ForumStore,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    onPosted: (String) -> Unit,
    editPostId: String? = null,
) {
    val colors = SeuTheme.colors
    var title by remember(editPostId) { mutableStateOf("") }
    var content by remember(editPostId) { mutableStateOf("") }
    var prefilled by remember(editPostId) { mutableStateOf(editPostId == null) }
    var selectedSlugs by remember(editPostId) { mutableStateOf(listOf<String>()) }
    var pendingImages by remember(editPostId) { mutableStateOf(listOf<PendingComposerImage>()) }
    var showTagSheet by remember(editPostId) { mutableStateOf(false) }

    val context = LocalContext.current
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val remaining = (9 - pendingImages.size).coerceAtLeast(0)
        uris.take(remaining).forEach { uri ->
            compressComposerImage(context, uri)?.let { pendingImages = pendingImages + it }
        }
    }

    // 编辑模式从详情缓存预填 —— 入口就是详情页的「编辑」按钮，detail 已在内存里；
    // 万一没有（深链接进来）就拉一次。
    LaunchedEffect(editPostId) {
        if (editPostId != null && store.detail.post?.id != editPostId) {
            store.loadDetail(editPostId)
        }
    }
    LaunchedEffect(editPostId, store.detail.post) {
        val post = store.detail.post
        if (editPostId != null && !prefilled && post?.id == editPostId) {
            title = post.title ?: ""
            content = post.content
            prefilled = true
        }
    }

    val canPublish = content.trim().isNotEmpty() && content.trim().length <= 20_000

    val tagPickerTitle = if (selectedSlugs.isEmpty()) {
        "选择板块"
    } else {
        TopicCatalog.topics
            .flatMap { t -> listOf(t.slug to t.name) + t.subtags.map { it.slug to it.name } }
            .filter { selectedSlugs.contains(it.first) }
            .joinToString(" · ") { it.second }
    }

    fun publish() {
        if (!isLoggedIn) {
            onLoginRequired()
            return
        }
        val images = pendingImages.map { it.bytes }
        if (editPostId != null) {
            store.updatePost(editPostId, title, content, images) { onPosted(editPostId) }
        } else {
            store.createPost(title, content, selectedSlugs, images) { onPosted(it) }
        }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：返回 / 板块胶囊 / 发布。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(
                        imageVector = SeuIcons.of("chevron.left"),
                        contentDescription = "取消",
                        tint = colors.label,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (editPostId == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.tertiaryFill)
                                .clickable { showTagSheet = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Icon(
                                imageVector = SeuIcons.of(if (selectedSlugs.isEmpty()) "plus" else "number"),
                                contentDescription = null,
                                tint = colors.label,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                tagPickerTitle,
                                style = SeuType.Subheadline,
                                color = colors.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text("编辑帖子", style = SeuType.Headline, color = colors.label)
                    }
                }
                val publishEnabled = canPublish
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (publishEnabled) colors.accent else colors.tertiaryFill)
                        .clickable(enabled = publishEnabled) { publish() }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    Text(
                        if (editPostId != null) "保存" else "发布",
                        style = SeuType.SubheadlineMedium,
                        color = if (publishEnabled) colors.onAccentInverted else colors.secondaryLabel,
                    )
                }
            }

            // 标题：单行通栏，靠分隔线分区。
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 160) title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("标题（可不填）", style = SeuType.Title3) },
                textStyle = SeuType.Title3.copy(color = colors.label),
                singleLine = true,
                colors = composerFieldColors(),
                shape = RectangleShape,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = colors.separator,
            )

            // 正文：占满剩余空间。
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("此刻你想要分享…", style = SeuType.Body) },
                textStyle = SeuType.Body.copy(color = colors.label),
                colors = composerFieldColors(),
                shape = RectangleShape,
            )

            if (content.length > 20_000) {
                Text(
                    "超出 ${content.length - 20_000} 字（上限 20000 字）",
                    style = SeuType.Footnote,
                    color = colors.red,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            // 待上传配图：可单张移除。
            if (pendingImages.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(pendingImages.size) { index ->
                        val image = pendingImages[index]
                        Box {
                            androidx.compose.foundation.Image(
                                bitmap = image.preview.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            Icon(
                                imageVector = SeuIcons.of("xmark"),
                                contentDescription = "移除图片",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .clickable {
                                        pendingImages = pendingImages.toMutableList()
                                            .also { it.removeAt(index) }
                                    }
                                    .padding(2.dp),
                            )
                        }
                    }
                }
            }

            // 底部工具栏：图片 / #板块。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.groupedBackground)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(
                    onClick = { pickImages.launch("image/*") },
                ) {
                    Icon(
                        imageVector = SeuIcons.of("photo"),
                        contentDescription = "添加图片",
                        tint = colors.label,
                    )
                }
                if (editPostId == null) {
                    androidx.compose.material3.IconButton(onClick = { showTagSheet = true }) {
                        Icon(
                            imageVector = SeuIcons.of("number"),
                            contentDescription = "选择板块",
                            tint = colors.label,
                        )
                    }
                }
            }
        }
    }

    if (showTagSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showTagSheet = false },
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "选择板块（最多 3 个）",
                    style = SeuType.Headline,
                    color = colors.label,
                )
                VSpace(12.dp)
                TopicCatalog.topics.forEach { topic ->
                    Text(topic.name, style = SeuType.SubheadlineMedium, color = colors.secondaryLabel)
                    VSpace(8.dp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tags = listOf(topic.slug to topic.name) +
                            topic.subtags.map { it.slug to it.name }
                        tags.forEach { (slug, name) ->
                            val selected = selectedSlugs.contains(slug)
                            ForumChip(
                                label = name,
                                selected = selected,
                                onClick = {
                                    selectedSlugs = if (selected) {
                                        selectedSlugs - slug
                                    } else if (selectedSlugs.size < 3) {
                                        selectedSlugs + slug
                                    } else {
                                        selectedSlugs
                                    }
                                },
                            )
                        }
                    }
                    VSpace(16.dp)
                }
                VSpace(24.dp)
            }
        }
    }
}

/** 无边框透明底输入框配色（标题/正文共用）。 */
@Composable
private fun composerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = SeuTheme.colors.accent,
)

/**
 * 相册图片 → JPEG 字节：先缩到长边 2048，再逐级降质直到 ≤5MB
 * （后端 `POST_IMAGE_MAX_BYTES`）；转换失败返回 null 直接跳过。
 */
private fun compressComposerImage(
    context: android.content.Context,
    uri: android.net.Uri,
    maxBytes: Int = 5 * 1024 * 1024,
): PendingComposerImage? {
    val bitmap = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
    }.getOrNull() ?: return null
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest <= 2048) {
        bitmap
    } else {
        val scale = 2048f / longest
        android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
    for (quality in listOf(85, 70, 55, 40)) {
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() <= maxBytes) return PendingComposerImage(out.toByteArray(), scaled)
    }
    return null
}

/** 统一的大图标。 */
@Composable
private fun ForumGlyph(icon: String, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(SeuIcons.of(icon), contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
    }
}

/**
 * 帖子配图。
 *
 * `asset_url` 是 `/api/media/...` 相对路径，必须拼 `https://forum.seu.wiki` 才能加载
 * （服务端刻意返回相对路径，见 `src/lib/media/security.mjs:154-159`）。
 * **不能**在客户端硬编码域名之外的东西 —— 用 [ForumApiClient.absoluteUrl] 那一套。
 *
 * `asset_url` 为空/空白时不渲染任何东西 —— 给 AsyncImage 塞个空地址会渲染出一块破图。
 * 真正的加载失败（网络问题、对象已删）由 [coil.compose.AsyncImage] 自己处理：图片区
 * 留一个 96dp 的灰底占位，不弹错误、不打断正文阅读。
 */
@Composable
private fun PostImageStrip(images: List<tech.iflink.seuwiki.models.ForumImage>) {
    val colors = SeuTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.take(3).forEach { img ->
            val url = ForumApiClient.absoluteUrl(img.assetUrl)
            if (url != null) {
                coil.compose.AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.tertiaryFill),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        if (images.size > 3) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(colors.tertiaryFill),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${images.size - 3}", style = SeuType.Caption, color = colors.secondaryLabel)
            }
        }
    }
}

/**
 * 我的论坛收藏。
 *
 * 与个人页那个「我的收藏」是**两回事**：那边存的是手册/经验条目的本地 slug，
 * 这边是论坛帖的服务器端收藏（`GET /api/bookmarks`，需登录）。
 */
@Composable
fun ForumBookmarksScreen(
    store: ForumStore,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onGoLogin: () -> Unit,
    onOpenTopic: ((String) -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    val state = store.bookmarks
    LaunchedEffect(Unit) { store.refreshBookmarks() }
    // 登录成功后回到本页：状态从「需登录」翻过来时自动重拉一次，
    // 否则用户登录完看到的还是那行「登录后才能进行这个操作」。
    LaunchedEffect(state.needsLogin) {
        if (!state.needsLogin) store.refreshBookmarks()
    }
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 2
        }
    }
    LaunchedEffect(shouldLoadMore, state.nextCursor) {
        if (shouldLoadMore && state.nextCursor != null) store.loadMoreBookmarks()
    }

    // 显式标注类型：`if/else` 两支分别是 lambda 和 null，不写出来编译器
    // 推不出 `@Composable`，下一行的 Button 就报「只能在 @Composable 里调用」。
    val loginAction: (@Composable () -> Unit)? = if (state.needsLogin) {
        {
            Button(
                onClick = onGoLogin,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("用 IF.Link 账号登录") }
        }
    } else {
        null
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "我的收藏", onBack = onBack)
            when {
                state.isLoading && !state.hasLoaded -> LoadingView(topPadding = 40.dp)
                state.items.isEmpty() -> EmptyStateView(
                    title = when {
                        state.needsLogin -> "登录后查看收藏"
                        state.isOffline -> "加载失败"
                        else -> "还没有收藏"
                    },
                    description = state.errorMessage ?: "在帖子上点「收藏」就会出现在这里。",
                    icon = { ForumGlyph(icon = "bookmark", tint = colors.tertiaryLabel) },
                    topPadding = 40.dp,
                    action = loginAction,
                )
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.bookmarkId }) { entry ->
                        ForumPostRow(
                            post = entry.post,
                            onClick = { onOpenPost(entry.post.id) },
                            onOpenTopic = onOpenTopic,
                        )
                    }
                }
            }
        }
    }
}
