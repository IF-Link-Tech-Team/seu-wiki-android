package tech.iflink.seuwiki.ui.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.ForumApiClient
import tech.iflink.seuwiki.data.ForumStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.TintPill
import tech.iflink.seuwiki.design.VSpace
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
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
                else -> LazyColumn(
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
        ComposeFab(onCompose = onCompose)
    }
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

/** 「最新 / 热门」切换。排序值直接对应后端 `sort` 参数，写错会被判 `INVALID_SORT`。 */
@Composable
private fun SortToggle(current: ForumSort, onChange: (ForumSort) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ForumSort.entries.forEach { option ->
            val selected = option == current
            ForumChip(
                label = if (option == ForumSort.Latest) "最新" else "热门",
                selected = selected,
                onClick = { if (!selected) onChange(option) },
            )
        }
    }
}

/**
 * 一行帖子。
 *
 * 刻意**不显示点赞数** —— 服务端没有"我是否已点赞"的只读接口，界面上一个红心加
 * 数字会暗示"这个赞是你按的"，而那只有本地才准。要显示互动数就显示服务端的
 * `comments_count`（真实），点赞态只在详情页给。
 *
 * [onOpenTopic] 非空时标签可点 —— 这是话题页的入口。`Routes.topicDetail` 之前只定义
 * 没人调用，整个话题页没有入口。
 */
@Composable
fun ForumPostRow(post: ForumPost, onClick: () -> Unit, onOpenTopic: ((String) -> Unit)? = null) {
    val colors = SeuTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardStyle()
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
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
            Spacer(Modifier.weight(1f))
            if (post.commentsCount > 0) {
                Text(
                    text = "${post.commentsCount} 条回复",
                    style = SeuType.Caption,
                    color = colors.tertiaryLabel,
                )
            }
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

/** 帖子详情 + 评论。 */
@Composable
fun ForumPostDetailScreen(
    store: ForumStore,
    postId: String,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
) {
    val colors = SeuTheme.colors
    val state = store.detail
    var commentDraft by remember(postId) { mutableStateOf("") }

    LaunchedEffect(postId) { store.loadDetail(postId) }

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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ForumChip(
                                label = if (post.likedByMe) "已赞" else "赞 ${post.likesCount}",
                                selected = post.likedByMe,
                                onClick = {
                                    if (store.viewer == null) onLoginRequired() else store.toggleLike(post.id)
                                },
                            )
                            ForumChip(
                                label = if (post.bookmarked) "已收藏" else "收藏",
                                selected = post.bookmarked,
                                onClick = {
                                    if (store.viewer == null) onLoginRequired() else store.toggleBookmark(post.id)
                                },
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
                            if (store.viewer == null) {
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

/** 发帖页。 */
@Composable
fun ForumComposeScreen(
    store: ForumStore,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    onPosted: (String) -> Unit,
) {
    val colors = SeuTheme.colors
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "发帖", onBack = onBack)
            CardColumn(Modifier.weight(1f).padding(16.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("标题（可不填）", style = SeuType.Body) },
                    singleLine = true,
                )
                VSpace(12.dp)
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("说点什么…", style = SeuType.Body) },
                    maxLines = 12,
                )
            }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                ForumChip(
                    label = "发布",
                    selected = true,
                    onClick = {
                        if (store.viewer == null) onLoginRequired()
                        else store.createPost(title, content, emptyList()) { onPosted(it) }
                    },
                )
            }
        }
    }
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
