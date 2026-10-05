package tech.iflink.seuwiki.ui.search

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
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
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SeuColorScheme
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.DocKind
import tech.iflink.seuwiki.models.DocRef
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage

/** 搜索信源 — 全部, or one of the three aggregatable sources. */
enum class SearchScope(val label: String, val symbol: String) {
    All("全部", "magnifyingglass"),
    Feed("通知", "newspaper"),
    Forum("经验", "bubble.left.and.text.bubble.right"),
    Handbook("手册", "book.closed");

    /** Copy for the 「搜索范围」 explainer card on the empty state. */
    val detail: String
        get() = when (this) {
            All -> ""
            Feed -> "教务、奖助、竞赛、招聘等校园资讯"
            Forum -> "保研、考研、留学、实习等经验帖"
            Handbook -> "入学、选课、奖助、生活等手册条目"
        }

    /** The aggregated sources, in the order the sections are stacked. */
    val sourceScopes: List<SearchScope>
        get() = when (this) {
            All -> listOf(Feed, Forum, Handbook)
            else -> listOf(this)
        }
}

/**
 * 一次查询的三个结果桶。
 *
 * 三信源映射（两端必须一致）：资讯卡 = pool 的 `items`；
 * 经验卡 = `docs.filter{ kind == experience }`；手册卡 = `docs.filter{ kind == survival }`。
 * 原来这里的经验桶是 `MockData.forumPosts`（虚构用户与虚构点赞数），已删除。
 */
class SearchResults(
    val keyword: String,
    val feed: List<FeedItem>,
    val experience: List<DocRef>,
    val handbook: List<DocRef>,
) {
    val isEmpty: Boolean get() = feed.isEmpty() && experience.isEmpty() && handbook.isEmpty()
}

/**
 * 搜索.
 *
 * 三个信源现在**全部来自后端**：`GET /api/site/pool` 一次响应里同时给
 * 资讯 `items` 与手册/经验 `docs`，客户端只做 kind 的映射与拆分，不再本地匹配。
 *
 * 匹配与高亮仍沿用 SwiftUI `SearchEngine` 的 containment + 加粗强调。
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    docs: DocsStore,
    onOpenProfile: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenSourceList: (SearchScope, String) -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var scopeKey by rememberSaveable { mutableStateOf(SearchScope.All.name) }
    val scope = SearchScope.entries.firstOrNull { it.name == scopeKey } ?: SearchScope.All
    val keyboard = LocalSoftwareKeyboardController.current
    val searchScope = rememberCoroutineScope()

    val result = docs.searchResult
    val results = SearchResults(
        keyword = result.query,
        feed = result.feed,
        experience = result.experience,
        handbook = result.handbook,
    )

    // 300ms 防抖 + collectLatest：原来每个按键都直接发一次请求，
    // 一边打字一边把 `/api/site/pool` 打满。collectLatest 会在新关键词到达时
    // 取消上一个在途请求 —— 这也是 S-1 那个 bug 的引爆点，所以取消路径必须
    // 一路保持 CancellationException、不被吞。
    LaunchedEffect(docs) {
        snapshotFlow { keyword.trim() }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest { q ->
                if (q.isEmpty()) docs.clearSearch() else docs.search(q)
            }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "搜索", onProfileClick = onOpenProfile)
            SearchField(
                keyword = keyword,
                onKeywordChange = { keyword = it },
                onSubmit = { keyboard?.hide() },
            )
            ConsoleBar(
                items = SearchScope.entries.toList(),
                selection = scope,
                onSelect = { scopeKey = it.name },
                title = { it.label },
            )

            when {
                keyword.isBlank() -> SearchSuggestions(
                    onSelectWord = { keyword = it },
                    modifier = Modifier.fillMaxSize(),
                )

                // 失败与「搜不到」必须分开：前者给可重试的错误态，后者给空态。
                // 之前两者都静默回退到本地假数据，用户根本分不清是网断了还是没命中。
                result.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = "搜索失败",
                        description = "${result.error}\n检查网络后再试一次。",
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
                        text = "重试",
                        style = SeuType.SubheadlineSemibold,
                        color = SeuTheme.colors.accent,
                        modifier = Modifier
                            .padding(top = 260.dp)
                            .clip(CircleShape)
                            .clickable { searchScope.launch { docs.search(keyword.trim()) } }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }

                result.loading && result.total == 0 -> LoadingView(topPadding = 80.dp)

                // 「搜过、没报错、0 命中」——这是真正的空结果。
                results.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = "未找到“$keyword”",
                        description = "换个关键词试试，试试「保研」「转专业」这类词。",
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
                    results = results,
                    keyword = keyword,
                    onOpenFeed = onOpenFeed,
                    onOpenEntry = onOpenEntry,
                    onOpenSourceList = onOpenSourceList,
                )

                else -> SourceList(
                    scope = scope,
                    results = results,
                    keyword = keyword,
                    onOpenFeed = onOpenFeed,
                    onOpenEntry = onOpenEntry,
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
                    text = "搜索通知、经验、手册",
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
    val hotWords = listOf(
        "保研", "SRTP", "奖学金", "转专业", "数学建模", "秋招",
        "交换", "选课", "图书馆", "食堂", "班车", "校园卡",
    )
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            CardColumn(spacing = 12.dp) {
                ExplainLabel("热门搜索", "flame.fill", colors.orange)
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
                ExplainLabel("搜索范围", "square.stack.3d.up.fill", colors.accent)
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
                            Text(source.label, style = SeuType.SubheadlineMedium, color = colors.label)
                            Text(
                                text = source.detail,
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
    results: SearchResults,
    keyword: String,
    onOpenFeed: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenSourceList: (SearchScope, String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (results.feed.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Feed,
                    count = results.feed.size,
                    onMore = { onOpenSourceList(SearchScope.Feed, keyword) },
                ) {
                    results.feed.take(3).forEachIndexed { index, item ->
                        SearchFeedResultRow(item, keyword) { onOpenFeed(item.id) }
                        if (index < minOf(3, results.feed.size) - 1) InsetDivider()
                    }
                }
            }
        }
        if (results.experience.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Forum,
                    count = results.experience.size,
                    onMore = { onOpenSourceList(SearchScope.Forum, keyword) },
                ) {
                    results.experience.take(3).forEachIndexed { index, doc ->
                        SearchDocResultRow(doc, DocKind.Experience, keyword) { onOpenEntry(doc.slug) }
                        if (index < minOf(3, results.experience.size) - 1) InsetDivider()
                    }
                }
            }
        }
        if (results.handbook.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Handbook,
                    count = results.handbook.size,
                    onMore = { onOpenSourceList(SearchScope.Handbook, keyword) },
                ) {
                    results.handbook.take(3).forEachIndexed { index, doc ->
                        SearchDocResultRow(doc, DocKind.Survival, keyword) { onOpenEntry(doc.slug) }
                        if (index < minOf(3, results.handbook.size) - 1) InsetDivider()
                    }
                }
            }
        }
    }
}

/**
 * 单信源结果列表（选中「通知」/「经验」/「手册」之后）。
 *
 * **每一行都必须可点并跳到对应详情**（A-1）。原实现里 `CardColumn` 没挂
 * `onClick`，传进来的 `onOpenFeed` / `onOpenPost` / `onOpenHandbookEntry`
 * 三个回调**一个都没被用上** —— 表现为「搜索结果点不动」：列表有内容、
 * 看着像能进，点了毫无反应。
 *
 * 现在三路各自接上自己的回调，并补上关键词高亮（原来这里也没高亮）。
 */
@Composable
private fun SourceList(
    scope: SearchScope,
    results: SearchResults,
    keyword: String,
    onOpenFeed: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (scope) {
            SearchScope.Feed -> items(results.feed, key = { it.id }) { item ->
                SearchFeedResultRow(item, keyword) { onOpenFeed(item.id) }
            }

            SearchScope.Forum -> items(results.experience, key = { it.slug }) { doc ->
                SearchDocResultRow(doc, DocKind.Experience, keyword) { onOpenEntry(doc.slug) }
            }

            SearchScope.Handbook -> items(results.handbook, key = { it.slug }) { doc ->
                SearchDocResultRow(doc, DocKind.Survival, keyword) { onOpenEntry(doc.slug) }
            }

            SearchScope.All -> Unit
        }
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
            Text(scope.label, style = SeuType.Headline, color = colors.label)
            Spacer(Modifier.weight(1f))
            Text(
                text = "$count 条结果",
                style = SeuType.Caption,
                color = colors.secondaryLabel,
            )
        }
        InsetDivider(leading = 14.dp)
        content()
        InsetDivider(leading = 14.dp)
        Text(
            text = "查看更多",
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
 * 手册 / 经验结果行。两者字段一致，只有图标与配色不同。
 *
 * 命中正文小节时（`pool` 的 `docs[].anchor` 非空）在标题下补一行锚点提示，
 * 让用户知道点进去会落到哪一节，而不是整篇从头看。
 */
@Composable
private fun SearchDocResultRow(
    doc: DocRef,
    kind: DocKind,
    keyword: String,
    onClick: () -> Unit,
) {
    val colors = SeuTheme.colors
    val tint = if (kind == DocKind.Experience) colors.orange else colors.green
    val symbol = if (kind == DocKind.Experience) {
        "bubble.left.and.text.bubble.right.fill"
    } else {
        "book.closed.fill"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardStyle(padding = 14.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconWell(tint = tint) {
            Icon(
                imageVector = SeuIcons.of(symbol),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = highlightMatches(doc.title, keyword),
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            doc.anchor?.let {
                Text(
                    text = "命中本节：${highlightMatches(it.text.trim(), keyword)}",
                    style = SeuType.Caption,
                    color = colors.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            doc.description?.let {
                Text(
                    text = highlightMatches(it, keyword),
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
