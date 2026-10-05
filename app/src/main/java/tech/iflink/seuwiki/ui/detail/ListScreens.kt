package tech.iflink.seuwiki.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.HandbookEntry
import tech.iflink.seuwiki.models.MockData
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
 * 论坛新帖 · 完整列表.
 *
 * Port of `HomeForumListView`: the 「查看全部」 destination for the Home forum
 * section, same compact two-line list shape as the feed list.
 */
@Composable
fun HomeForumListScreen(
    posts: List<ForumPost>,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    DetailListPage(title = "论坛新帖", onBack = onBack) {
        items(posts, key = { it.id }) { post ->
            CompactListRow(
                title = post.title,
                meta = "${post.authorName} · ${Format.relative(post.createdAt)}",
                onClick = { onOpenPost(post.id) },
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
    scope: String,
    keyword: String,
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val key = keyword.trim()
    val hits = remember(scope, key) { searchScope(scope, key) }
    if (hits == null) {
        TabPage {
            Column(Modifier.fillMaxSize()) {
                DetailHeader(title = scope, onBack = onBack)
                EmptyStateView(title = "未知搜索范围")
            }
        }
        return
    }

    DetailListPage(title = scope, onBack = onBack) {
        when (hits) {
            is ScopeHits.Feed -> items(hits.items, key = { it.id }) { item ->
                CompactListRow(
                    title = item.title,
                    meta = "${item.sourceName} · ${item.summary}",
                    keyword = key,
                    onClick = { onOpenFeed(item.id) },
                )
            }

            is ScopeHits.Forum -> items(hits.items, key = { it.id }) { post ->
                CompactListRow(
                    title = post.title,
                    meta = "${post.authorName} · ${post.excerpt}",
                    keyword = key,
                    onClick = { onOpenPost(post.id) },
                )
            }

            is ScopeHits.Handbook -> items(hits.items, key = { it.id }) { entry ->
                CompactListRow(
                    title = entry.title,
                    meta = handbookSnippet(entry, key),
                    keyword = key,
                    onClick = { onOpenEntry(entry.id) },
                )
            }

            ScopeHits.Blank -> Unit
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

// --- query -----------------------------------------------------------------

/** One scope's hits, kept per source so each renders the meta line the iOS page pairs with it. */
private sealed interface ScopeHits {

    data class Feed(val items: List<FeedItem>) : ScopeHits

    data class Forum(val items: List<ForumPost>) : ScopeHits

    data class Handbook(val items: List<HandbookEntry>) : ScopeHits

    /** A blank keyword: the Swift `SearchStore` clears every bucket rather than matching all. */
    data object Blank : ScopeHits
}

/**
 * Runs the query against one source.
 *
 * 「通知」 hits the live `pool` search in the SwiftUI build, falling back to
 * this local match when the request fails; 「经验」 and 「手册」 are local
 * providers outright. All three are offline here, so the fallback is all we
 * have — the same behaviour the Android search screen already ships.
 *
 * Returns null when [scope] names no source.
 */
private fun searchScope(scope: String, keyword: String): ScopeHits? {
    if (keyword.isEmpty()) return ScopeHits.Blank
    return when (scope) {
        "通知" -> ScopeHits.Feed(
            MockData.feedItems.filter {
                matches(it.title, keyword) || matches(it.summary, keyword)
            },
        )

        "经验" -> ScopeHits.Forum(
            MockData.forumPosts.filter {
                matches(it.title, keyword) || matches(it.excerpt, keyword)
            },
        )

        "手册" -> ScopeHits.Handbook(
            MockData.handbookSections.flatMap { it.entries }.filter {
                matches(it.title, keyword) ||
                    matches(it.subtitle, keyword) ||
                    matches(it.body, keyword)
            },
        )

        else -> null
    }
}

/** `SearchEngine.matches`: case-insensitive containment. */
private fun matches(text: String, keyword: String): Boolean =
    text.contains(keyword, ignoreCase = true)

/**
 * The handbook meta line.
 *
 * Port of `SearchHandbookRow.snippet`: prefer the subtitle, and fall back to the
 * body when only the body matched, so the hit is visible on the row.
 */
private fun handbookSnippet(entry: HandbookEntry, keyword: String): String =
    if (keyword.isNotEmpty() &&
        !matches(entry.title, keyword) &&
        !matches(entry.subtitle, keyword) &&
        matches(entry.body, keyword)
    ) {
        entry.body
    } else {
        entry.subtitle
    }
