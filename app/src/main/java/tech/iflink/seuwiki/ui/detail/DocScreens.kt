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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.DocsApiClient
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.data.campusHtmlToAnnotatedString
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.models.DocPart
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.openExternalUrl

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
            .onFailure { error = "加载失败：${it.message ?: "网络异常"}" }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = detail?.entry?.title ?: "手册", onBack = onBack)
            when {
                detail == null && error != null -> EmptyStateView(
                    title = "这条内容没能加载出来",
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
                text = "从搜索定位到本节：$anchor",
                style = SeuType.Footnote,
                color = colors.accent,
            )
        }

        detail.entry.description?.let {
            Text(it, style = SeuType.Subheadline, color = colors.secondaryLabel)
        }

        // 目录来自服务端下发的 headings（[{id,text,depth}]）。
        if (detail.headings.isNotEmpty()) {
            Text("目录", style = SeuType.Headline, color = colors.label)
            detail.headings.forEach { heading ->
                Text(
                    text = "· ${heading.text}",
                    style = SeuType.Subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = ((heading.depth - 2).coerceAtLeast(0) * 12).dp),
                )
            }
        }

        Text("正文", style = SeuType.Headline, color = colors.label)

        if (detail.html.isBlank()) {
            Text("这条内容暂时没有正文。", style = SeuType.Subheadline, color = colors.secondaryLabel)
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
                text = "查看原文 →",
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

/**
 * 生存手册的「篇」。
 *
 * 按服务端给的真实层级展示：篇 → 组 → 条，不再是本地编出来的 6 个扁平章节。
 */
@Composable
fun HandbookPartScreen(
    docs: DocsStore,
    partKey: String,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val colors = SeuTheme.colors
    LaunchedEffect(Unit) { docs.loadHandbook() }

    val part: DocPart? = docs.handbook.firstOrNull { it.key == partKey }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = part?.label ?: "生存手册", onBack = onBack)
            if (part == null) {
                EmptyStateView(
                    title = "没有找到这一篇",
                    description = docs.handbookError ?: "手册目录可能已经更新，返回重进试试。",
                    icon = {
                        Icon(
                            SeuIcons.of("book"),
                            contentDescription = null,
                            tint = colors.tertiaryLabel,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = ListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(part.entries, key = { it.slug }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenEntry(entry.slug) }
                                // 补到 48dp 触控高度。
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(entry.title, style = SeuType.Headline, color = colors.label)
                            entry.description?.let {
                                Text(
                                    text = it,
                                    style = SeuType.Subheadline,
                                    color = colors.secondaryLabel,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 社区入口的统一说明页。
 *
 * seu-wiki-forum 目前只有 Supabase migration、**没有任何 HTTP API 路由**，
 * 所以发帖、点赞、评论、关注一律接不通。与其拿编造的帖子正文、虚构用户
 * （「林晚舟」等）和虚构互动数（1893 赞 / 342 评论）把功能装点出来，
 * 不如如实说明还没上线。
 */
@Composable
fun CommunityComingSoonScreen(onBack: () -> Unit) {
    val colors = SeuTheme.colors
    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "社区", onBack = onBack)
            EmptyStateView(
                title = "社区功能即将上线",
                description = "发帖、点赞、评论与关注正在开发中。\n" +
                    "现在可以先逛「资讯」「经验」和「东大生存手册」。",
                icon = {
                    Icon(
                        SeuIcons.of("person.2"),
                        contentDescription = null,
                        tint = colors.tertiaryLabel,
                        modifier = Modifier.size(40.dp),
                    )
                },
                topPadding = 80.dp,
            )
        }
    }
}

/** 文档正文的相对链接以 `api/site/docs/` 为基准补全。 */
private fun android.content.Context.baseUrlForDoc(slug: String): String =
    "https://seu.wiki/api/site/docs/" + DocsApiClient.encodeSlug(slug)
