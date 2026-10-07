package tech.iflink.seuwiki.data

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iflink.seuwiki.data.ForumApiClient.ForumApiException
import tech.iflink.seuwiki.models.ForumBookmarkPage
import tech.iflink.seuwiki.models.ForumBookmarkedPost
import tech.iflink.seuwiki.models.ForumComment
import tech.iflink.seuwiki.models.ForumCommentsPage
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.models.ForumTargetType
import tech.iflink.seuwiki.models.ForumViewer

/**
 * 论坛状态容器。
 *
 * 刻意做成 ViewModel 而不是自己 new 一个 CoroutineScope —— 请求挂在
 * [viewModelScope] 上，`onCleared()` 时统一取消，用户退出页面不会留一堆在途请求
 * 往回写状态（与 [FeedStore] 同理）。
 *
 * **登录态只存一份**：[ForumStore] 拿到的 [viewer] 就是 App 现有 Logto 会话在论坛侧
 * 的投影。用户从头到尾只登了一次：App 用 Logto 登录 → 同一个 access token 发给
 * 论坛 → 论坛返回 viewer。这里不做任何第二套登录流程。
 */
class ForumStore(
    private val client: ForumApiClient,
) : ViewModel() {

    companion object {
        /**
         * 工厂由 [RootView] 用真实的 AuthStore 构造 —— 这里需要注入 token，
         * 所以不能像 [FeedStore] 那样无参。
         */
        fun factory(tokenProvider: suspend () -> String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ForumStore(ForumApiClient(tokenProvider = tokenProvider)) }
            }
    }

    /** 帖子列表的加载态。 */
    data class ListState(
        val posts: List<ForumPost> = emptyList(),
        val nextCursor: String? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasLoaded: Boolean = false,
        /** 最近一次请求失败；界面展示的是**已加载到的旧内容**（或空）。 */
        val isOffline: Boolean = false,
        /** 需要登录才能做、但当前未登录。UI 据此引导登录，而不是报网络错误。 */
        val needsLogin: Boolean = false,
        val errorMessage: String? = null,
    )

    /** 帖子详情的加载态。 */
    data class DetailState(
        val post: ForumPost? = null,
        val comments: List<ForumComment> = emptyList(),
        val commentAuthors: Map<String, tech.iflink.seuwiki.models.ForumAuthor> = emptyMap(),
        val isLoading: Boolean = false,
        val isSendingComment: Boolean = false,
        val hasLoaded: Boolean = false,
        val needsLogin: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _list = mutableStateOf(ListState())
    val list: ListState get() = _list.value

    private val _detail = mutableStateOf(DetailState())
    val detail: DetailState get() = _detail.value

    /** 当前用户。`null` 有两种含义：**未登录**，或**还没查过**，用 [viewerLoaded] 区分。 */
    private val _viewer = mutableStateOf<ForumViewer?>(null)
    val viewer: ForumViewer? get() = _viewer.value

    private val _viewerLoaded = mutableStateOf(false)
    val viewerLoaded: Boolean get() = _viewerLoaded.value

    /** 发帖 / 发评论的结果提示，供 UI 消费一次后清空。 */
    private val _toast = mutableStateOf<String?>(null)
    val toast: String? get() = _toast.value

    /** 收藏列表。与手册条目的本地收藏（UserProfileStore.bookmarkedSlugs）是两回事。 */
    data class BookmarkState(
        val items: List<ForumBookmarkedPost> = emptyList(),
        val nextCursor: String? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasLoaded: Boolean = false,
        val isOffline: Boolean = false,
        /**
         * 401 时为 true。收藏是**纯服务端**接口，未登录必然 401 ——
         * 空态据此给「去登录」按钮，而不是把「登录后才能进行这个操作」摆在那里
         * 却没有任何办法照做。
         */
        val needsLogin: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _bookmarks = mutableStateOf(BookmarkState())
    val bookmarks: BookmarkState get() = _bookmarks.value

    private var currentSort = ForumSort.Latest
    private var currentTag: String? = null
    /** 列表「世代」。[refresh] 自增；在途的 [loadMore] 返回时世代变了就整份丢弃。 */
    private var generation = 0

    // MARK: - 列表

    /**
     * 拉列表。[tag] 非空时切到某个话题。
     *
     * 切话题/换排序都会**丢弃旧游标**：后端游标里编码了排序语义，
     * 拿旧 sort 的游标去请求会被判 `INVALID_CURSOR` 直接 400。
     */
    fun refresh(sort: ForumSort = currentSort, tag: String? = currentTag) {
        currentSort = sort
        currentTag = tag
        generation++
        _list.value = _list.value.copy(isLoading = true, isOffline = false, errorMessage = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.posts(sort, tag, null) } }
                .onSuccess { page ->
                    _list.value = ListState(
                        posts = page.posts,
                        nextCursor = page.nextCursor,
                        hasLoaded = true,
                    )
                }
                .onFailure { e ->
                    _list.value = _list.value.copy(
                        isLoading = false,
                        hasLoaded = true,
                        isOffline = true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    /** 翻下一页。没有游标说明已到末页，直接返回。 */
    fun loadMore() {
        val state = _list.value
        if (state.isLoading || state.isLoadingMore) return
        val cursor = state.nextCursor ?: return
        val genAtStart = generation
        _list.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.posts(currentSort, currentTag, cursor) }
            }
                .onSuccess { page ->
                    // 用户可能在途刷新过：世代变了就丢弃这份结果，否则会覆盖掉新列表。
                    if (genAtStart != generation) return@onSuccess
                    val current = _list.value
                    // 按 id 去重：游标分页在 likes_count 被并发改动时可能重复下发同一条。
                    val merged = (current.posts + page.posts).distinctBy { it.id }
                    _list.value = current.copy(
                        posts = merged,
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
                }
                .onFailure { e ->
                    if (genAtStart != generation) return@onFailure
                    _list.value = _list.value.copy(
                        isLoadingMore = false,
                        isOffline = true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    /** 当前是否处于话题视图（决定「查看全部」之类的入口该不该出现）。 */
    fun isFilteringByTag(): Boolean = currentTag != null

    // MARK: - 详情

    fun loadDetail(postId: String) {
        _detail.value = DetailState(isLoading = true)
        viewModelScope.launch {
            val postResult = runCatching {
                withContext(Dispatchers.IO) { client.postDetail(postId) }
            }
            postResult.onFailure { e ->
                _detail.value = DetailState(
                    isLoading = false,
                    hasLoaded = true,
                    needsLogin = (e as? ForumApiException)?.isUnauthorized == true,
                    errorMessage = describe(e),
                )
                return@onFailure
            }
            val post = postResult.getOrThrow()
            // 评论与帖子详情分开拉：评论失败不该让整页变成错误页。
            val comments = runCatching {
                withContext(Dispatchers.IO) { client.comments(postId) }
            }.getOrNull()
            _detail.value = DetailState(
                post = post,
                comments = comments?.comments.orEmpty(),
                commentAuthors = comments?.authors.orEmpty(),
                isLoading = false,
                hasLoaded = true,
            )
        }
    }

    fun sendComment(postId: String, content: String, onSent: () -> Unit) {
        val text = content.trim()
        if (text.isEmpty()) return
        _detail.value = _detail.value.copy(isSendingComment = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.createComment(postId, text) } }
                .onSuccess {
                    _detail.value = _detail.value.copy(isSendingComment = false)
                    _toast.value = "评论已发布"
                    onSent()
                    // 重拉评论列表拿到服务端权威顺序（升序）。
                    reloadComments(postId)
                }
                .onFailure { e ->
                    _detail.value = _detail.value.copy(
                        isSendingComment = false,
                        needsLogin = (e as? ForumApiException)?.isUnauthorized == true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    private fun reloadComments(postId: String) {
        viewModelScope.launch {
            val page: ForumCommentsPage = runCatching {
                withContext(Dispatchers.IO) { client.comments(postId) }
            }.getOrNull() ?: return@launch
            _detail.value = _detail.value.copy(
                comments = page.comments,
                commentAuthors = page.authors,
            )
        }
    }

    // MARK: - 互动（点赞 / 收藏）

    /**
     * 点赞 toggle。
     *
     * **先改本地再发请求**：接口本身就是 toggle，等响应回来才动会让连点两下只生效一次。
     * 服务端返回的 [ForumPost.likedByMe] 以响应为准；失败就把本地回滚。
     */
    fun toggleLike(postId: String) {
        val before = findPost(postId) ?: return
        applyPost(before.copy(likedByMe = !before.likedByMe, likesCount = before.likesCount.coerceAtLeast(0)))
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.toggleLike(postId) } }
                .onSuccess { liked ->
                    val now = findPost(postId) ?: return@onSuccess
                    // 同步计数：本地先 ±1，服务端给的是绝对真相，纠正可能的漂移。
                    applyPost(now.copy(likedByMe = liked))
                }
                .onFailure { e ->
                    val now = findPost(postId)
                    if (now != null) applyPost(now.copy(likedByMe = before.likedByMe, likesCount = before.likesCount))
                    if ((e as? ForumApiException)?.isUnauthorized == true) {
                        _toast.value = "登录后才能点赞"
                    }
                }
        }
    }

    fun toggleBookmark(postId: String) {
        val before = findPost(postId) ?: return
        applyPost(before.copy(bookmarked = !before.bookmarked))
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.toggleBookmark(postId) } }
                .onSuccess { marked ->
                    val now = findPost(postId) ?: return@onSuccess
                    applyPost(now.copy(bookmarked = marked))
                    _toast.value = if (marked) "已收藏" else "已取消收藏"
                }
                .onFailure { e ->
                    val now = findPost(postId)
                    if (now != null) applyPost(now.copy(bookmarked = before.bookmarked))
                    if ((e as? ForumApiException)?.isUnauthorized == true) {
                        _toast.value = "登录后才能收藏"
                    }
                }
        }
    }

    // MARK: - 发帖

    fun createPost(title: String?, content: String, tags: List<String>, onCreated: (String) -> Unit) {
        val body = content.trim()
        if (body.isEmpty()) {
            _toast.value = "内容不能为空"
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.createPost(title, body, tags) } }
                .onSuccess { id ->
                    _toast.value = "发布成功"
                    refresh()
                    onCreated(id)
                }
                .onFailure { e ->
                    _toast.value = when ((e as? ForumApiException)?.errorCode) {
                        "UNAUTHORIZED" -> "登录后才能发帖"
                        "CONTENT_REQUIRED", "INVALID_BODY" -> "内容格式不对"
                        "INVALID_TAGS" -> "话题选得不对"
                        else -> describe(e)
                    }
                }
        }
    }

    fun deleteContent(type: ForumTargetType, id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.deleteContent(type, id) } }
                .onSuccess {
                    _toast.value = "已删除"
                    onDone()
                }
                .onFailure { e -> _toast.value = describe(e) }
        }
    }

    // MARK: - 收藏

    fun refreshBookmarks() {
        _bookmarks.value = BookmarkState(isLoading = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.bookmarks() } }
                .onSuccess { page ->
                    _bookmarks.value = BookmarkState(
                        items = page.items,
                        nextCursor = page.nextCursor,
                        hasLoaded = true,
                    )
                }
                .onFailure { e ->
                    _bookmarks.value = BookmarkState(
                        isLoading = false,
                        hasLoaded = true,
                        isOffline = (e as? ForumApiException)?.isUnauthorized != true,
                        needsLogin = (e as? ForumApiException)?.isUnauthorized == true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    fun loadMoreBookmarks() {
        val state = _bookmarks.value
        if (state.isLoading || state.isLoadingMore) return
        val cursor = state.nextCursor ?: return
        _bookmarks.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.bookmarks(cursor) } }
                .onSuccess { page ->
                    val current = _bookmarks.value
                    // 服务端会剔除已删除的目标，所以同一页可能少于 limit 条但游标非空 ——
                    // 不能据此判定末页，只能靠游标为 null。
                    _bookmarks.value = current.copy(
                        items = (current.items + page.items).distinctBy { it.bookmarkId },
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
                }
                .onFailure { e ->
                    _bookmarks.value = _bookmarks.value.copy(
                        isLoadingMore = false,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    // MARK: - 身份

    /**
     * 查论坛侧身份。
     *
     * 用的就是 App 现有的 Logto token，所以这一步同时也是**「免登录是否打通」的
     * 自检**：能拿到 viewer 就说明后端认这个 token。
     */
    fun refreshViewer() {
        viewModelScope.launch {
            _viewer.value = runCatching {
                withContext(Dispatchers.IO) { client.me() }
            }.getOrNull()
            _viewerLoaded.value = true
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    // MARK: - 内部

    /** 在列表与详情之间按 id 找到同一份帖子，互动后两处一起更新。 */
    private fun findPost(id: String): ForumPost? =
        _detail.value.post?.takeIf { it.id == id }
            ?: _list.value.posts.firstOrNull { it.id == id }

    private fun applyPost(updated: ForumPost) {
        _detail.value = _detail.value.post
            ?.let { if (it.id == updated.id) _detail.value.copy(post = updated) else _detail.value }
            ?: _detail.value
        val list = _list.value
        if (list.posts.none { it.id == updated.id }) return
        _list.value = list.copy(posts = list.posts.map { if (it.id == updated.id) updated else it })
    }

    /**
     * 错误转成**能给用户看的**中文。
     *
     * 只回落到通用文案而不是把 [ForumApiException.message] 直接显示 —— 后端的
     * message 是给「接口调用方」看的，不一定适合终端用户。
     */
    private fun describe(e: Throwable): String = when (e) {
        is ForumApiException -> when {
            e.isUnauthorized -> "登录后才能进行这个操作"
            e.status == 403 -> "没有权限"
            e.status == 404 -> "内容不存在或已被删除"
            e.status >= 500 -> "服务器开小差了，稍后再试"
            else -> "请求失败（${e.status}）"
        }
        else -> "网络连接失败，检查网络后重试"
    }
}