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
import tech.iflink.seuwiki.models.FollowedTag
import tech.iflink.seuwiki.models.ForumBookmarkPage
import tech.iflink.seuwiki.models.ForumBookmarkedPost
import tech.iflink.seuwiki.models.ForumComment
import tech.iflink.seuwiki.models.ForumCommentsPage
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.models.ForumTargetType
import tech.iflink.seuwiki.models.ForumViewer
import tech.iflink.seuwiki.models.HandbookArticle
import tech.iflink.seuwiki.models.HandbookSectionInfo
import tech.iflink.seuwiki.models.HandbookSectionPage
import tech.iflink.seuwiki.models.SuggestedTopic

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

    /** 帖子列表的加载态。[nextOffset] 仅热榜使用（offset 分页），与 nextCursor 互斥。 */
    data class ListState(
        val posts: List<ForumPost> = emptyList(),
        val nextCursor: String? = null,
        val nextOffset: Int? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasLoaded: Boolean = false,
        /** 最近一次请求失败；界面展示的是**已加载到的旧内容**（或空）。 */
        val isOffline: Boolean = false,
        /** 需要登录才能做、但当前未登录。UI 据此引导登录，而不是报网络错误。 */
        val needsLogin: Boolean = false,
        val errorMessage: String? = null,
    ) {
        /** 还有没有下一页：游标或 offset 任一非空。 */
        val hasMore: Boolean get() = nextCursor != null || nextOffset != null
    }

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

    /** 热榜（sort=hot，置顶优先）。独立于 [_list]：它是 offset 分页，游标体系不同。 */
    private val _hot = mutableStateOf(ListState())
    val hot: ListState get() = _hot.value

    /** 关注流（/api/feed/following，需登录）。 */
    private val _following = mutableStateOf(ListState())
    val following: ListState get() = _following.value

    /** 我关注的板块标签。空 ≠ 未加载，用 [followsLoaded] 区分。 */
    private val _followedTags = mutableStateOf<List<FollowedTag>>(emptyList())
    val followedTags: List<FollowedTag> get() = _followedTags.value

    /** 零关注时服务端下发的推荐主题（slug/name/post_count）。 */
    private val _suggestedTags = mutableStateOf<List<SuggestedTopic>>(emptyList())
    val suggestedTags: List<SuggestedTopic> get() = _suggestedTags.value

    private val _followsLoaded = mutableStateOf(false)
    val followsLoaded: Boolean get() = _followsLoaded.value

    // 手册
    private val _handbookSections = mutableStateOf<List<HandbookSectionInfo>?>(null)
    val handbookSections: List<HandbookSectionInfo>? get() = _handbookSections.value

    private val _handbookLoading = mutableStateOf(false)
    val handbookLoading: Boolean get() = _handbookLoading.value

    private val _handbookError = mutableStateOf<String?>(null)
    val handbookError: String? get() = _handbookError.value

    /** 板块详情缓存：从列表进详情再返回，不该整页重新转圈。 */
    private val handbookSectionCache = mutableMapOf<String, HandbookSectionPage>()
    private val handbookArticleCache = mutableMapOf<String, HandbookArticle>()

    private var hotGeneration = 0
    private var followingGeneration = 0

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

    // MARK: - 热榜（sort=hot，offset 分页）

    fun loadHotIfNeeded() {
        if (_hot.value.hasLoaded || _hot.value.isLoading) return
        refreshHot()
    }

    fun refreshHot() {
        hotGeneration++
        _hot.value = _hot.value.copy(isLoading = true, isOffline = false, errorMessage = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.posts(ForumSort.Hot) } }
                .onSuccess { page ->
                    _hot.value = ListState(
                        posts = page.posts,
                        nextOffset = page.nextOffset,
                        hasLoaded = true,
                    )
                }
                .onFailure { e ->
                    _hot.value = _hot.value.copy(
                        isLoading = false,
                        hasLoaded = true,
                        isOffline = true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    /** 热榜翻页：传 offset 而不是游标（对 hot 传 cursor 后端 400）。 */
    fun loadMoreHot() {
        val state = _hot.value
        if (state.isLoading || state.isLoadingMore) return
        val offset = state.nextOffset ?: return
        val genAtStart = hotGeneration
        _hot.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.posts(ForumSort.Hot, offset = offset) }
            }
                .onSuccess { page ->
                    if (genAtStart != hotGeneration) return@onSuccess
                    val current = _hot.value
                    _hot.value = current.copy(
                        posts = (current.posts + page.posts).distinctBy { it.id },
                        nextOffset = page.nextOffset,
                        isLoadingMore = false,
                    )
                }
                .onFailure { e ->
                    if (genAtStart != hotGeneration) return@onFailure
                    _hot.value = _hot.value.copy(
                        isLoadingMore = false,
                        isOffline = true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    // MARK: - 关注（/api/follows + /api/feed/following）

    /** 拉取我关注的板块标签。401 时不算错误 —— 未登录就是没有关注。 */
    fun loadFollows() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.followedTags() } }
                .onSuccess { tags ->
                    _followedTags.value = tags
                    _followsLoaded.value = true
                }
                .onFailure { e ->
                    _followsLoaded.value = true
                    if ((e as? ForumApiException)?.isUnauthorized == true) {
                        _followedTags.value = emptyList()
                    }
                }
        }
    }

    fun isFollowingTag(slug: String): Boolean = _followedTags.value.any { it.slug == slug }

    /**
     * 关注/取关 toggle：**先改本地再发请求**（与 toggleLike 同理），失败回滚。
     * 成功后顺手刷新关注流，让新关注板块的帖子进来。
     */
    fun toggleFollowTag(slug: String) {
        val before = _followedTags.value
        val nowOn = before.none { it.slug == slug }
        _followedTags.value = if (nowOn) {
            before + FollowedTag(slug = slug, name = slug, topicSlug = null)
        } else {
            before.filterNot { it.slug == slug }
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.toggleFollowTag(slug) } }
                .onSuccess { followed ->
                    if (followed == nowOn) {
                        // 与服务端一致：刷新关注流让变化立刻可见。
                        refreshFollowing()
                    } else {
                        // 本地猜反了（冷启动状态漂移），以服务端为准。
                        if (followed) {
                            _followedTags.value = _followedTags.value +
                                FollowedTag(slug = slug, name = slug, topicSlug = null)
                        } else {
                            _followedTags.value = _followedTags.value.filterNot { it.slug == slug }
                        }
                        refreshFollowing()
                    }
                }
                .onFailure { e ->
                    _followedTags.value = before
                    _toast.value = if ((e as? ForumApiException)?.isUnauthorized == true) {
                        "登录后才能关注"
                    } else {
                        describe(e)
                    }
                }
        }
    }

    fun loadFollowingIfNeeded() {
        if (_following.value.hasLoaded || _following.value.isLoading) return
        refreshFollowing()
    }

    /**
     * 关注流首页。401 是**正常路径**（未登录），不是网络错误：
     * needsLogin = true，UI 给登录引导而不是重试按钮。
     */
    fun refreshFollowing() {
        followingGeneration++
        _following.value = _following.value.copy(
            isLoading = true, isOffline = false, needsLogin = false, errorMessage = null,
        )
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.followingFeed() } }
                .onSuccess { page ->
                    _following.value = ListState(
                        posts = page.posts,
                        nextCursor = page.nextCursor,
                        hasLoaded = true,
                    )
                    if (page.suggestedTags.isNotEmpty()) _suggestedTags.value = page.suggestedTags
                }
                .onFailure { e ->
                    val unauthorized = (e as? ForumApiException)?.isUnauthorized == true
                    _following.value = _following.value.copy(
                        isLoading = false,
                        hasLoaded = true,
                        isOffline = !unauthorized,
                        needsLogin = unauthorized,
                        errorMessage = if (unauthorized) null else describe(e),
                    )
                }
        }
    }

    fun loadMoreFollowing() {
        val state = _following.value
        if (state.isLoading || state.isLoadingMore) return
        val cursor = state.nextCursor ?: return
        val genAtStart = followingGeneration
        _following.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.followingFeed(cursor) } }
                .onSuccess { page ->
                    if (genAtStart != followingGeneration) return@onSuccess
                    val current = _following.value
                    _following.value = current.copy(
                        posts = (current.posts + page.posts).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
                }
                .onFailure { e ->
                    if (genAtStart != followingGeneration) return@onFailure
                    _following.value = _following.value.copy(
                        isLoadingMore = false,
                        isOffline = true,
                        errorMessage = describe(e),
                    )
                }
        }
    }

    // MARK: - 手册

    fun loadHandbookSections(force: Boolean = false) {
        if (_handbookLoading.value) return
        if (_handbookSections.value != null && !force) return
        _handbookLoading.value = true
        _handbookError.value = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.handbookSections() } }
                .onSuccess { _handbookSections.value = it }
                .onFailure { e -> _handbookError.value = describe(e) }
            _handbookLoading.value = false
        }
    }

    /** 板块详情（含文章列表），按 slug 缓存。失败抛出由页面显示错误态。 */
    suspend fun handbookSection(slug: String): HandbookSectionPage {
        handbookSectionCache[slug]?.let { return it }
        val page = withContext(Dispatchers.IO) { client.handbookSection(slug) }
        handbookSectionCache[slug] = page
        return page
    }

    /** 文章详情，按 id 缓存。 */
    suspend fun handbookArticle(id: String): HandbookArticle {
        handbookArticleCache[id]?.let { return it }
        val article = withContext(Dispatchers.IO) { client.handbookArticle(id) }
        handbookArticleCache[id] = article
        return article
    }

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
            // 浏览计数 +1：匿名也可调，**失败静默**（client 内部已吞掉异常返回 null），
            // 拿到服务端权威值就更新详情里的数字。
            val views = withContext(Dispatchers.IO) { client.incrementView(postId) }
            if (views != null) {
                _detail.value.post?.takeIf { it.id == postId }?.let { current ->
                    _detail.value = _detail.value.copy(post = current.copy(viewsCount = views))
                }
            }
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

    fun createPost(
        title: String?,
        content: String,
        tags: List<String>,
        images: List<ByteArray> = emptyList(),
        onCreated: (String) -> Unit,
    ) {
        val body = content.trim()
        if (body.isEmpty()) {
            _toast.value = "内容不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val id = client.createPost(title, body, tags)
                    // 顺序与 Web 端 PostEditor 一致：先建帖拿 id 再传图。
                    // 图片失败不拦发帖 —— 帖已在，可再进编辑补传。
                    images.forEach { bytes ->
                        runCatching { client.uploadPostImage(id, bytes) }
                    }
                    id
                }
            }
                .onSuccess { id ->
                    _toast.value = "发布成功"
                    invalidateFeeds()
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

    /** 发帖后让三条信息流都作废重拉：新帖该出现在最新与热榜里。 */
    fun invalidateFeeds() {
        refresh()
        refreshHot()
        if (_following.value.hasLoaded) refreshFollowing()
    }

    fun deleteContent(type: ForumTargetType, id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.deleteContent(type, id) } }
                .onSuccess {
                    _toast.value = "已删除"
                    invalidateFeeds()
                    onDone()
                }
                .onFailure { e -> _toast.value = describe(e) }
        }
    }

    /**
     * 作者编辑自己的帖子。成功后刷新详情与信息流 —— 标题变了列表卡片也要变。
     * 标签后端不让改，这里不暴露。
     */
    fun updatePost(
        postId: String,
        title: String?,
        content: String,
        images: List<ByteArray> = emptyList(),
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.updatePost(postId, title, content)
                    // 新增配图：编辑模式同样先保存正文再传图，失败不拦。
                    images.forEach { bytes ->
                        runCatching { client.uploadPostImage(postId, bytes) }
                    }
                }
            }
                .onSuccess {
                    _toast.value = "已保存"
                    loadDetail(postId)
                    invalidateFeeds()
                    onDone()
                }
                .onFailure { e ->
                    _toast.value = when ((e as? ForumApiException)?.errorCode) {
                        "UNAUTHORIZED" -> "登录后才能编辑"
                        "FORBIDDEN" -> "只能编辑自己的帖子"
                        "INVALID_BODY" -> "内容格式不对"
                        else -> describe(e)
                    }
                }
        }
    }

    /**
     * 管理删除（owner/admin/moderator，能力 `admin:content:delete`）。
     * 入口可见性由 UI 按 viewer.capabilities 控制，真正的门禁在服务端 ——
     * 越权调用只会吃到 403，不会出事。
     */
    fun adminDeleteContent(type: ForumTargetType, id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.adminDeleteContent(type, id) } }
                .onSuccess {
                    _toast.value = "已删除（管理操作）"
                    invalidateFeeds()
                    onDone()
                }
                .onFailure { e ->
                    _toast.value = when ((e as? ForumApiException)?.errorCode) {
                        "UNAUTHORIZED" -> "登录后才能进行这个操作"
                        "FORBIDDEN" -> "没有内容管理权限"
                        else -> describe(e)
                    }
                }
        }
    }

    /** 当前登录用户在论坛侧是否持有某个管理能力。未登录/未拉到 viewer 时一律 false。 */
    fun hasCapability(capability: String): Boolean =
        _viewer.value?.capabilities?.contains(capability) == true

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

    // MARK: - 登录态生命周期（由 RootView 的统一监听调用，约定见仓库 AGENTS.md）

    /**
     * 登录成功（含冷启动时已有会话）后调用：拉取论坛侧身份投影；
     * 已加载过的用户态数据顺带刷新，让新账号看到自己的能力与内容。
     */
    fun onSignedIn() {
        refreshViewer()
        if (_bookmarks.value.hasLoaded) refreshBookmarks()
        if (_following.value.hasLoaded) refreshFollowing()
        if (_followsLoaded.value) loadFollows()
    }

    /**
     * 登出后调用：清空**全部**用户态数据（viewer、收藏、关注流、关注的标签），
     * 防止下一个账号看到上一个账号的残留。公共信息流（最新/热榜/手册）匿名可读，
     * 列表本身保留，但帖子上带的 `likedByMe`/`bookmarked` 是上一个账号的视角，
     * 必须一并洗掉。
     */
    fun onSignedOut() {
        _viewer.value = null
        _viewerLoaded.value = false
        _bookmarks.value = BookmarkState()
        _following.value = ListState()
        followingGeneration++
        _followedTags.value = emptyList()
        _suggestedTags.value = emptyList()
        _followsLoaded.value = false
        listOf(_list, _hot).forEach { state ->
            state.value = state.value.copy(
                posts = state.value.posts.map { it.copy(likedByMe = false, bookmarked = false) },
            )
        }
        _detail.value.post?.let { post ->
            _detail.value = _detail.value.copy(
                post = post.copy(likedByMe = false, bookmarked = false),
            )
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
            ?: _hot.value.posts.firstOrNull { it.id == id }
            ?: _following.value.posts.firstOrNull { it.id == id }

    private fun applyPost(updated: ForumPost) {
        _detail.value = _detail.value.post
            ?.let { if (it.id == updated.id) _detail.value.copy(post = updated) else _detail.value }
            ?: _detail.value
        listOf(_list, _hot, _following).forEach { state ->
            val value = state.value
            if (value.posts.any { it.id == updated.id }) {
                state.value = value.copy(
                    posts = value.posts.map { if (it.id == updated.id) updated else it },
                )
            }
        }
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