package tech.iflink.seuwiki.data

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.ui.feed.FeedScope

/**
 * 资讯状态仓库，对应 iOS 端 `FeedStore`。
 *
 * 每个 console scope（为你精选 / 全部 / 各分类）各自维护一页 cursor 分页状态；
 * 网络失败时回退 MockData 并标记离线，不阻塞 UI —— 与 SwiftUI 侧 `catch` 分支
 * 的行为一致。
 *
 * 构造时传 [mockOnly] = true 可永远使用 MockData 且不发请求（Preview / 截图用）。
 */
class FeedStore(
    private val client: FeedApiClient = FeedApiClient(),
    private val mockOnly: Boolean = false,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) {

    /** One scope's pagination + load state. */
    data class PageState(
        val items: List<FeedItem> = emptyList(),
        val nextCursor: String? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasLoaded: Boolean = false,
        /** true 表示当前数据是网络失败后的 MockData 回退。 */
        val isOffline: Boolean = false,
    )

    private val states = mutableMapOf<String, PageState>()
    private val detailCache = mutableMapOf<String, RemoteFeedDetail>()

    /**
     * 每次状态变更自增，调用方读它即可让 Compose 重组。
     *
     * 用一个版本号而不是给每个 scope 挂 `mutableStateOf`，是因为 Map 本身不是可观察的。
     */
    private val version = mutableStateOf(0)

    private fun bump() {
        version.value = version.value + 1
    }

    fun page(scope: FeedScope): PageState = states[FeedScope.key(scope)] ?: PageState()

    /**
     * `/api/site/pool` 全站搜索，供搜索页的「通知」信源使用。
     *
     * 失败时返回空列表而不是抛出，让搜索页静默回退到本地匹配 —— 与 SwiftUI 侧
     * `SearchStore` 的失败回退一致。
     */
    suspend fun pool(query: String): List<FeedItem> = withContext(Dispatchers.IO) {
        runCatching { client.pool(query).items }.getOrDefault(emptyList())
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
    fun loadIfNeeded(scope: FeedScope, profile: FeedProfile) {
        val state = page(scope)
        if (state.hasLoaded || state.isLoading) return
        refresh(scope, profile)
    }

    /** 下拉刷新 / 首次加载：成功后重置分页，失败回退 MockData。 */
    fun refresh(scope: FeedScope, profile: FeedProfile) {
        if (page(scope).isLoading) return
        val key = FeedScope.key(scope)
        if (mockOnly) {
            states[key] = PageState(items = mockItems(scope), hasLoaded = true)
            bump()
            return
        }
        states[key] = page(scope).copy(isLoading = true)
        bump()
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetch(scope, profile, null) }
                states[key] = PageState(
                    items = result.items,
                    nextCursor = result.nextCursor,
                    hasLoaded = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 网络失败：保留已有内容，空则回退 MockData，并标记离线。
                val current = states[key] ?: PageState()
                states[key] = current.copy(
                    items = current.items.ifEmpty { mockItems(scope) },
                    nextCursor = null,
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
    fun loadMore(scope: FeedScope, profile: FeedProfile) {
        val state = page(scope)
        val cursor = state.nextCursor
        if (mockOnly || !state.hasLoaded || state.isLoading || state.isLoadingMore ||
            state.isOffline || cursor == null
        ) {
            return
        }
        val key = FeedScope.key(scope)
        states[key] = state.copy(isLoadingMore = true)
        bump()
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetch(scope, profile, cursor) }
                states[key]?.let { current ->
                    states[key] = current.copy(
                        items = current.items + result.items,
                        nextCursor = result.nextCursor,
                    )
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
        if (mockOnly) {
            return RemoteFeedDetail(
                originalTitle = null,
                summary = item.summary,
                reason = null,
                bodyHtml = null,
                originalUrl = item.originalUrl,
            )
        }
        val detail = withContext(Dispatchers.IO) { client.itemDetail(item.id) }
        detailCache[item.id] = detail
        return detail
    }

    // MARK: - Private

    private fun fetch(scope: FeedScope, profile: FeedProfile, cursor: String?): FeedPage =
        when (scope) {
            FeedScope.ForYou -> client.forYou(
                college = profile.college,
                degree = profile.degree,
                grade = profile.grade,
                interests = profile.interests,
                cursor = cursor,
            )

            FeedScope.All -> client.timeline(category = null, cursor = cursor)

            is FeedScope.Category -> client.timeline(category = scope.category, cursor = cursor)
        }

    /** 离线 / 截图兜底：命中理由优先在前，其余按时间倒序，与线上语义对齐。 */
    private fun mockItems(scope: FeedScope): List<FeedItem> = when (scope) {
        FeedScope.ForYou -> MockData.feedItems.sortedWith(
            compareByDescending<FeedItem> { it.matchReasons.isNotEmpty() }
                .thenByDescending { it.publishedAt ?: 0L },
        )

        FeedScope.All -> MockData.feedItems.sortedByDescending { it.publishedAt ?: 0L }

        is FeedScope.Category -> MockData.feedItems
            .filter { it.category == scope.category }
            .sortedByDescending { it.publishedAt ?: 0L }
    }

    /**
     * `/api/site/for-you` 需要的画像字段，够用即可。
     *
     * 只投影 `UserProfileStore` 上这几个字段而不是直接依赖整个 store，这样 store
     * 可以在没有 Android Context 的环境下构造。
     */
    data class FeedProfile(
        val college: String = "",
        val degree: String = "",
        val grade: String = "",
        val interests: List<String> = emptyList(),
    )
}

/** 把 [UserProfileStore] 的画像字段投影成 [FeedStore.FeedProfile]。 */
fun UserProfileStore.toFeedProfile(): FeedStore.FeedProfile = FeedStore.FeedProfile(
    college = college,
    degree = degree,
    grade = grade,
    interests = interests,
)
