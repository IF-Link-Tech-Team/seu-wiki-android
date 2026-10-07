package tech.iflink.seuwiki.ui.search

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.data.SearchStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SeuColorScheme
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ForumSearchArticle
import tech.iflink.seuwiki.models.ForumSearchPost
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage

/**
 * 搜索信源 — 全部, or one of the three aggregatable sources.
 *
 * [key] 是稳定标识符：它进路由（`search/list/{scope}/{keyword}`），必须稳定且与
 * 语言无关。[labelRes] / [detailRes] 只是显示用，可以翻译。
 *
 * 信源映射（与 iOS `SearchScope` 一致）：
 * - 通知 = `GET seu.wiki/api/site/pool?type=feed`
 * - 经验 = 论坛搜索的**帖子**桶（`GET forum.seu.wiki/api/search?type=all` 的 posts）
 * - 手册 = 论坛搜索的**手册文章**桶（同一响应的 articles）
 */
enum class SearchScope(val key: String, @StringRes val labelRes: Int, val symbol: String) {
    All("all", R.string.search_scope_all, "magnifyingglass"),
    Feed("feed", R.string.search_scope_feed, "newspaper"),
    Forum("forum", R.string.search_scope_forum, "bubble.left.and.text.bubble.right"),
    Handbook("handbook", R.string.search_scope_handbook, "book.closed");

    /** Copy for the 「搜索范围」 explainer card on the empty state. */
    @get:StringRes
    val detailRes: Int
        get() = when (this) {
            All -> 0
            Feed -> R.string.search_scope_feed_desc
            Forum -> R.string.search_scope_forum_desc
            Handbook -> R.string.search_scope_handbook_desc
        }

    /** The aggregated sources, in the order the sections are stacked. */
    val sourceScopes: List<SearchScope>
        get() = when (this) {
            All -> listOf(Feed, Forum, Handbook)
            else -> listOf(this)
        }

    companion object {
        fun fromKey(key: String?): SearchScope? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 搜索。
 *
 * 信源全部来自后端真实接口，两组**并发、各自成败**（见 [SearchStore]）：
 * 论坛不可达时通知搜索照常出结果，反之亦然。空关键词不发请求
 * （论坛搜索对空 q 直接 400 `EMPTY_QUERY`）。
 *
 * 匹配与高亮沿用 SwiftUI `SearchEngine` 的 containment + 加粗强调。
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    store: SearchStore,
    onOpenProfile: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenArticle: (id: String, title: String) -> Unit,
    onOpenSourceList: (SearchScope, String) -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    var scopeKey by rememberSaveable { mutableStateOf(SearchScope.All.name) }
    val scope = SearchScope.entries.firstOrNull { it.name == scopeKey } ?: SearchScope.All
    val keyboard = LocalSoftwareKeyboardController.current
    val searchScope = rememberCoroutineScope()

    val result = store.state

    // 300ms 防抖 + collectLatest：新关键词到达时取消上一个在途请求。
    LaunchedEffect(store) {
        snapshotFlow { keyword.trim() }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest { q ->
                if (q.isEmpty()) store.clear() else store.search(q)
            }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.search_title), onProfileClick = onOpenProfile)
            SearchField(
                keyword = keyword,
                onKeywordChange = { keyword = it },
                onSubmit = { keyboard?.hide() },
            )
            ConsoleBar(
                items = SearchScope.entries.toList(),
                selection = scope,
                onSelect = { scopeKey = it.name },
                title = { stringResource(it.labelRes) },
            )

            when {
                keyword.isBlank() -> SearchSuggestions(
                    onSelectWord = { keyword = it },
                    modifier = Modifier.fillMaxSize(),
                )

                // 两组信源全挂才算整体失败；只挂一组时另一组照常显示。
                result.allFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = stringResource(R.string.search_failed),
                        description = stringResource(
                            R.string.search_failed_desc,
                            result.forumError?.format(context)
                                ?: result.feedError?.format(context).orEmpty(),
                        ),
                        icon = {
                            Icon(
                                imageVector = SeuIcons.Search,
                                contentDescription = null,
                                tint = SeuTheme.colors.tertiaryLabel,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                    )
                    Text(
                        text = stringResource(R.string.search_retry),
                        style = SeuType.SubheadlineSemibold,
                        color = SeuTheme.colors.accent,
                        modifier = Modifier
                            .padding(top = 260.dp)
                            .clip(CircleShape)
                            .clickable { searchScope.launch { store.search(keyword.trim()) } }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }

                result.loading && result.total == 0 -> LoadingView(topPadding = 80.dp)

                // 「搜过、没报错、0 命中」——这是真正的空结果。
                result.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = stringResource(R.string.search_not_found, keyword),
                        description = stringResource(R.string.search_not_found_desc),
                        icon = {
                            Icon(
                                imageVector = SeuIcons.Search,
                                contentDescription = null,
                                tint = SeuTheme.colors.tertiaryLabel,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                    )
                }

                scope == SearchScope.All -> AggregatedResults(
                    result = result,
                    keyword = keyword,
                    onOpenFeed = onOpenFeed,
                    onOpenPost = onOpenPost,
                    onOpenArticle = onOpenArticle,
                    onOpenSourceList = onOpenSourceList,
                    onRetry = { searchScope.launch { store.search(keyword.trim()) } },
                )

                else -> SourceList(
                    scope = scope,
                    result = result,
                    keyword = keyword,
                    onOpenFeed = onOpenFeed,
                    onOpenPost = onOpenPost,
                    onOpenArticle = onOpenArticle,
                    onRetry = { searchScope.launch { store.search(keyword.trim()) } },
                )
            }
        }
    }
}

// --- query -----------------------------------------------------------------

/**
 * Bolds every occurrence of [keyword] in [text] — the `String.highlighting(_:)`
 * helper, expressed as a styled `AnnotatedString`.
 *
 * Public because the pushed single-source result page needs the same emphasis
 * as the inline rows; both render the Swift `SearchResultRows` treatment.
 */
@Composable
fun highlightMatches(text: String, keyword: String): AnnotatedString {
    val key = keyword.trim()
    if (key.isEmpty()) return buildAnnotatedString { append(text) }
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val hit = text.indexOf(key, index, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, hit))
            withStyle(bold) { append(text.substring(hit, hit + key.length)) }
            index = hit + key.length
        }
    }
}

// --- chrome ----------------------------------------------------------------

/**
 * The search field.
 *
 * iOS puts `.searchable` in the navigation bar drawer; on Android the large
 * title occupies that row, so the field sits directly beneath it in the same
 * capsule treatment `.searchable` uses.
 */
@Composable
private fun SearchField(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.groupedBackground)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.tertiaryFill)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = SeuIcons.Search,
            contentDescription = null,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(17.dp),
        )
        Box(Modifier.weight(1f)) {
            if (keyword.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = SeuType.Callout,
                    color = colors.secondaryLabel,
                )
            }
            BasicTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    SeuType.Callout.copy(color = colors.label),
                ),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 热门搜索 chips + the explainer card for the three sources. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(onSelectWord: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    val hotWords = stringArrayResource(R.array.search_hot_keywords)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            CardColumn(spacing = 12.dp) {
                ExplainLabel(stringResource(R.string.search_hot_label), "flame.fill", colors.orange)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hotWords.forEach { word ->
                        Text(
                            text = word,
                            style = SeuType.Subheadline,
                            color = colors.label,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.groupedBackground)
                                .clickable { onSelectWord(word) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        item {
            CardColumn(spacing = 12.dp) {
                ExplainLabel(stringResource(R.string.search_scope_label), "square.stack.3d.up.fill", colors.accent)
                listOf(SearchScope.Feed, SearchScope.Forum, SearchScope.Handbook).forEach { source ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = scopeTint(source, colors)
                        IconWell(tint = tint) {
                            Icon(
                                imageVector = SeuIcons.of(source.symbol),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Column {
                            Text(stringResource(source.labelRes), style = SeuType.SubheadlineMedium, color = colors.label)
                            Text(
                                text = if (source.detailRes != 0) stringResource(source.detailRes) else "",
                                style = SeuType.Caption,
                                color = colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplainLabel(text: String, symbol: String, tint: Color) {
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

// --- results ---------------------------------------------------------------

@Composable
private fun AggregatedResults(
    result: SearchStore.State,
    keyword: String,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenArticle: (String, String) -> Unit,
    onOpenSourceList: (SearchScope, String) -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (result.feed.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Feed,
                    count = result.feed.size,
                    onMore = { onOpenSourceList(SearchScope.Feed, keyword) },
                ) {
                    result.feed.take(3).forEachIndexed { index, item ->
                        SearchFeedResultRow(item, keyword) { onOpenFeed(item.id) }
                        if (index < minOf(3, result.feed.size) - 1) InsetDivider()
                    }
                }
            }
        }
        if (result.posts.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Forum,
                    count = result.posts.size,
                    onMore = { onOpenSourceList(SearchScope.Forum, keyword) },
                ) {
                    result.posts.take(3).forEachIndexed { index, post ->
                        SearchForumPostRow(post, keyword) { onOpenPost(post.id) }
                        if (index < minOf(3, result.posts.size) - 1) InsetDivider()
                    }
                }
            }
        }
        if (result.articles.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Handbook,
                    count = result.articles.size,
                    onMore = { onOpenSourceList(SearchScope.Handbook, keyword) },
                ) {
                    result.articles.take(3).forEachIndexed { index, article ->
                        SearchArticleRow(article, keyword) { onOpenArticle(article.id, article.title) }
                        if (index < minOf(3, result.articles.size) - 1) InsetDivider()
                    }
                }
            }
        }
        // 单组失败：该组没有可显示的结果时，给一张如实说明的小卡而不是装作没搜这一组。
        if (result.feedError != null && result.feed.isEmpty()) {
            item { SearchGroupErrorCard(SearchScope.Feed, result.feedError.format(LocalContext.current), onRetry) }
        }
        if (result.forumError != null && result.posts.isEmpty() && result.articles.isEmpty()) {
            item { SearchGroupErrorCard(SearchScope.Forum, result.forumError.format(LocalContext.current), onRetry) }
        }
    }
}

/**
 * 单信源结果列表（选中「通知」/「经验」/「手册」之后）。
 *
 * **每一行都必须可点并跳到对应详情**：帖子行进论坛帖子详情，手册文章行进
 * 手册文章详情，通知行进资讯详情。
 */
@Composable
private fun SourceList(
    scope: SearchScope,
    result: SearchStore.State,
    keyword: String,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenArticle: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (scope) {
            SearchScope.Feed -> {
                items(result.feed, key = { it.id }) { item ->
                    SearchFeedResultRow(item, keyword) { onOpenFeed(item.id) }
                }
                if (result.feedError != null && result.feed.isEmpty()) {
                    item { SearchGroupErrorCard(scope, result.feedError.format(context), onRetry) }
                }
            }

            SearchScope.Forum -> {
                items(result.posts, key = { it.id }) { post ->
                    SearchForumPostRow(post, keyword) { onOpenPost(post.id) }
                }
                if (result.forumError != null && result.posts.isEmpty()) {
                    item { SearchGroupErrorCard(scope, result.forumError.format(context), onRetry) }
                }
            }

            SearchScope.Handbook -> {
                items(result.articles, key = { it.id }) { article ->
                    SearchArticleRow(article, keyword) { onOpenArticle(article.id, article.title) }
                }
                if (result.forumError != null && result.articles.isEmpty()) {
                    item { SearchGroupErrorCard(scope, result.forumError.format(context), onRetry) }
                }
            }

            SearchScope.All -> Unit
        }
    }
}

/** 某一组信源失败时的如实说明卡（另一组的结果不受影响）。 */
@Composable
private fun SearchGroupErrorCard(scope: SearchScope, message: String, onRetry: () -> Unit) {
    val colors = SeuTheme.colors
    Column(Modifier.cardStyle(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = SeuIcons.of(scope.symbol),
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(scope.labelRes),
                style = SeuType.SubheadlineSemibold,
                color = colors.secondaryLabel,
            )
        }
        Text(message, style = SeuType.Caption, color = colors.tertiaryLabel)
        Text(
            text = stringResource(R.string.search_retry),
            style = SeuType.SubheadlineSemibold,
            color = colors.accent,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SearchSectionCard(
    scope: SearchScope,
    count: Int,
    onMore: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = SeuTheme.colors
    val tint = scopeTint(scope, colors)
    Column(Modifier.cardStyle(padding = 0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconWell(tint = tint, size = 30.dp) {
                Icon(
                    imageVector = SeuIcons.of(scope.symbol),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(stringResource(scope.labelRes), style = SeuType.Headline, color = colors.label)
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.search_result_count, count),
                style = SeuType.Caption,
                color = colors.secondaryLabel,
            )
        }
        InsetDivider(leading = 14.dp)
        content()
        InsetDivider(leading = 14.dp)
        Text(
            text = stringResource(R.string.view_all),
            style = SeuType.SubheadlineMedium,
            color = colors.accent,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onMore)
                .padding(vertical = 11.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun scopeTint(scope: SearchScope, colors: SeuColorScheme): Color = when (scope) {
    SearchScope.All, SearchScope.Feed -> colors.accent
    SearchScope.Forum -> colors.orange
    SearchScope.Handbook -> colors.green
}

// --- result rows -----------------------------------------------------------

@Composable
private fun SearchFeedResultRow(item: FeedItem, keyword: String, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(14.dp),
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
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = highlightMatches(item.title, keyword),
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.sourceName} · ${item.summary}",
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 论坛搜索的帖子结果行。
 *
 * 搜索接口给的是 snippet 投影（没有 content / images / tags，见
 * [ForumSearchPost]），标题可能为 null（无标题帖），这时拿 snippet 当主行。
 */
@Composable
private fun SearchForumPostRow(post: ForumSearchPost, keyword: String, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.cardStyle(padding = 14.dp, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconWell(tint = colors.orange) {
            Icon(
                imageVector = SeuIcons.of("bubble.left.and.text.bubble.right"),
                contentDescription = null,
                tint = colors.orange,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            post.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = highlightMatches(it, keyword),
                    style = SeuType.SubheadlineMedium,
                    color = colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (post.snippet.isNotBlank()) {
                Text(
                    text = highlightMatches(post.snippet, keyword),
                    style = if (post.title.isNullOrBlank()) SeuType.SubheadlineMedium else SeuType.Caption,
                    color = if (post.title.isNullOrBlank()) colors.label else colors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(
                    post.author?.nameOrFallback,
                    "${post.likesCount} 赞",
                    "${post.commentsCount} 评论",
                    "${post.viewsCount} 浏览",
                    post.createdAt?.let { Format.relative(context, it) }?.takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = SeuType.Caption,
                color = colors.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 论坛搜索的手册文章结果行。点进去是手册文章详情（`content_html` 渲染），
 * 不是旧文档树的条目。
 */
@Composable
private fun SearchArticleRow(article: ForumSearchArticle, keyword: String, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.cardStyle(padding = 14.dp, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconWell(tint = colors.green) {
            Icon(
                imageVector = SeuIcons.of("books.vertical.fill"),
                contentDescription = null,
                tint = colors.green,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = highlightMatches(article.title, keyword),
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.snippet.isNotBlank()) {
                Text(
                    text = highlightMatches(article.snippet, keyword),
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(
                    TopicCatalog.nameForSlug(article.tagSlug),
                    article.publishedAt?.let { Format.relative(context, it) }?.takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = SeuType.Caption,
                color = colors.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
