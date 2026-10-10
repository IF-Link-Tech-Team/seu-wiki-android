package tech.iflink.seuwiki.data

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.ui.feed.FeedScope

/**
 * 资讯状态仓库，对应 iOS 端 `FeedStore`。
 *
 * 现在是 [ViewModel]（A-3）。之前是 `remember { FeedStore() }` 持有的普通单例，
 * 生命周期完全不受控：自己 new 了一个 `CoroutineScope(Dispatchers.Main.immediate)`
 * 却从不取消它，于是旋转屏幕后旧实例里仍在途的分页请求会把结果写进**已经没人
 * 观察的状态**里，白白消耗流量；而且分页状态因此在配置变更后从头重来。
 * 换成 ViewModel 之后请求挂在 [viewModelScope] 上，`onCleared` 时统一取消，
 * 实例本身由 `viewModel()` 在配置变更时保留。
 *
 * 每个 console scope（精选 / 一手 / 各分类 / 全部）各自维护一份分页状态。
 * 「精选」走 timeline（cursor 分页），其余 scope 走 pool（page 分页，
 * `hasMore = page < pageCount`），两种分页在同一套 [PageState] 里并存。
 *
 * **网络失败不再回退假数据**（S-10）。原来的实现会静默塞一份 `MockData` 顶上，
 * 于是「断网了」和「这个分类今天没新内容」在界面上长得一模一样，用户完全无从
 * 分辨。现在失败就是失败：保留已有内容、置 [PageState.isOffline]，
 * 交由 UI 显示可重试的错误态。
 *
 * 刷新（[refresh]）与加载更多（[loadMore]）可能并发。两个请求都打同一批接口，
 * 只有 refresh 会重置列表 —— 如果 loadMore 那一页在 refresh 之后才回来，
 * 它就会被**追加到已经换过的新列表后面**，出现重复与错序。每个 scope 带一个
 * generation 计数，请求返回时先比对，不一致就整份丢弃。
 */
class FeedStore(
    private val client: FeedApiClient = FeedApiClient(),
) : ViewModel() {

    companion object {
        /** 无依赖，工厂只是为了让 `viewModel()` 有地方拿默认构造。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { FeedStore() }
        }
    }

    /** One scope's pagination + load state. */
    data class PageState(
        val items: List<FeedItem> = emptyList(),
        /** timeline（精选）的 keyset 游标；pool 系 scope 恒为 null。 */
        val nextCursor: String? = null,
        /** pool 系 scope（一手 / 分类 / 全部）的下一页页码；精选恒为 null。 */
        val nextPage: Int? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasLoaded: Boolean = false,
        /** true 表示最近一次请求失败，当前展示的是**已加载到的旧内容**（或空）。 */
        val isOffline: Boolean = false,
    ) {
        /** 两种分页形态统一成一个「还能再翻」判据。 */
        val hasMore: Boolean get() = nextCursor != null || nextPage != null
    }

    private val states = mutableMapOf<String, PageState>()
    private val detailCache = mutableMapOf<String, RemoteFeedDetail>()

    /**
     * 每个 scope 的「列表世代」。只有 [refresh] 会自增（它换掉了整份列表）；
     * 在途的 [loadMore] 返回时若发现世代已经变了，说明用户在这期间刷新过，
     * 自己拿到的那一页属于**旧列表**，必须丢弃而不是追加。
     */
    private val generations = mutableMapOf<String, Long>()

    /**
     * 每次状态变更自增，调用方读它即可让 Compose 重组。
     *
     * 用一个版本号而不是给每个 scope 挂 `mutableStateOf`，是因为 Map 本身不是可观察的。
     */
    private val version = mutableStateOf(0)

    private fun bump() {
        version.value = version.value + 1
    }

    /**
     * 读某个 scope 的分页状态。
     *
     * 这里必须先读一次 [version]：scope 状态存在普通 `MutableMap` 里，本身不是快照可观察的，
     * 只有在 composition 里读到 `version` 才会在 [bump] 之后触发重组。少了这一行，
     * 冷启动请求返回后界面不会自己刷新（切 Tab 顺带重组会掩盖掉这个问题）。
     */
    fun page(scope: FeedScope): PageState {
        @Suppress("UNUSED_EXPRESSION")
        version.value
        return states[FeedScope.key(scope)] ?: PageState()
    }

    /**
     * `/api/site/pool` 全站搜索，供搜索页的「通知」信源使用。
     *
     * 失败时返回空列表而不是抛出，让搜索页静默回退到本地匹配 —— 与 SwiftUI 侧
     * `SearchStore` 的失败回退一致。
     */
    suspend fun pool(query: String): List<FeedItem> = withContext(Dispatchers.IO) {
        try {
            client.pool(query).items
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 按 id 在已加载的所有 scope 里找条目。
     *
     * 详情路由传的是 id 而不是整条数据（iOS 靠 `NavigationLink(value: item)` 传值），
     * 所以进详情时要能在列表状态里反查。列表响应不含 `links.original`，原文链接
     * 由详情接口补齐，与 iOS 的处理一致。
     */
    fun findItem(id: String): FeedItem? =
        states.values.firstNotNullOfOrNull { state -> state.items.firstOrNull { it.id == id } }

    /** 首次进入某个 scope 时加载；已加载过的直接复用。 */
    fun loadIfNeeded(scope: FeedScope) {
        val state = page(scope)
        if (state.hasLoaded || state.isLoading) return
        refresh(scope)
    }

    /** 下拉刷新 / 首次加载：成功后重置分页，失败保留旧内容并标记离线。 */
    fun refresh(scope: FeedScope) {
        if (page(scope).isLoading) return
        val key = FeedScope.key(scope)
        // 换代：让此刻仍在途的 loadMore 失效。
        generations[key] = (generations[key] ?: 0L) + 1L
        states[key] = page(scope).copy(isLoading = true)
        bump()
        viewModelScope.launch {
            try {
                if (scope == FeedScope.Featured) {
                    val result = withContext(Dispatchers.IO) {
                        client.timeline(category = null, cursor = null)
                    }
                    states[key] = PageState(
                        items = result.items.distinctBy { it.id },
                        nextCursor = result.nextCursor,
                        hasLoaded = true,
                    )
                } else {
                    val result = withContext(Dispatchers.IO) { fetchPool(scope, page = 1) }
                    states[key] = PageState(
                        items = result.items.distinctBy { it.id },
                        nextPage = if (result.hasMore) result.page + 1 else null,
                        hasLoaded = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络失败：**保留已有内容**，不塞假数据，并标记离线让 UI 给重试入口。
                val current = states[key] ?: PageState()
                states[key] = current.copy(
                    nextCursor = null,
                    nextPage = null,
                    hasLoaded = true,
                    isOffline = true,
                )
            } finally {
                states[key]?.let { states[key] = it.copy(isLoading = false) }
                bump()
            }
        }
    }

    /** 滚动到底加载下一页。失败静默，下次滚到底会再试。 */
    fun loadMore(scope: FeedScope) {
        val state = page(scope)
        if (!state.hasLoaded || state.isLoading || state.isLoadingMore ||
            state.isOffline || !state.hasMore
        ) {
            return
        }
        val key = FeedScope.key(scope)
        val generationAtStart = generations[key] ?: 0L
        states[key] = state.copy(isLoadingMore = true)
        bump()
        viewModelScope.launch {
            try {
                // 用户在请求在途时刷新过：这一页属于旧列表，丢掉。
                if (scope == FeedScope.Featured) {
                    val result = withContext(Dispatchers.IO) {
                        client.timeline(category = null, cursor = state.nextCursor)
                    }
                    if ((generations[key] ?: 0L) != generationAtStart) return@launch
                    appendPage(key) { current ->
                        current.copy(
                            items = (current.items + result.items).distinctBy { it.id },
                            nextCursor = result.nextCursor,
                        )
                    }
                } else {
                    val nextPage = state.nextPage ?: return@launch
                    val result = withContext(Dispatchers.IO) { fetchPool(scope, nextPage) }
                    if ((generations[key] ?: 0L) != generationAtStart) return@launch
                    appendPage(key) { current ->
                        current.copy(
                            items = (current.items + result.items).distinctBy { it.id },
                            nextPage = if (result.hasMore) result.page + 1 else null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默失败，保留当前列表。
            } finally {
                states[key]?.let { states[key] = it.copy(isLoadingMore = false) }
                bump()
            }
        }
    }

    /**
     * 资讯详情（body / originalURL 等），按 id 缓存。
     *
     * 失败时抛出，由详情页回退展示列表里那条 [FeedItem] 的摘要 —— 与 SwiftUI 侧
     * `detail = try? await store.detail(for: item)` 的静默回退一致。
     */
    suspend fun detail(item: FeedItem): RemoteFeedDetail {
        detailCache[item.id]?.let { return it }
        val detail = withContext(Dispatchers.IO) { client.itemDetail(item.id) }
        detailCache[item.id] = detail
        return detail
    }

    // MARK: - Private

    /**
     * pool 系 scope 的路由（网页端 2026-10-08 重构后的约定）：
     * 一手 → `pool?channel=firstParty`，分类 → `pool?category=<key>`，全部 → `pool`。
     * 全量无门槛，page 分页，`hasMore = page < pageCount`。
     */
    private suspend fun fetchPool(scope: FeedScope, page: Int): PoolPage = when (scope) {
        FeedScope.FirstParty -> client.pool(channel = "firstParty", page = page)
        is FeedScope.Category -> client.pool(category = scope.category, page = page)
        FeedScope.All -> client.pool(page = page)
        // 精选走 timeline（cursor），不会到这里。
        FeedScope.Featured -> error("Featured 是 timeline scope，不该走 pool")
    }

    /**
     * 追加一页结果。
     *
     * 去重在调用方的 update 里做（`distinctBy { it.id }`）：cursor 分页在条目
     * 插入/删除时可能重复返回同一条，而 LazyColumn 的 `key` 一旦重复**直接抛
     * IllegalArgumentException 崩掉**（A-2 / S-9）。
     */
    private inline fun appendPage(key: String, update: (PageState) -> PageState) {
        states[key]?.let { current -> states[key] = update(current) }
    }
}
