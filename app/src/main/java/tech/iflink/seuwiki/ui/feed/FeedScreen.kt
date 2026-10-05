package tech.iflink.seuwiki.ui.feed

import androidx.compose.ui.res.stringArrayResource
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
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
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.FeedItemCard
import tech.iflink.seuwiki.ui.rows.ForYouCard

/** 资讯 console scope — 为你精选 / 全部 / the 8 categories. */
sealed interface FeedScope {
    /** 显示名走资源，编译器保证 key 存在，不会静默渲染成空白胶囊。 */
    @get:StringRes
    val titleRes: Int

    data object ForYou : FeedScope {
        override val titleRes = R.string.feed_scope_for_you
    }

    data object All : FeedScope {
        override val titleRes = R.string.feed_filter_all
    }

    data class Category(val category: FeedCategory) : FeedScope {
        override val titleRes: Int get() = category.labelRes
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val listState = rememberLazyListState()

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
                title = stringResource(R.string.feed_title),
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
                                contentDescription = stringResource(R.string.cd_filter),
                                tint = SeuTheme.colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(stringResource(R.string.feed_filter), style = SeuType.Subheadline, color = SeuTheme.colors.accent)
                        }
                    }
                },
            )

            ConsoleBar(
                items = FeedScope.scopes,
                selection = scope,
                onSelect = { scopeKey = FeedScope.key(it) },
                title = { stringResource(it.titleRes) },
            )

            // 筛选只在「全部」下生效，与 SwiftUI 的 allItems 一致。
            val visible = if (scope == FeedScope.All) {
                page.items.filter { filter.matches(it, profile) }
            } else {
                page.items
            }
            val emptyMessage = when (scope) {
                FeedScope.All ->
                    if (filter.isActive) stringResource(R.string.feed_empty_filtered) else stringResource(R.string.feed_empty)
                is FeedScope.Category -> stringResource(R.string.feed_empty_category)
                FeedScope.ForYou -> stringResource(R.string.feed_empty_for_you)
            }

            if (visible.isEmpty() && page.isLoading) {
                LoadingView(topPadding = 80.dp)
            } else if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = stringResource(R.string.feed_empty),
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
                PullToRefreshBox(
                    // 下拉真的重拉第一页，不是假动画：以前只能退到「全部」再切回来
                    // 才能刷，资讯流这种高频场景下很难用。
                    isRefreshing = page.isLoading,
                    onRefresh = { store.refresh(scope, feedProfile) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                LazyColumn(
                    state = listState,
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
                }
                // 滚到底加载下一页，对应 SwiftUI 的 `.onAppear { if item.id == last { loadMore } }`。
                // 必须由滚动位置驱动：挂在列表外按 `visible.size` 触发会变成「一有数据就再拉一页」，
                // 冷启动就把整条 for-you 链路一次性拉完。
                val nearEnd by remember { derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= 0 && last >= info.totalItemsCount - 2
                } }
                LaunchedEffect(nearEnd, page.nextCursor) {
                    if (nearEnd && page.nextCursor != null) {
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
                Text(stringResource(R.string.feed_filter), style = SeuType.Title3, color = colors.label)
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.feed_filter_done),
                    style = SeuType.SubheadlineMedium,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Column(
                // 固定 420.dp 在矮屏 / 横屏下会把筛选内容顶出视口；改成「最高 420dp、
                // 空间不够就收缩 + 内部滚动」。
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                FilterSectionTitle(stringResource(R.string.feed_filter_college))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.feed_filter_only_my_college),
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

                FilterSectionTitle(stringResource(R.string.feed_filter_degree))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(stringResource(R.string.feed_filter_all), filter.degree == null) {
                        onChange(filter.copy(degree = null))
                    }
                    stringArrayResource(R.array.feed_filter_degrees).forEach { degree ->
                        FilterChip(degree, filter.degree == degree) {
                            onChange(filter.copy(degree = degree))
                        }
                    }
                }

                FilterSectionTitle(stringResource(R.string.feed_filter_category))
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
                            text = stringResource(category.labelRes),
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
                        text = stringResource(R.string.feed_filter_clear),
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
 * 不阻塞浏览：列表保留已加载到的旧内容，失败时给出重试入口。
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
            text = stringResource(R.string.feed_offline_banner),
            style = SeuType.Caption,
            color = colors.secondaryLabel,
        )
    }
}

