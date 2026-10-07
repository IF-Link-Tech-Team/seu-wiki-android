package tech.iflink.seuwiki.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文档条目详情的状态容器。
 *
 * 手册树（`loadHandbook`）、经验长文（`loadExperience`）与统一搜索（`search`）
 * 已随 `/api/site/docs` 信源切换移除：手册改走论坛后端 `/api/handbook/` 系列接口
 * （[ForumStore]），搜索改走 [SearchStore]。这里只剩**按 slug 的条目详情缓存**——
 * 收藏列表与深链仍会打开旧文档条目（[ui.detail.DocEntryDetailScreen]）。
 *
 * 改成 [ViewModel] 的原因（A-3）：`detailCache` 是跨页面复用的缓存，挂在
 * `remember` 里的普通对象上时旋转一次就没了，用户会看到详情页闪一下空内容
 * 再重新加载。
 */
class DocsStore(
    private val docs: DocsApiClient = DocsApiClient(),
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DocsStore() }
        }
    }

    /** 详情缓存，按 slug 存；返回详情页旋转后也不会退化成空页面。 */
    private val detailCache = mutableMapOf<String, DocsApiClient.DocDetail>()

    fun cachedDetail(slug: String): DocsApiClient.DocDetail? = detailCache[slug]

    suspend fun detail(slug: String): DocsApiClient.DocDetail {
        detailCache[slug]?.let { return it }
        val result = withContext(Dispatchers.IO) { docs.docDetail(slug) }
        detailCache[slug] = result
        return result
    }
}
