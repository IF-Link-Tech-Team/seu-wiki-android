package tech.iflink.seuwiki.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.FeedStore
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.data.toFeedProfile
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.models.FeedCategory
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.FeedItemCard
import tech.iflink.seuwiki.ui.rows.ForYouCard

/** 资讯 console scope — 为你精选 / 全部 / the 8 categories. */
sealed interface FeedScope {
    val title: String

    data object ForYou : FeedScope {
        override val title = "为你精选"
    }

    data object All : FeedScope {
        override val title = "全部"
    }

    data class Category(val category: FeedCategory) : FeedScope {
        override val title: String get() = category.label
    }

    companion object {
        val scopes: List<FeedScope> = listOf(ForYou, All) + FeedCategory.all.map { Category(it) }

        /** Stable key so `ConsoleBar` selection survives recomposition. */
        val key: (FeedScope) -> String = { scope ->
            when (scope) {
                ForYou -> "forYou"
                All -> "all"
                is Category -> scope.category.key
            }
        }
    }
}

/**
 * The client-side filter applied to the 「全部」 list.
 *
 * A `null` degree means "all". Audience arrays that come back empty mean the
 * live API did not describe the audience, so — matching the SwiftUI comment —
 * those items are treated as neutral and never filtered out.
 */
data class FeedFilter(
    val onlyMyCollege: Boolean = false,
    val degree: String? = null,
    val categories: Set<FeedCategory> = emptySet(),
) {
    val isActive: Boolean get() = onlyMyCollege || degree != null || categories.isNotEmpty()

    fun matches(item: FeedItem, profile: UserProfileStore): Boolean {
        if (onlyMyCollege &&
            item.audience.colleges.isNotEmpty() &&
            !item.audience.colleges.contains(profile.college)
        ) {
            return false
        }
        if (degree != null &&
            item.audience.identities.isNotEmpty() &&
            !item.audience.identities.contains(degree)
        ) {
            return false
        }
        if (categories.isNotEmpty() && !categories.contains(item.category)) return false
        return true
    }
}

/**
 * 资讯.
 *
 * Port of `FeedHomeView`: a console across the two global scopes and the eight
 * categories, a personalised card stream for 为你精选, and a filterable list for
 * 全部 with the filter button appearing in the header only for that scope.
 */
@Composable
fun FeedScreen(
    profile: UserProfileStore,
    store: FeedStore,
    onOpenProfile: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    var scopeKey by rememberSaveable { mutableStateOf("forYou") }
    val scope = FeedScope.scopes.firstOrNull { FeedScope.key(it) == scopeKey } ?: FeedScope.ForYou
    var filter by rememberSaveable(stateSaver = FeedFilterSaver) {
        mutableStateOf(FeedFilter())
    }
    var showsFilter by remember { mutableStateOf(false) }

    // 画像只取 for-you 用到的四个字段，profile 变化时才重建。
    val feedProfile = remember(profile.college, profile.degree, profile.grade, profile.interests) {
        profile.toFeedProfile()
    }

    // 首次进入某个 scope 时拉一次；已加载过的 scope 直接复用缓存的分页状态。
    LaunchedEffect(scopeKey) { store.loadIfNeeded(scope, feedProfile) }
    val page = store.page(scope)

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "资讯",
                onProfileClick = onOpenProfile,
                trailing = {
                    AnimatedVisibility(visible = scope == FeedScope.All) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { showsFilter = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = SeuIcons.of(
                                    if (filter.isActive) {
                                        "line.3.horizontal.decrease.circle.fill"
                                    } else {
                                        "line.3.horizontal.decrease.circle"
                                    },
                                ),
                                contentDescription = "筛选",
                                tint = SeuTheme.colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("筛选", style = SeuType.Subheadline, color = SeuTheme.colors.accent)
                        }
                    }
                },
            )

            ConsoleBar(
                items = FeedScope.scopes,
                selection = scope,
                onSelect = { scopeKey = FeedScope.key(it) },
                title = { it.title },
            )

            // 筛选只在「全部」下生效，与 SwiftUI 的 allItems 一致。
            val visible = if (scope == FeedScope.All) {
                page.items.filter { filter.matches(it, profile) }
            } else {
                page.items
            }
            val emptyMessage = when (scope) {
                FeedScope.All ->
                    if (filter.isActive) "没有符合条件的资讯，试试调整筛选条件" else "暂无资讯"
                is FeedScope.Category -> "该分类暂无资讯"
                FeedScope.ForYou -> "暂时没有为你精选的资讯"
            }

            if (visible.isEmpty() && page.isLoading) {
                LoadingView(topPadding = 80.dp)
            } else if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = "暂无资讯",
                        description = emptyMessage,
                        icon = {
                            Icon(
                                imageVector = SeuIcons.of("newspaper"),
                                contentDescription = null,
                                tint = SeuTheme.colors.tertiaryLabel,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = ListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (page.isOffline) { item(key = "offline") { FeedOfflineBanner() } }

                    items(visible, key = { it.id }) { item ->
                        if (scope == FeedScope.ForYou) {
                            ForYouCard(item = item, onClick = { onOpenItem(item.id) })
                        } else {
                            FeedItemCard(item = item, onClick = { onOpenItem(item.id) })
                        }
                    }

                    if (page.isLoadingMore) {
                        item(key = "loadingMore") { LoadingView(topPadding = 24.dp) }
                    }
                }
                // 滚到底加载下一页，对应 SwiftUI 的 `.onAppear { if item.id == last { loadMore } }`。
                LaunchedEffect(visible.size, page.nextCursor) {
                    if (visible.isNotEmpty() && page.nextCursor != null) {
                        store.loadMore(scope, feedProfile)
                    }
                }
            }
        }
    }

    if (showsFilter) {
        FeedFilterSheet(
            profile = profile,
            filter = filter,
            onChange = { filter = it },
            onDismiss = { showsFilter = false },
        )
    }
}

/**
 * The 筛选 sheet.
 *
 * Android's bottom sheet rather than the iOS form sheet — same three sections
 * (学院 / 学段 / 分类) and the same destructive "清除全部筛选" affordance, shown
 * only while a filter is active.
 */
@Composable
private fun FeedFilterSheet(
    profile: UserProfileStore,
    filter: FeedFilter,
    onChange: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SeuTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.secondaryGroupedBackground)
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("筛选", style = SeuType.Title3, color = colors.label)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "完成",
                    style = SeuType.SubheadlineMedium,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                FilterSectionTitle("学院")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "只看我的学院",
                        style = SeuType.Body,
                        color = colors.label,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = filter.onlyMyCollege,
                        onCheckedChange = { onChange(filter.copy(onlyMyCollege = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                    )
                }
                if (filter.onlyMyCollege) {
                    Text(
                        text = profile.college,
                        style = SeuType.Body,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                FilterSectionTitle("学段")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip("全部", filter.degree == null) {
                        onChange(filter.copy(degree = null))
                    }
                    listOf("本科生", "硕士生", "博士生").forEach { degree ->
                        FilterChip(degree, filter.degree == degree) {
                            onChange(filter.copy(degree = degree))
                        }
                    }
                }

                FilterSectionTitle("分类")
                FeedCategory.all.forEach { category ->
                    val checked = category in filter.categories
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onChange(
                                    filter.copy(
                                        categories = if (checked) {
                                            filter.categories - category
                                        } else {
                                            filter.categories + category
                                        },
                                    ),
                                )
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = SeuIcons.of(category.iconKey),
                            contentDescription = null,
                            tint = colors.label,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(
                            text = category.label,
                            style = SeuType.Body,
                            color = colors.label,
                            modifier = Modifier.weight(1f),
                        )
                        if (checked) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }

                if (filter.isActive) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "清除全部筛选",
                        style = SeuType.Body,
                        color = colors.red,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onChange(FeedFilter()) }
                            .padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    val colors = SeuTheme.colors
    Text(
        text = text,
        style = SeuType.Subheadline,
        color = colors.secondaryLabel,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Text(
        text = label,
        style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
        color = if (selected) Color.White else colors.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.tertiaryFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Remembers the filter across tab switches by encoding it as a string. */
private val FeedFilterSaver = Saver<FeedFilter, String>(
    save = { filter ->
        listOf(
            if (filter.onlyMyCollege) "1" else "0",
            filter.degree.orEmpty(),
            filter.categories.joinToString(",") { it.key },
        ).joinToString("|")
    },
    restore = { raw ->
        val parts = raw.split("|")
        FeedFilter(
            onlyMyCollege = parts.getOrNull(0) == "1",
            degree = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            categories = parts.getOrNull(2)
                ?.split(",")
                ?.mapNotNull { FeedCategory.fromKey(it) }
                ?.toSet()
                ?: emptySet(),
        )
    },
)

/**
 * 网络失败时的轻量提示，对应 `FeedOfflineBanner`。
 *
 * 不阻塞浏览：列表此时显示的是 MockData 回退内容。
 */
@Composable
private fun FeedOfflineBanner() {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.tertiaryFill)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = SeuIcons.of("wifi.slash"),
            contentDescription = null,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "暂时无法连接服务器，显示离线示例内容",
            style = SeuType.Caption,
            color = colors.secondaryLabel,
        )
    }
}

