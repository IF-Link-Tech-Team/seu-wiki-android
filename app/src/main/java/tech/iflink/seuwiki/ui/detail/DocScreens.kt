package tech.iflink.seuwiki.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.data.DocsApiClient
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.data.campusHtmlToAnnotatedString
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.openExternalUrl
import androidx.compose.foundation.shape.CircleShape
import tech.iflink.seuwiki.data.UserProfileStore

/**
 * 手册 / 经验条目详情。
 *
 * 数据来自 `GET /api/site/docs/{slug}`，正文是白名单 HTML，经 [campusHtmlToAnnotatedString]
 * 渲染；`baseUrl` 传进去是为了把正文里的相对链接补成绝对地址。
 *
 * [anchor] 非空表示从搜索深链进来、命中的是正文某个小节，这时标题上方会明确提示
 * 「定位到本节」，而不是假装用户是从头读的。
 */
@Composable
fun DocEntryDetailScreen(
    docs: DocsStore,
    profile: UserProfileStore,
    slug: String,
    anchor: String? = null,
    onBack: () -> Unit,
) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    var detail by remember(slug) { mutableStateOf(docs.cachedDetail(slug)) }
    var error by remember(slug) { mutableStateOf<String?>(null) }

    LaunchedEffect(slug) {
        runCatching { docs.detail(slug) }
            .onSuccess { detail = it }
            .onFailure { error = context.getString(R.string.doc_load_failed, it.message ?: context.getString(R.string.doc_network_error)) }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = detail?.entry?.title ?: stringResource(R.string.doc_title),
                onBack = onBack,
                // 收藏放在标题栏右上角，与 iOS `DocDetailView` 的 toolbar 位置一致。
                trailing = {
                    BookmarkButton(
                        bookmarked = profile.isBookmarked(slug),
                        onToggle = { profile.toggleBookmark(slug) },
                    )
                },
            )
            when {
                detail == null && error != null -> EmptyStateView(
                    title = stringResource(R.string.doc_load_error_title),
                    description = error,
                    icon = {
                        Icon(
                            SeuIcons.of("exclamationmark.triangle"),
                            contentDescription = null,
                            tint = colors.tertiaryLabel,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                )

                detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.secondaryLabel, strokeWidth = 2.dp)
                }

                else -> DocDetailBody(
                    detail = detail!!,
                    anchor = anchor,
                    baseUrl = context.baseUrlForDoc(slug),
                    onOpenExternal = { url -> context.openExternalUrl(url) },
                )
            }
        }
    }
}

/**
 * 标题栏右上角的收藏开关。
 *
 * 图标用 SF 名 `bookmark` / `bookmark.fill` —— 走的是工程里「按 SF 名称查图标」
 * 那套跨端约定（§7 明确保留不改），iOS 侧用的是同一对符号，两端视觉自然一致。
 */
@Composable
private fun BookmarkButton(bookmarked: Boolean, onToggle: () -> Unit) {
    val colors = SeuTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SeuIcons.of(if (bookmarked) "bookmark.fill" else "bookmark"),
            contentDescription = stringResource(
                if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add
            ),
            tint = if (bookmarked) colors.accent else colors.label,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 正文 + 目录 + 上下篇。 */
@Composable
private fun DocDetailBody(
    detail: DocsApiClient.DocDetail,
    anchor: String?,
    baseUrl: String,
    onOpenExternal: (String) -> Unit,
) {
    val colors = SeuTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!anchor.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.doc_jump_to_section, anchor),
                style = SeuType.Footnote,
                color = colors.accent,
            )
        }

        detail.entry.description?.let {
            Text(it, style = SeuType.Subheadline, color = colors.secondaryLabel)
        }

        // 目录来自服务端下发的 headings（[{id,text,depth}]）。
        if (detail.headings.isNotEmpty()) {
            Text(stringResource(R.string.doc_outline), style = SeuType.Headline, color = colors.label)
            detail.headings.forEach { heading ->
                Text(
                    text = "· ${heading.text}",
                    style = SeuType.Subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = ((heading.depth - 2).coerceAtLeast(0) * 12).dp),
                )
            }
        }

        Text(stringResource(R.string.doc_body), style = SeuType.Headline, color = colors.label)

        if (detail.html.isBlank()) {
            Text(stringResource(R.string.doc_no_body), style = SeuType.Subheadline, color = colors.secondaryLabel)
        } else {
            val parsed = remember(detail.html, colors.accent, baseUrl) {
                campusHtmlToAnnotatedString(detail.html, colors.accent, baseUrl)
            }
            Text(
                text = parsed.text,
                style = SeuType.Body.copy(lineHeight = SeuType.Body.fontSize * 1.4f),
                color = colors.label,
            )
        }

        detail.sourceUrl?.let { url ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.doc_view_original),
                style = SeuType.SubheadlineSemibold,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenExternal(url) }
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/** 文档正文的相对链接以 `api/site/docs/` 为基准补全。 */
private fun android.content.Context.baseUrlForDoc(slug: String): String =
    "https://seu.wiki/api/site/docs/" + DocsApiClient.encodeSlug(slug)
