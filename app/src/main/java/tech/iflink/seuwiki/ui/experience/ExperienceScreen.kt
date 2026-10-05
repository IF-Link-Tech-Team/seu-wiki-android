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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.data.ExperienceSelection
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.ExperienceTab
import tech.iflink.seuwiki.models.DocEntry
import tech.iflink.seuwiki.models.DocPart
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage

/**
 * 经验 tab。
 *
 * 四个子页全部来自**真实后端数据**，没有一条编造内容：
 * - 热门：经验长文列表（`GET /api/site/docs/experience`）
 * - 话题：同上的分面筛选，用服务端 `filters[]` 给的真实取值
 * - 东大生存手册：真实文档树（`GET /api/site/docs/survival`）
 * - 关注：诚实的空状态。seu-wiki-forum 目前**没有任何 HTTP API 路由**，
 *   社区功能无法接通，所以这里明确写「即将上线」而不是拿假帖子填坑。
 *
 * console 胶囊与 pager 双向绑定：点胶囊滚动 pager，滑动 pager 跟随胶囊，
 * 与 iOS 的 `ExperienceHomeView` 一致。
 */
@Composable
fun ExperienceScreen(
    profile: UserProfileStore,
    docs: DocsStore,
    onOpenProfile: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenHandbookPart: (String) -> Unit,
) {
    val tabs = ExperienceTab.all
    var tabKey by rememberSaveable { mutableStateOf(ExperienceTab.Hot.key) }
    val tab = tabs.firstOrNull { it.key == tabKey } ?: ExperienceTab.Hot
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // Console → pager。
    LaunchedEffect(tab) {
        val index = tabs.indexOf(tab)
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }
    // Pager → console。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            tabs.getOrNull(page)?.let { tabKey = it.key }
        }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "经验", onProfileClick = onOpenProfile)
            ConsoleBar(
                items = tabs,
                selection = tab,
                onSelect = {
                    tabKey = it.key
                    scope.launch { pagerState.animateScrollToPage(tabs.indexOf(it)) }
                },
                title = { it.label },
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (tabs[page]) {
                    ExperienceTab.Hot -> ExperienceList(docs, onOpenEntry)
                    ExperienceTab.Topics -> ExperienceFacets(docs, onOpenEntry)
                    ExperienceTab.Handbook -> HandbookTree(docs, onOpenHandbookPart, onOpenEntry)
                    ExperienceTab.Following -> FollowingComingSoon()
                }
            }
        }
    }
}

/** 热门 / 话题 共用的列表：同一份数据，只是「话题」页顶部多了分面筛选。 */
@Composable
private fun ExperienceList(
    docs: DocsStore,
    onOpenEntry: (String) -> Unit,
) {
    ExperienceState(docs) {
        if (docs.experience.isEmpty() && !docs.experienceLoading) {
            EmptyStateView(
                title = "还没有经验长文",
                description = docs.experienceError
                    ?: "「社区」功能即将上线，经验长文会持续补充。",
                icon = { EmptyIcon("text.book.closed") },
                topPadding = 64.dp,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(docs.experience, key = { it.slug }) { entry ->
                    DocEntryCard(entry = entry, onClick = { onOpenEntry(entry.slug) })
                }
            }
        }
    }
}

/**
 * 话题 —— 经验长文的分面筛选。
 *
 * 筛选项**一律取服务端 `filters[]` 的真实取值**（实测：场景 7 项、年级 6 项、
 * 学院 4 项），不在客户端另编一套中文分类。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperienceFacets(
    docs: DocsStore,
    onOpenEntry: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    ExperienceState(docs) {
        val filters = docs.experienceFilters
        val selection = docs.experienceSelection
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier.cardStyle(padding = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("按场景、年级、学院筛选", style = SeuType.SubheadlineSemibold)
                    filters.forEach { facet ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                facet.label,
                                style = SeuType.Footnote,
                                color = SeuTheme.colors.secondaryLabel,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                facet.values.forEach { value ->
                                    FacetChip(
                                        text = value,
                                        selected = when (facet.key) {
                                            "category" -> selection.category == value
                                            "grade" -> selection.grade == value
                                            "college" -> selection.college == value
                                            else -> false
                                        },
                                        onClick = {
                                            scope.launch {
                                                docs.loadExperience(selection.toggle(facet.key, value))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (selection.isActive) {
                        Text(
                            text = "清除筛选",
                            style = SeuType.Subheadline,
                            color = SeuTheme.colors.accent,
                            modifier = Modifier
                                .clip(CircleShape)
                                // 触控区补到 48dp 高，避免只有文字那点高度可点。
                                .clickable { scope.launch { docs.loadExperience(ExperienceSelection()) } }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
            if (docs.experience.isEmpty() && !docs.experienceLoading) {
                item {
                    EmptyStateView(
                        title = "这个组合下暂时没有内容",
                        description = "换一个场景或年级再看看。",
                        icon = { EmptyIcon("line.3.horizontal.decrease.circle") },
                        topPadding = 48.dp,
                    )
                }
            }
            items(docs.experience, key = { it.slug }) { entry ->
                DocEntryCard(entry = entry, onClick = { onOpenEntry(entry.slug) })
            }
        }
    }
}

/** 东大生存手册 —— 真实文档树，按「篇」分组。 */
@Composable
private fun HandbookTree(
    docs: DocsStore,
    onOpenPart: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    ExperienceState(docs) {
        if (docs.handbook.isEmpty() && !docs.handbookLoading) {
            EmptyStateView(
                title = "生存手册加载中或暂时不可用",
                description = docs.handbookError ?: "下拉或重新进入即可重试。",
                icon = { EmptyIcon("book") },
                topPadding = 64.dp,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(docs.handbook, key = { it.key }) { part ->
                    HandbookPartCard(
                        part = part,
                        onOpenPart = { onOpenPart(part.key) },
                        onOpenEntry = onOpenEntry,
                    )
                }
            }
        }
    }
}

@Composable
private fun HandbookPartCard(
    part: DocPart,
    onOpenPart: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    Column(Modifier.cardStyle(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .clickable(onClick = onOpenPart)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(part.label, style = SeuType.Headline, color = SeuTheme.colors.label)
            Text("查看全部", style = SeuType.Footnote, color = SeuTheme.colors.accent)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${part.entries.size} 条",
                style = SeuType.Footnote,
                color = SeuTheme.colors.secondaryLabel,
            )
        }
        part.groups.forEach { group ->
            group.items.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable { onOpenEntry(entry.slug) }
                        // 44dp → 48dp：列表行是高频触控目标，别贴着下限。
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.title,
                        style = SeuType.Subheadline,
                        color = SeuTheme.colors.label,
                        modifier = Modifier.weight(1f),
                    )
                    entry.description?.let {
                        Text(
                            text = it,
                            style = SeuType.Caption2,
                            color = SeuTheme.colors.secondaryLabel,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 关注 —— 社区尚未上线，诚实空状态。
 *
 * 原来的实现是从 [tech.iflink.seuwiki.models.MockData] 里筛出「用户关注话题」的
 * 假帖子：作者、点赞数 1893、评论数 342 全是编的。seu-wiki-forum 目前没有任何
 * HTTP API 路由，发帖/点赞/评论/关注都接不通 —— 与其用假数据把功能装点出来，
 * 不如直接说清楚还没上线。
 */
@Composable
private fun FollowingComingSoon() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyStateView(
            title = "社区功能即将上线",
            description = "关注、发帖、点赞与评论正在开发中。\n现在可以先逛逛「热门」和「东大生存手册」。",
            icon = { EmptyIcon("person.2") },
            topPadding = 72.dp,
        )
    }
}

/** 加载失败时给一个可点的重试；加载中给骨架占位。 */
@Composable
private fun ExperienceState(docs: DocsStore, content: @Composable () -> Unit) {
    LaunchedEffect(Unit) { docs.loadExperience() }
    LaunchedEffect(Unit) { docs.loadHandbook() }
    content()
}

@Composable
private fun EmptyIcon(symbol: String) {
    Icon(
        imageVector = SeuIcons.of(symbol),
        contentDescription = null,
        tint = SeuTheme.colors.tertiaryLabel,
        modifier = Modifier.size(40.dp),
    )
}

/**
 * 分面筛选 chip。
 *
 * 选中态用**品牌深色文字**而不是白字：`#34D6AB` 的亮绿底压白字对比度只有
 * 1.83:1，远低于 WCAG AA 的 4.5:1。深色下改用 `#00382B`（对亮绿 7.08:1）。
 */
@Composable
private fun FacetChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Text(
        text = text,
        style = SeuType.Subheadline,
        color = if (selected) colors.onAccentInverted else colors.label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.secondaryGroupedBackground)
            .selectableChip(selected = selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** chip 的选中语义 + 按下反馈：图标/文字之外，读屏也能知道「已选中」。 */
private fun Modifier.selectableChip(selected: Boolean, onClick: () -> Unit): Modifier =
    this
        .semantics { this.selected = selected }
        .clickable(onClick = onClick)

/** 经验长文条目卡：标题 + 描述 + 篇/分类。 */
@Composable
fun DocEntryCard(entry: DocEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardStyle(padding = 14.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(entry.title, style = SeuType.Headline, color = SeuTheme.colors.label)
        entry.description?.let {
            Text(
                text = it,
                style = SeuType.Subheadline,
                color = SeuTheme.colors.secondaryLabel,
                maxLines = 3,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(entry.part, entry.category, entry.author).take(3).forEach {
                Text(
                    text = it,
                    style = SeuType.Caption2,
                    color = SeuTheme.colors.tertiaryLabel,
                )
            }
        }
    }
}

/** 切换某个分面的选中态：点已选中的取消，点新的替换同分面的旧值。 */
private fun ExperienceSelection.toggle(key: String, value: String): ExperienceSelection =
    when (key) {
        "category" -> copy(category = if (category == value) null else value)
        "grade" -> copy(grade = if (grade == value) null else value)
        "college" -> copy(college = if (college == value) null else value)
        else -> this
    }
