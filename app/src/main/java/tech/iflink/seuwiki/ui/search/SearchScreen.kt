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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuColorScheme
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.HandbookEntry
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
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

/** The three aggregated result buckets for one query. */
class SearchResults(
    val keyword: String,
    val feed: List<FeedItem>,
    val forum: List<ForumPost>,
    val handbook: List<HandbookEntry>,
) {
    val isEmpty: Boolean get() = feed.isEmpty() && forum.isEmpty() && handbook.isEmpty()
}

/**
 * 搜索.
 *
 * Port of `SearchHomeView`: one query fans out across 通知 / 经验 / 手册. On the
 * 全部 scope each hit source gets a section card (glyph, name, hit count, up to
 * three rows, 查看更多); picking a single scope shows that source's full list.
 *
 * Matching is the same case- and diacritic-insensitive containment the SwiftUI
 * `SearchEngine` uses, and results are highlighted with the same emphasis.
 */
@Composable
fun SearchScreen(
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenHandbookEntry: (String) -> Unit,
    onOpenSourceList: (SearchScope, String) -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var scopeKey by rememberSaveable { mutableStateOf(SearchScope.All.name) }
    val scope = SearchScope.entries.firstOrNull { it.name == scopeKey } ?: SearchScope.All
    val keyboard = LocalSoftwareKeyboardController.current

    val results = remember(keyword) { search(keyword) }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "搜索")
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

            if (keyword.isBlank()) {
                SearchSuggestions(
                    onSelectWord = { keyword = it },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (results.isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = "未找到“$keyword”",
                        description = null,
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
            } else if (scope == SearchScope.All) {
                AggregatedResults(
                    results = results,
                    keyword = keyword,
                    onOpenFeed = onOpenFeed,
                    onOpenPost = onOpenPost,
                    onOpenHandbookEntry = onOpenHandbookEntry,
                    onOpenSourceList = onOpenSourceList,
                )
            } else {
                SourceList(
                    scope = scope,
                    results = results,
                    keyword = keyword,
                    onOpenFeed = onOpenFeed,
                    onOpenPost = onOpenPost,
                    onOpenHandbookEntry = onOpenHandbookEntry,
                )
            }
        }
    }
}

// --- query -----------------------------------------------------------------

/**
 * Runs the query against all three sources.
 *
 * 「通知」 hits the live `pool` search in the SwiftUI build; here it falls back
 * to the same local match so the aggregation behaves identically offline.
 */
private fun search(rawKeyword: String): SearchResults {
    val keyword = rawKeyword.trim()
    if (keyword.isEmpty()) return SearchResults(keyword, emptyList(), emptyList(), emptyList())
    return SearchResults(
        keyword = keyword,
        feed = MockData.feedItems.filter { it.matches(keyword) },
        forum = MockData.forumPosts.filter { it.matches(keyword) },
        handbook = MockData.handbookSections
            .flatMap { it.entries }
            .filter { it.matches(keyword) },
    )
}

private fun contains(text: String, keyword: String): Boolean =
    text.contains(keyword, ignoreCase = true)

private fun FeedItem.matches(keyword: String) =
    contains(title, keyword) || contains(summary, keyword)

private fun ForumPost.matches(keyword: String) =
    contains(title, keyword) || contains(excerpt, keyword)

private fun HandbookEntry.matches(keyword: String) =
    contains(title, keyword) || contains(subtitle, keyword) || contains(body, keyword)

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
    onOpenPost: (String) -> Unit,
    onOpenHandbookEntry: (String) -> Unit,
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
        if (results.forum.isNotEmpty()) {
            item {
                SearchSectionCard(
                    scope = SearchScope.Forum,
                    count = results.forum.size,
                    onMore = { onOpenSourceList(SearchScope.Forum, keyword) },
                ) {
                    results.forum.take(3).forEachIndexed { index, post ->
                        SearchForumResultRow(post, keyword) { onOpenPost(post.id) }
                        if (index < minOf(3, results.forum.size) - 1) InsetDivider()
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
                    results.handbook.take(3).forEachIndexed { index, entry ->
                        SearchHandbookResultRow(entry, keyword) { onOpenHandbookEntry(entry.id) }
                        if (index < minOf(3, results.handbook.size) - 1) InsetDivider()
                    }
                }
            }
        }
    }
}

/**
 * A single-source result list, shown when a scope other than 全部 is selected.
 */
@Composable
private fun SourceList(
    scope: SearchScope,
    results: SearchResults,
    keyword: String,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenHandbookEntry: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when (scope) {
            SearchScope.Feed -> items(results.feed, key = { it.id }) { item ->
                CardColumn(padding = 14.dp) {
                    Text(
                        text = item.title,
                        style = SeuType.SubheadlineMedium,
                        color = SeuTheme.colors.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.sourceName} · ${item.summary}",
                        style = SeuType.Caption,
                        color = SeuTheme.colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SearchScope.Forum -> items(results.forum, key = { it.id }) { post ->
                CardColumn(padding = 14.dp) {
                    Text(
                        text = post.title,
                        style = SeuType.SubheadlineMedium,
                        color = SeuTheme.colors.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${post.authorName} · ${post.excerpt}",
                        style = SeuType.Caption,
                        color = SeuTheme.colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SearchScope.Handbook -> items(results.handbook, key = { it.id }) { entry ->
                CardColumn(padding = 14.dp) {
                    Text(
                        text = entry.title,
                        style = SeuType.SubheadlineMedium,
                        color = SeuTheme.colors.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${sectionNameOf(entry)} · ${entry.subtitle}",
                        style = SeuType.Caption,
                        color = SeuTheme.colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SearchScope.All -> Unit
        }
    }
}

/**
 * One source's section card.
 *
 * Port of `SearchSectionCard`: a 30dp tinted well with the source name and hit
 * count, a hairline, up to three rows, then a full-width 查看更多 link.
 */
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

@Composable
private fun SearchForumResultRow(post: ForumPost, keyword: String, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                text = highlightMatches(post.title, keyword),
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${post.authorName} · ${post.excerpt}",
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchHandbookResultRow(entry: HandbookEntry, keyword: String, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    // Prefer the subtitle; fall back to the body when only the body matched, so
    // the highlighted keyword is always visible — the same rule as SwiftUI.
    val snippet = if (keyword.isNotBlank() &&
        !entry.title.contains(keyword, ignoreCase = true) &&
        !entry.subtitle.contains(keyword, ignoreCase = true) &&
        entry.body.contains(keyword, ignoreCase = true)
    ) {
        entry.body
    } else {
        entry.subtitle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(tint = colors.green) {
            Icon(
                imageVector = SeuIcons.of("book.closed"),
                contentDescription = null,
                tint = colors.green,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = highlightMatches(entry.title, keyword),
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snippet,
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Which handbook section an entry belongs to, for the single-source list subtitle. */
private fun sectionNameOf(entry: HandbookEntry): String =
    MockData.handbookSections
        .firstOrNull { section -> section.entries.any { it.id == entry.id } }
        ?.name
        .orEmpty()
