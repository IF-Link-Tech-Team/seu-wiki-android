package tech.iflink.seuwiki.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.search.highlightMatches

/**
 * 与我有关 · 完整列表.
 *
 * Port of `HomeFeedListView`: the 「查看全部」 destination for the Home feed
 * section, a plain list of the notices in a two-line form. This is the compact
 * `List` treatment, not the richer `HomeFeedRow` card used on the Home page.
 */
@Composable
fun HomeFeedListScreen(
    items: List<FeedItem>,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    DetailListPage(title = "与我有关", onBack = onBack) {
        items(items, key = { it.id }) { item ->
            CompactListRow(
                title = item.title,
                meta = "${item.sourceName} · ${Format.relative(item.publishedAt)}",
                onClick = { onOpenItem(item.id) },
            )
        }
    }
}

/**
 * 经验长文 · 完整列表。
 *
 * 主页那一栏原来叫「论坛新帖」，内容是 `MockData.forumPosts` —— 虚构作者、
 * 虚构互动数。社区接不通之后，这一栏改为展示**真实的经验长文**
 * （`GET /api/site/docs/experience`），点进去就是对应的正文详情。
 */
@Composable
fun HomeExperienceListScreen(
    docs: DocsStore,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    LaunchedEffect(Unit) { docs.loadExperience() }
    DetailListPage(title = "经验长文", onBack = onBack) {
        items(docs.experience, key = { it.slug }) { entry ->
            CompactListRow(
                title = entry.title,
                meta = listOfNotNull(entry.part, entry.category, entry.author)
                    .take(2).joinToString(" · ").ifEmpty { "经验长文" },
                onClick = { onOpenEntry(entry.slug) },
            )
        }
    }
}

/**
 * 单信源搜索结果.
 *
 * Port of `SearchSourceListView`: the destination of a `SearchSectionCard`'s
 * 「查看更多」, and the inline form the ConsoleBar scopes reuse. [scope] is the
 * raw Chinese source name — 「通知」, 「经验」 or 「手册」 — so a caller can hand
 * `SearchScope.label` straight through without a translation table.
 *
 * Each scope keeps the Swift `SearchEngine` filter, the `SearchResultRows` meta
 * line, and that row type's keyword emphasis.
 */
@Composable
fun SearchSourceListScreen(
    docs: DocsStore,
    scope: String,
    keyword: String,
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val key = keyword.trim()
    var error by remember(key) { mutableStateOf<String?>(null) }

    // 结果直接取自 `GET /api/site/pool`：这一次请求同时给了资讯 items 与
    // 手册/经验 docs，三信源按 label 分流，不再本地匹配假数据。
    // 关键词已经是路由参数（A-9 修好了编码），这里不再二次编码。
    LaunchedEffect(key) {
        if (key.isEmpty()) return@LaunchedEffect
        runCatching { docs.search(key) }
            .onSuccess { error = null }
            .onFailure { error = it.message ?: "网络异常" }
    }

    if (error != null) {
        TabPage {
            Column(Modifier.fillMaxSize()) {
                DetailHeader(title = scope, onBack = onBack)
                EmptyStateView(title = "搜索失败", description = error)
            }
        }
        return
    }

    val result = docs.searchResult
    DetailListPage(title = scope, onBack = onBack) {
        when (scope) {
            "通知" -> items(result.feed, key = { it.id }) { item ->
                CompactListRow(
                    title = item.title,
                    meta = "${item.sourceName} · ${item.summary}",
                    keyword = key,
                    onClick = { onOpenFeed(item.id) },
                )
            }

            "经验" -> items(result.experience, key = { it.slug }) { doc ->
                CompactListRow(
                    title = doc.title,
                    meta = doc.anchor?.let { "命中本节：${it.text.trim()}" } ?: doc.description.orEmpty(),
                    keyword = key,
                    onClick = { onOpenEntry(doc.slug) },
                )
            }

            "手册" -> items(result.handbook, key = { it.slug }) { doc ->
                CompactListRow(
                    title = doc.title,
                    meta = doc.anchor?.let { "命中本节：${it.text.trim()}" } ?: doc.description.orEmpty(),
                    keyword = key,
                    onClick = { onOpenEntry(doc.slug) },
                )
            }

            else -> item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp)) {
                    Text(
                        text = "未知搜索范围：$scope",
                        style = SeuType.Subheadline,
                        color = SeuTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

// --- chrome ----------------------------------------------------------------

/**
 * The pushed-list page shell: an inline `DetailHeader` over a scrolling list on
 * the 16pt page inset, with bottom padding clearing the floating tab bar.
 */
@Composable
private fun DetailListPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = title, onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = ListBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/**
 * The two-line list row every page above shares.
 *
 * Stands in for a Swift `List` + `NavigationLink` pair: `.subheadline.weight(.medium)`
 * title over a `.caption` secondary meta line, spaced 4pt. When [keyword] is
 * present the title is emphasised the way `String.highlighting(_:)` does in
 * `SearchResultRows`.
 */
@Composable
private fun CompactListRow(
    title: String,
    meta: String,
    onClick: () -> Unit,
    keyword: String? = null,
) {
    val colors = SeuTheme.colors
    CardColumn(padding = 14.dp, spacing = 4.dp, onClick = onClick) {
        Text(
            text = if (keyword != null) {
                highlightMatches(title, keyword)
            } else {
                buildAnnotatedString { append(title) }
            },
            style = SeuType.SubheadlineMedium,
            color = colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = meta,
            style = SeuType.Caption,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
