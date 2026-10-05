package tech.iflink.seuwiki.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.iflink.seuwiki.models.DocEntry
import tech.iflink.seuwiki.models.DocFilter
import tech.iflink.seuwiki.models.DocKind
import tech.iflink.seuwiki.models.DocPart
import tech.iflink.seuwiki.models.DocRef
import tech.iflink.seuwiki.models.FeedItem

/**
 * 手册 / 经验 / 搜索三类数据的状态容器。
 *
 * 与 [FeedStore] 同样的组织方式：状态放在普通字段里、用一个版本号驱动重组。
 * 但这里**不持有自己的 CoroutineScope** —— 搜索、手册加载都由调用方的
 * `LaunchedEffect` 驱动（搜索必须能被 debounce + `collectLatest` 取消，
 * 手册树则随详情页重建），store 本身只提供 suspend 方法与可观察状态。
 * 这一点和 [FeedStore] 相反是有意的：分页在 FeedStore 里、生命周期归 ViewModel，
 * 而这里的数据都是「一次请求一个结果」，不需要长期存活的 scope。
 *
 * 即便如此它也改成了 [ViewModel]（A-3）：手册树的 `detailCache` 是跨页面复用的
 * 缓存，挂在 `remember` 里的普通对象上时旋转一次就没了，用户会看到详情页闪一下
 * 空内容再重新加载。
 */
class DocsStore(
    private val docs: DocsApiClient = DocsApiClient(),
    private val feed: FeedApiClient = FeedApiClient(),
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DocsStore() }
        }
    }

    private val version = mutableStateOf(0)

    private fun bump() {
        version.value = version.value + 1
    }

    // MARK: - 生存手册树

    /** 真实文档树（篇 → 组 → 条），来自 `GET /api/site/docs/survival`。 */
    var handbook by mutableStateOf<List<DocPart>>(emptyList())
        private set

    var handbookLoading by mutableStateOf(false)
        private set

    var handbookError by mutableStateOf<String?>(null)
        private set

    /** 手册详情缓存，按 slug 存；返回详情页旋转后也不会退化成空页面。 */
    private val detailCache = mutableMapOf<String, DocsApiClient.DocDetail>()

    /** 展开到指定小节的请求，条目内缓存。 */
    fun cachedDetail(slug: String): DocsApiClient.DocDetail? = detailCache[slug]

    /** 按 slug 在手册树里查一个条目（手册页与深链都要用）。 */
    fun findEntry(slug: String): DocEntry? =
        handbook.asSequence().flatMap { it.entries }.firstOrNull { it.slug == slug }

    suspend fun loadHandbook(force: Boolean = false) {
        if (handbookLoading) return
        if (handbook.isNotEmpty() && !force) return
        handbookLoading = true
        handbookError = null
        bump()
        try {
            handbook = withContext(Dispatchers.IO) { docs.survival() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handbookError = "生存手册加载失败：${e.message ?: "网络异常"}"
        } finally {
            handbookLoading = false
            bump()
        }
    }

    suspend fun detail(slug: String): DocsApiClient.DocDetail {
        detailCache[slug]?.let { return it }
        val result = withContext(Dispatchers.IO) { docs.docDetail(slug) }
        detailCache[slug] = result
        return result
    }

    // MARK: - 经验长文

    /**
     * 经验长文列表与**服务端给的**分面筛选项。
     *
     * 筛选项一律用 `filters[]` 的真实值（实测：场景 7 项、年级 6 项、学院 4 项），
     * 不在客户端另编一套分类。
     */
    var experience by mutableStateOf<List<DocEntry>>(emptyList())
        private set

    var experienceFilters by mutableStateOf<List<DocFilter>>(emptyList())
        private set

    var experienceLoading by mutableStateOf(false)
        private set

    var experienceError by mutableStateOf<String?>(null)
        private set

    /** 当前生效的筛选条件，界面据此显示已选标签。 */
    var experienceSelection by mutableStateOf(ExperienceSelection())
        private set

    /**
     * 「成功但为空」和「失败」必须分开。
     *
     * 之前搜索页两者都回退到本地假数据且不提示，于是「搜不到」和「网断了」
     * 在界面上长得一模一样，用户以为是自己关键词不对。改成：失败 → [error]，
     * 成功但 0 命中 → [items] 为空且 [error] 为 null，由 UI 显示空态。
     */
    suspend fun loadExperience(selection: ExperienceSelection = experienceSelection, force: Boolean = false) {
        if (experienceLoading) return
        // 已加载过就直接复用，除非显式 force（下拉刷新）。
        if (experience.isNotEmpty() && selection == experienceSelection && !force) return
        experienceLoading = true
        experienceError = null
        experienceSelection = selection
        bump()
        try {
            val page = withContext(Dispatchers.IO) {
                docs.experience(
                    category = selection.category,
                    grade = selection.grade,
                    college = selection.college,
                )
            }
            experience = page.items
            experienceFilters = page.filters
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            experienceError = "经验加载失败：${e.message ?: "网络异常"}"
            // 保留上一批内容，别把用户已经看到的清空。
        } finally {
            experienceLoading = false
            bump()
        }
    }

    // MARK: - 统一搜索

    /** 搜索结果。三个信源全部来自 `GET /api/site/pool` 的一次响应。 */
    var searchResult by mutableStateOf(SearchResult())
        private set

    suspend fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResult = SearchResult()
            bump()
            return
        }
        searchResult = searchResult.copy(loading = true, error = null)
        bump()
        try {
            val page = withContext(Dispatchers.IO) { feed.pool(query) }
            // 三信源映射（两端必须一致）：
            //   资讯卡 = items；经验卡 = docs.filter{ kind==experience }；手册卡 = docs.filter{ kind==survival }
            searchResult = SearchResult(
                query = q,
                feed = page.items,
                experience = page.docs.filter { it.kind == DocKind.Experience },
                handbook = page.docs.filter { it.kind == DocKind.Survival },
                loading = false,
                error = null,
                hasSearched = true,
            )
        } catch (e: CancellationException) {
            // 取消是「没人要这个结果了」，不是失败：既不写错误也不清空，
            // 由下一次 collectLatest 的结果覆盖即可。
            throw e
        } catch (e: Exception) {
            searchResult = SearchResult(
                query = q,
                loading = false,
                error = "搜索失败：${e.message ?: "网络异常"}",
                hasSearched = true,
            )
        } finally {
            bump()
        }
    }

    fun clearSearch() {
        searchResult = SearchResult()
        bump()
    }
}

/**
 * 经验长文的三个分面筛选条件。
 *
 * 取值必须来自 [DocsStore.experienceFilters]，不要在代码里写死中文分类。
 */
data class ExperienceSelection(
    val category: String? = null,
    val grade: String? = null,
    val college: String? = null,
) {
    val isActive: Boolean
        get() = category != null || grade != null || college != null

    /** 已选条件的「分面名 → 值」，用于界面上的可删除标签。 */
    fun labels(filters: List<DocFilter>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        category?.let { v -> out += (filters.firstOrNull { it.key == "category" }?.label ?: "场景") to v }
        grade?.let { v -> out += (filters.firstOrNull { it.key == "grade" }?.label ?: "年级") to v }
        college?.let { v -> out += (filters.firstOrNull { it.key == "college" }?.label ?: "学院") to v }
        return out
    }
}

/**
 * 搜索结果。
 *
 * [hasSearched] 区分「还没搜过」与「搜过但 0 命中」：前者显示引导，后者显示空态。
 * [error] 只在**真的失败**时有值 —— 空结果不是错误。
 */
data class SearchResult(
    val query: String = "",
    val feed: List<FeedItem> = emptyList(),
    val experience: List<DocRef> = emptyList(),
    val handbook: List<DocRef> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
) {
    val total: Int get() = feed.size + experience.size + handbook.size

    /** 搜过、没报错、且三路都空 —— 空态文案与「未搜索」要分开。 */
    val isEmpty: Boolean
        get() = hasSearched && error == null && !loading && total == 0
}

/** 搜索结果里一个手册 / 经验条目的深链目标。 */
data class DocDeepLink(
    val ref: DocRef,
) {
    /** 正文锚点：命中的是小节而非整篇。 */
    val anchorId: String? get() = ref.anchor?.id
    val anchorText: String? get() = ref.anchor?.text
}
