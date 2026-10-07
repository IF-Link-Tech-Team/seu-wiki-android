package tech.iflink.seuwiki.ui.experience

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.data.ForumApiClient
import tech.iflink.seuwiki.data.ForumStore
import tech.iflink.seuwiki.data.campusHtmlToAnnotatedString
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.TintPill
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.design.forumSolidTint
import tech.iflink.seuwiki.models.HandbookArticle
import tech.iflink.seuwiki.models.HandbookSectionInfo
import tech.iflink.seuwiki.models.HandbookSectionPage
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.TabPage

/**
 * 东大生存手册三屏：板块列表（经验 tab 的子页）、板块详情、文章详情。
 *
 * 数据全部来自论坛后端 `/api/handbook/` 系列接口（编辑团队从讨论沉淀的真实文章），
 * 不再是 seu.wiki 的 `/api/site/docs/survival` 文档树。文章正文是服务端消毒过的
 * `content_html`，复用 [campusHtmlToAnnotatedString] 渲染管线。
 */

/**
 * 手册板块列表（经验 tab「东大生存手册」子页的内容体，外面已有 ScreenHeader）。
 */
@Composable
fun HandbookHomeTab(
    store: ForumStore,
    onOpenSection: (slug: String, name: String) -> Unit,
) {
    LaunchedEffect(Unit) { store.loadHandbookSections() }
    val sections = store.handbookSections

    when {
        sections == null && store.handbookLoading -> LoadingView(topPadding = 64.dp)
        sections == null -> EmptyStateView(
            title = stringResource(R.string.handbook_load_failed),
            description = store.handbookError,
            icon = { HandbookGlyph("book.closed") },
            topPadding = 64.dp,
            action = {
                Text(
                    text = stringResource(R.string.search_retry),
                    style = SeuType.SubheadlineSemibold,
                    color = SeuTheme.colors.accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { store.loadHandbookSections(force = true) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            },
        )

        sections.isEmpty() -> EmptyStateView(
            title = stringResource(R.string.handbook_empty),
            description = stringResource(R.string.handbook_empty_desc),
            icon = { HandbookGlyph("book.closed") },
            topPadding = 64.dp,
        )

        else -> LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = ListBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sections, key = { it.slug }) { section ->
                HandbookSectionCard(
                    section = section,
                    onClick = { onOpenSection(section.slug, section.name) },
                )
            }
        }
    }
}

/** 板块卡：图标圆盘 + 名称 + 文章数 + 子标签 chips。对齐 iOS HandbookHomeView。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandbookSectionCard(section: HandbookSectionInfo, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Column(Modifier.cardStyle(padding = 0.dp, onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(forumSolidTint(section.slug)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SeuIcons.of("books.vertical.fill"),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = section.name,
                    style = SeuType.Headline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.handbook_articles_count, section.articleCount),
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                )
            }
            Icon(
                imageVector = SeuIcons.of("chevron.right"),
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(14.dp),
            )
        }
        if (section.children.isNotEmpty()) {
            InsetDivider(leading = 60.dp)
            FlowRow(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                section.children.forEach { child ->
                    TintPill(
                        text = stringResource(R.string.handbook_child_count, child.name, child.articleCount),
                        tint = colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * 手册板块详情：板块元信息 + 文章列表 + 「查看该板块讨论」入口（跳该 tag 的帖子流）。
 *
 * [fallbackName] 是列表页带过来的板块名，详情请求返回前标题栏就有标题。
 */
@Composable
fun HandbookSectionScreen(
    store: ForumStore,
    slug: String,
    fallbackName: String = "",
    onBack: () -> Unit,
    onOpenArticle: (id: String, title: String) -> Unit,
    onOpenTopic: (String) -> Unit,
) {
    val context = LocalContext.current
    var page by remember(slug) { mutableStateOf<HandbookSectionPage?>(null) }
    var error by remember(slug) { mutableStateOf<String?>(null) }

    LaunchedEffect(slug) {
        error = null
        runCatching { store.handbookSection(slug) }
            .onSuccess { page = it }
            .onFailure {
                error = it.message
                    ?: context.getString(R.string.doc_network_error)
            }
    }

    val title = page?.name
        ?: fallbackName.ifEmpty { TopicCatalog.nameForSlug(slug) ?: slug }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = title, onBack = onBack)
            when {
                page == null && error == null -> LoadingView(topPadding = 64.dp)
                page == null -> EmptyStateView(
                    title = stringResource(R.string.handbook_load_failed),
                    description = error,
                    icon = { HandbookGlyph("book.closed") },
                    topPadding = 64.dp,
                )

                else -> {
                    val section = requireNotNull(page)
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = ListBottomPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 「查看该板块讨论」→ 该 tag 的帖子流。
                        item(key = "discussion") {
                            DiscussionEntry(
                                label = stringResource(R.string.handbook_section_discussion),
                                onClick = { onOpenTopic(slug) },
                            )
                        }
                        if (section.children.isNotEmpty()) {
                            item(key = "children") {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    section.children.forEach { child ->
                                        Text(
                                            text = child.name,
                                            style = SeuType.Caption,
                                            color = SeuTheme.colors.secondaryLabel,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(SeuTheme.colors.tertiaryFill)
                                                .clickable { onOpenTopic(child.slug) }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (section.articles.isEmpty()) {
                            item(key = "empty") {
                                EmptyStateView(
                                    title = stringResource(R.string.handbook_section_empty),
                                    description = stringResource(R.string.handbook_section_empty_desc),
                                    icon = { HandbookGlyph("doc.text") },
                                    topPadding = 40.dp,
                                )
                            }
                        } else {
                            items(section.articles, key = { it.id }) { article ->
                                Column(
                                    Modifier.cardStyle(
                                        padding = 14.dp,
                                        onClick = { onOpenArticle(article.id, article.title) },
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = article.title,
                                        style = SeuType.SubheadlineMedium,
                                        color = SeuTheme.colors.label,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            TopicCatalog.nameForSlug(article.tagSlug),
                                            article.authorDisplay,
                                            article.publishedAt?.let { Format.relative(context, it) }
                                                ?.takeIf { it.isNotEmpty() },
                                        ).joinToString(" · "),
                                        style = SeuType.Caption,
                                        color = SeuTheme.colors.secondaryLabel,
                                        maxLines = 1,
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
}

/**
 * 手册文章详情：渲染服务端消毒过的 `content_html`。
 * 有 `source_post` 时底部显示「查看原帖讨论」卡片跳帖子详情。
 */
@Composable
fun HandbookArticleScreen(
    store: ForumStore,
    articleId: String,
    fallbackTitle: String = "",
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    var article by remember(articleId) { mutableStateOf<HandbookArticle?>(null) }
    var error by remember(articleId) { mutableStateOf<String?>(null) }

    LaunchedEffect(articleId) {
        error = null
        runCatching { store.handbookArticle(articleId) }
            .onSuccess { article = it }
            .onFailure { error = it.message ?: context.getString(R.string.doc_network_error) }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = article?.title ?: fallbackTitle.ifEmpty { stringResource(R.string.handbook_article_title) },
                onBack = onBack,
            )
            when {
                article == null && error == null -> LoadingView(topPadding = 64.dp)
                article == null -> EmptyStateView(
                    title = stringResource(R.string.handbook_load_failed),
                    description = error,
                    icon = { HandbookGlyph("doc.text") },
                    topPadding = 64.dp,
                )

                else -> {
                    val a = requireNotNull(article)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TopicCatalog.nameForSlug(a.tagSlug)?.let { TintPill(it, colors.accent) }
                            a.authorDisplay?.let { TintPill(it, colors.secondaryLabel) }
                            a.publishedAt?.let {
                                Text(
                                    text = Format.relative(context, it),
                                    style = SeuType.Caption,
                                    color = colors.secondaryLabel,
                                )
                            }
                        }
                        if (a.contentHtml.isBlank()) {
                            Text(
                                text = stringResource(R.string.handbook_no_body),
                                style = SeuType.Subheadline,
                                color = colors.secondaryLabel,
                            )
                        } else {
                            val parsed = remember(a.contentHtml, colors.accent) {
                                campusHtmlToAnnotatedString(
                                    a.contentHtml,
                                    colors.accent,
                                    // 手册正文里的相对链接以论坛站点为基准补全。
                                    baseUrl = ForumApiClient.DefaultBaseUrl + "/handbook/",
                                )
                            }
                            Text(
                                text = parsed.text,
                                style = SeuType.Body.copy(lineHeight = SeuType.Body.fontSize * 1.4f),
                                color = colors.label,
                            )
                        }
                        a.sourcePost?.let { source ->
                            DiscussionEntry(
                                label = stringResource(R.string.handbook_source_post),
                                title = source.title?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.handbook_source_post_fallback),
                                meta = stringResource(
                                    R.string.handbook_source_post_stats,
                                    source.likesCount,
                                    source.commentsCount,
                                ),
                                onClick = { onOpenPost(source.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「查看该板块讨论」/「查看原帖讨论」共用的入口卡。 */
@Composable
private fun DiscussionEntry(
    label: String,
    title: String? = null,
    meta: String? = null,
    onClick: () -> Unit,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardStyle(padding = 0.dp, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SeuIcons.of("bubble.left.and.text.bubble.right"),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = SeuType.Caption, color = colors.secondaryLabel)
            title?.let {
                Text(
                    text = it,
                    style = SeuType.SubheadlineMedium,
                    color = colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta?.let {
                Text(it, style = SeuType.Caption2, color = colors.tertiaryLabel)
            }
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = SeuIcons.of("chevron.right"),
            contentDescription = null,
            tint = colors.tertiaryLabel,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun HandbookGlyph(symbol: String) {
    Icon(
        imageVector = SeuIcons.of(symbol),
        contentDescription = null,
        tint = SeuTheme.colors.tertiaryLabel,
        modifier = Modifier.size(40.dp),
    )
}
