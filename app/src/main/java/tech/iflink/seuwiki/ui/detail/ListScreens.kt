package tech.iflink.seuwiki.ui.detail

import tech.iflink.seuwiki.ui.search.SearchScope
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.data.SearchStore
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.LoadingView
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
    val context = LocalContext.current
    DetailListPage(title = stringResource(R.string.list_related_title), onBack = onBack) {
        items(items, key = { it.id }) { item ->
            CompactListRow(
                title = item.title,
                meta = "${item.sourceName} · ${Format.relative(context, item.publishedAt)}",
                onClick = { onOpenItem(item.id) },
            )
        }
    }
}

/**
 * 单信源搜索结果.
 *
 * Port of `SearchSourceListView`: the destination of a `SearchSectionCard`'s
 * 「查看更多」. [scopeKey] is a `SearchScope.key` handed straight through from the
 * route — a stable ASCII identifier, never the localized display name, so a
 * device-language change can never orphan a route already sitting on the back
 * stack.
 *
 * 数据与搜索页同一份 [SearchStore.state]：通知来自 `pool?type=feed`，经验/手册
 * 来自论坛搜索的 posts / articles 桶。三组按 scope 分流，关键词高亮与
 * 搜索页行内结果一致。
 */
@Composable
fun SearchSourceListScreen(
    store: SearchStore,
    scopeKey: String,
    keyword: String,
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenArticle: (id: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    // 路由带进来的是 SearchScope.key（稳定的 ASCII 标识符），显示名才从资源取。
    val scope = SearchScope.fromKey(scopeKey)
    val scopeTitle = scope?.let { stringResource(it.labelRes) } ?: scopeKey
    val key = keyword.trim()

    // 关键词已经是路由参数，这里不再二次编码。与搜索页共享同一个 store：
    // 若刚搜过同一关键词，state 里已有结果，请求会被 collectLatest 幂等覆盖。
    LaunchedEffect(key) {
        if (key.isNotEmpty() && store.state.query != key) store.search(key)
    }

    val result = store.state
    val failed = when (scope) {
        SearchScope.Feed -> result.feedError
        SearchScope.Forum, SearchScope.Handbook -> result.forumError
        else -> if (result.allFailed) result.forumError ?: result.feedError else null
    }

    when {
        result.loading && result.total == 0 -> DetailListPage(title = scopeTitle, onBack = onBack) {
            item { LoadingView(topPadding = 64.dp) }
        }

        failed != null -> TabPage {
            Column(Modifier.fillMaxSize()) {
                DetailHeader(title = scopeTitle, onBack = onBack)
                EmptyStateView(
                    title = stringResource(R.string.list_search_failed),
                    description = failed.format(context),
                )
            }
        }

        else -> DetailListPage(title = scopeTitle, onBack = onBack) {
            when (scope) {
                SearchScope.Feed -> items(result.feed, key = { it.id }) { item ->
                    CompactListRow(
                        title = item.title,
                        meta = "${item.sourceName} · ${item.summary}",
                        keyword = key,
                        onClick = { onOpenFeed(item.id) },
                    )
                }

                SearchScope.Forum -> items(result.posts, key = { it.id }) { post ->
                    CompactListRow(
                        title = post.title?.takeIf { it.isNotBlank() } ?: post.snippet,
                        meta = listOfNotNull(
                            post.author?.nameOrFallback,
                            "${post.likesCount} 赞",
                            "${post.commentsCount} 评论",
                        ).joinToString(" · "),
                        keyword = key,
                        onClick = { onOpenPost(post.id) },
                    )
                }

                SearchScope.Handbook -> items(result.articles, key = { it.id }) { article ->
                    CompactListRow(
                        title = article.title,
                        meta = listOfNotNull(
                            TopicCatalog.nameForSlug(article.tagSlug),
                            article.publishedAt?.let { Format.relative(context, it) }
                                ?.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        keyword = key,
                        onClick = { onOpenArticle(article.id, article.title) },
                    )
                }

                else -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp)) {
                        Text(
                            text = stringResource(R.string.list_unknown_scope, scopeKey),
                            style = SeuType.Subheadline,
                            color = SeuTheme.colors.secondaryLabel,
                        )
                    }
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
