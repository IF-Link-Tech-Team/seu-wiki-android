package tech.iflink.seuwiki.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ForumSearchArticle
import tech.iflink.seuwiki.models.ForumSearchPost
import tech.iflink.seuwiki.models.UserFacingError

/**
 * 搜索状态容器：一次查询打**两组各自独立的信源**，并发、各自成败。
 *
 * - 资讯：`GET seu.wiki/api/site/pool?type=feed`（只留资讯，docs 桶已随
 *   经验长文/文档树信源一并移除）；
 * - 帖子 + 手册文章：`GET forum.seu.wiki/api/search?q=&type=all`（匿名可读，
 *   空关键词后端直接 400 `EMPTY_QUERY`，所以 trim 后为空时根本不发请求）。
 *
 * 刻意**不持有自己的 CoroutineScope**（与旧 [DocsStore] 同一模式）：搜索由调用方的
 * debounce + `collectLatest` 驱动，新关键词到达时在途请求必须能被取消，
 * store 只提供 suspend 方法与可观察状态。
 */
class SearchStore(
    private val forum: ForumApiClient,
    private val feed: FeedApiClient = FeedApiClient(),
) : ViewModel() {

    companion object {
        /** 论坛搜索匿名可读，但带上 token 也无害（与 ForumStore 同一个 provider）。 */
        fun factory(tokenProvider: suspend () -> String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SearchStore(ForumApiClient(tokenProvider = tokenProvider)) }
            }
    }

    data class State(
        val query: String = "",
        val feed: List<FeedItem> = emptyList(),
        /** 论坛搜索结果（`posts` 桶）：是 snippet 投影，不是 [ForumPost]。 */
        val posts: List<ForumSearchPost> = emptyList(),
        /** 手册文章搜索结果（`articles` 桶）。 */
        val articles: List<ForumSearchArticle> = emptyList(),
        val loading: Boolean = false,
        /** 资讯这一组失败了；另一组照常显示。 */
        val feedError: UserFacingError? = null,
        /** 论坛搜索这一组失败了；另一组照常显示。 */
        val forumError: UserFacingError? = null,
        val hasSearched: Boolean = false,
    ) {
        val total: Int get() = feed.size + posts.size + articles.size

        /** 搜过、两组都没报错、且三桶全空 —— 真正的「没搜到」。 */
        val isEmpty: Boolean
            get() = hasSearched && !loading && total == 0 && feedError == null && forumError == null

        /** 两组全挂了才算整体失败，给整页错误态 + 重试。 */
        val allFailed: Boolean
            get() = hasSearched && !loading && feedError != null && forumError != null
    }

    var state by mutableStateOf(State())
        private set

    /**
     * 并发打两组信源，**各自成败**：一组失败只记自己的错误字段，不拖垮另一组
     * （论坛不可达时通知搜索照样该出结果）。
     */
    suspend fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            state = State()
            return
        }
        state = state.copy(query = q, loading = true, feedError = null, forumError = null)
        coroutineScope {
            val feedJob = async {
                runCatching { withContext(Dispatchers.IO) { feed.pool(q, type = "feed") } }
            }
            val forumJob = async {
                runCatching { withContext(Dispatchers.IO) { forum.search(q) } }
            }
            val feedResult = feedJob.await()
            val forumResult = forumJob.await()
            state = State(
                query = q,
                feed = feedResult.getOrNull()?.items.orEmpty(),
                feedError = feedResult.exceptionOrNull()?.let {
                    UserFacingError(R.string.search_feed_failed, it.message.orEmpty())
                },
                posts = forumResult.getOrNull()?.posts.orEmpty(),
                articles = forumResult.getOrNull()?.articles.orEmpty(),
                forumError = forumResult.exceptionOrNull()?.let {
                    UserFacingError(R.string.search_forum_failed, it.message.orEmpty())
                },
                hasSearched = true,
            )
        }
    }

    fun clear() {
        state = State()
    }
}
