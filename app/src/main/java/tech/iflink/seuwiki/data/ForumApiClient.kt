package tech.iflink.seuwiki.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import tech.iflink.seuwiki.models.ForumAuthor
import tech.iflink.seuwiki.models.ForumComment
import tech.iflink.seuwiki.models.ForumBookmarkPage
import tech.iflink.seuwiki.models.ForumBookmarkedPost
import tech.iflink.seuwiki.models.ForumCommentsPage
import tech.iflink.seuwiki.models.ForumImage
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumPostPage
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.models.ForumTag
import tech.iflink.seuwiki.models.ForumTargetType
import tech.iflink.seuwiki.models.ForumViewer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * `forum.seu.wiki` 的 API 客户端 —— **与 App 现有登录共用同一个 Logto 会话**。
 *
 * ## 为什么不需要第二次登录
 *
 * 后端同时接受两种凭据（`seu-wiki-forum` `docs/auth.md` §2）：
 * - Cookie 会话：给**浏览器**访问 `forum.seu.wiki` 用，跑的是服务端 OIDC 重定向
 * - `Authorization: Bearer`：给**原生 App** 用，就是本类在做的事
 *
 * 后端有专门的鉴权分支 `src/lib/logto/bearer.ts`：只要请求头里带了 Bearer，
 * 就**只**走 Bearer 校验，绝不回退 Cookie。所以 App 拿 [AuthConfig.clientId]
 * 登一次拿到的 access token，直接发到这里即可，用户全程只登一次。
 *
 * **前置条件**：后端必须配置 `LOGTO_NATIVE_APP_ID`（或 `LOGTO_API_RESOURCE`），
 * 否则 [isBearerAuthEnabled] 为 false，**所有** Bearer 一律被拒
 * （`src/lib/logto/bearer.ts:19-31`）。这不是本类能兜住的事。
 *
 * ## 为什么每个请求都无条件带 token
 *
 * 后端对写操作有同源 CSRF 校验，比较的是 `Origin`/`Referer` 与 `APP_URL`。
 * 原生客户端既没有 `Origin` 也没有 `Referer`，不带 Bearer 的话一律
 * **403 `CROSS_ORIGIN_REQUEST`**（`src/lib/forum-access/http.ts:42,56`）。
 * 带上 Bearer 就整段豁免 —— 所以这个头不是"登录了才加"，是**所有请求都要带**。
 *
 * 同理本客户端**从不读写 Cookie**（契约明确要求非浏览器集成不得携带 Cookie）。
 * `HttpURLConnection` 默认不启用 CookieHandler，本工程也没有全局设置过，
 * 所以天然满足。
 *
 * ## 依赖选择
 *
 * 与 [FeedApiClient] 同款：手写 `HttpURLConnection` + kotlinx.serialization，
 * 不引入 Retrofit / OkHttp。所有方法都是阻塞 I/O，调用方负责切到 IO 线程
 * （见 [ForumStore]）。
 */
class ForumApiClient(
    private val baseUrl: String = DefaultBaseUrl,
    /**
     * 取当前 access token；返回 null 就是匿名请求。
     *
     * 走 [AuthStore.accessToken] 而不是直接读 `session.accessToken`：后者不续期，
     * token 过期后所有写操作会变成 401，而用户界面上还显示着「已登录」。
     * [AuthStore.accessToken] 自带续期、并发合并与登出保护。
     */
    private val tokenProvider: (suspend () -> String?)? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    // MARK: - 读

    /**
     * `GET /api/posts?sort=&limit=&tag=&cursor=` —— 公开帖子列表，**匿名可读**。
     *
     * [cursor] 必须原样回传后端给的 `next_cursor`，不要解析也不要自己拼。
     * 切换 [sort] 时必须丢弃旧游标：后端游标里编码了排序语义，跨 sort 复用直接
     * 400 `INVALID_CURSOR`（`src/lib/api/public-read.ts:75-87`）。
     *
     * @param tag 话题 slug。**未收录的 slug 返回 200 + 空数组**，不是 404。
     */
    suspend fun posts(
        sort: ForumSort = ForumSort.Latest,
        tag: String? = null,
        cursor: String? = null,
        limit: Int = PageSize,
    ): ForumPostPage {
        val dto: PostsResponse = get(
            "/api/posts",
            buildList {
                add("sort" to sort.key)
                add("limit" to limit.coerceIn(1, 50).toString())
                tag?.takeIf { it.isNotBlank() }?.let { add("tag" to it) }
                cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
            },
        )
        return ForumPostPage(dto.posts.map { it.toPost() }, dto.nextCursor)
    }

    /**
     * `GET /api/posts/{id}` —— 详情。
     *
     * 匿名也能读；带登录态时服务端会额外算上 [ForumPost.bookmarked]。
     * 注意这里的 `bookmarked` 是**服务端算的**，与列表不同；同理 `featured`。
     *
     * 鉴权失败时后端**降级为匿名而不是报错**（`posts/[id]/route.ts:30-35`），
     * 所以未登录时这个接口照常 200，只是 `bookmarked` 恒 false。
     */
    suspend fun postDetail(id: String): ForumPost {
        val dto: PostDetailResponse = get("/api/posts/${encode(id)}", emptyList())
        return dto.post.toPost().copy(
            bookmarked = dto.post.bookmarked ?: false,
            featured = dto.featured,
        )
    }

    /**
     * `GET /api/comments?target_type=post&target_id=` —— 评论列表，**匿名可读**。
     *
     * 两个服务端行为决定了这里的形状：
     * - **没有分页**，硬上限 50 条，按时间**升序**（`src/lib/services/comments.ts:93,107`）。
     *   所以一次拉完，不要做"加载更多"。
     * - [ForumCommentsPage.authors] 是以 userId 为 key 的 map，**可能缺 key**
     *   （作者被软删除就不在里面）。渲染时必须容忍找不到。
     */
    suspend fun comments(postId: String): ForumCommentsPage {
        val dto: CommentsResponse = get(
            "/api/comments",
            listOf("target_type" to "post", "target_id" to postId),
        )
        return ForumCommentsPage(
            comments = dto.comments.map {
                ForumComment(
                    id = it.id,
                    authorId = it.authorId,
                    content = it.content,
                    parentId = it.parentId,
                    likesCount = it.likesCount,
                    createdAt = parseIso8601(it.createdAt),
                )
            },
            authors = dto.authors.mapValues { (_, card) -> card.toAuthor() },
        )
    }

    /**
     * `GET /api/me` —— 当前身份。**匿名也返回 200，永不 401**。
     *
     * 未登录时后端回 `{isAuthenticated:false, viewer:null, user:null}`；
     * 已登录返回 viewer。失败时可能 `isAuthenticated:true` 但 `viewer:null`
     * 并带 `error` 字段（`me/route.ts:47-54`），所以这里靠 viewer 是否为 null 判定，
     * 不靠 `isAuthenticated`。
     *
     * 这是验证「免登录真的通了」最直接的一个接口：带上 App 的 token，
     * 能拿到 viewer 就说明后端认这个 token。
     */
    suspend fun me(): ForumViewer? {
        val dto: MeResponse = get("/api/me", emptyList())
        return dto.viewer?.toViewer()
    }

    // MARK: - 写（全部需要登录）

    /**
     * `POST /api/posts` —— 发帖，返回新帖 id。
     *
     * Body 是**严格白名单**，多一个 key 就 400 `INVALID_BODY`
     * （`src/lib/posts/create-input.mjs`）。后端目前只接受 `post_type=normal`
     * 与 `visibility=public`，所以本方法不暴露这两个参数。
     *
     * @param tags 话题 slug，最多 3 个，必须都在后端目录内。
     */
    suspend fun createPost(title: String?, content: String, tags: List<String> = emptyList()): String {
        val body = buildJsonObject {
            put("content", content)
            title?.takeIf { it.isNotBlank() }?.let { put("title", it.trim()) }
            if (tags.isNotEmpty()) {
                putJsonArray("tags") { tags.forEach { add(it) } }
            }
        }
        val dto: CreatedPostResponse = send("POST", "/api/posts", body)
        // 成功返回的是 **posts 表整行**（没有 author 子对象、只有 author_id），
        // 与列表结构不同。这里只取 id 让 UI 跳详情，作者/图片由详情接口补。
        return dto.post.id
    }

    /** `POST /api/comments` —— 发评论，返回新评论 id。 */
    suspend fun createComment(
        postId: String,
        content: String,
        parentId: String? = null,
    ): String {
        val body = buildJsonObject {
            put("target_type", "post")
            put("target_id", postId)
            put("content", content)
            parentId?.let { put("parent_id", it) }
        }
        val dto: CreatedCommentResponse = send("POST", "/api/comments", body)
        return dto.comment.id
    }

    /**
     * `POST /api/post-likes?post_id=` —— 点赞 toggle，返回**切换后**的态。
     *
     * 这是 toggle 不是 setter：已经点过再调会取消。后端没有"查询当前是否已点赞"
     * 的只读接口（`hasUserLikedPost` 只在服务端内部用），所以初始态只能靠本地记。
     */
    suspend fun toggleLike(postId: String): Boolean {
        val dto: LikedResponse = send("POST", "/api/post-likes?post_id=${encode(postId)}", null)
        return dto.liked
    }

    /** `POST /api/bookmarks` —— 收藏 toggle，同样返回切换后的态。 */
    suspend fun toggleBookmark(postId: String): Boolean {
        val body = buildJsonObject {
            put("target_type", "post")
            put("target_id", postId)
        }
        val dto: BookmarkedResponse = send("POST", "/api/bookmarks", body)
        return dto.bookmarked
    }

    /**
     * `DELETE /api/content/{targetType}/{targetId}` —— 删自己的帖/评（软删除）。
     *
     * 没有 `DELETE /api/posts/{id}`，删除统一走这里。body 里那个
     * `confirmation` 字符串必须**精确等于** `delete_owned_content`
     * （`content/[targetType]/[targetId]/route.ts:46-48`），少一个字符就 400。
     */
    suspend fun deleteContent(targetType: ForumTargetType, targetId: String) {
        val body = buildJsonObject { put("confirmation", "delete_owned_content") }
        send<JsonObject>(
            "DELETE",
            "/api/content/${targetType.key}/${encode(targetId)}",
            body,
        )
    }

    /**
     * 把服务端的相对资源路径拼成可直接加载的绝对 URL。
     *
     * `post_assets.asset_url` 与上传返回的 `path` 都是 `/api/media/...` 形式
     * （`src/lib/media/security.mjs:154-159`），不带域名。**bookmarks 返回的
     * `preview_image_url` 可能是外部 URL**，那种情况原样返回。
     */
    /**
     * `GET /api/bookmarks` —— 我的收藏列表，**需登录**。
     *
     * 服务端会把目标已删除/不可见的条目**剔除**，但游标照样前进
     * （`src/lib/services/bookmarks.ts:141`），所以可能出现「返回条数 < limit 但
     * next_cursor 非 null」，这是正常的，不能当成末页。
     */
    suspend fun bookmarks(cursor: String? = null, limit: Int = PageSize): ForumBookmarkPage {
        val dto: BookmarksResponse = get(
            "/api/bookmarks",
            buildList {
                add("limit" to limit.coerceIn(1, 50).toString())
                cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
            },
        )
        return ForumBookmarkPage(
            items = dto.bookmarks.mapNotNull { b ->
                val target = b.target ?: return@mapNotNull null
                ForumBookmarkedPost(
                    bookmarkId = b.id,
                    post = ForumPost(
                        id = target.id,
                        title = target.title,
                        content = target.excerpt,
                        postType = "normal",
                        createdAt = parseIso8601(target.createdAt),
                        likesCount = target.likesCount,
                        commentsCount = target.commentsCount,
                        author = target.author?.let {
                            it.toAuthor()
                        },
                        // 收藏列表给的是 preview_image_url，可能不是 /api/media/ 形式
                        images = listOfNotNull(
                            target.previewImageUrl?.let {
                                ForumImage(id = "", assetUrl = it, mimeType = null, sortOrder = 0)
                            }
                        ),
                    ),
                )
            },
            nextCursor = dto.nextCursor,
        )
    }

    // MARK: - Plumbing

    private suspend inline fun <reified T> get(
        path: String,
        query: List<Pair<String, String>>,
    ): T = request("GET", path + queryString(query), null)

    private suspend inline fun <reified T> send(
        method: String,
        path: String,
        body: JsonObject?,
    ): T = request(method, path, body)

    /**
     * 所有请求都带 Bearer —— 包括 GET。
     *
     * 不是因为 GET 需要鉴权（公开接口匿名可读），而是因为**统一带上才能保证
     * 服务端永远走 Bearer 分支**，行为可预测；更重要的是写操作必须靠这个头
     * 豁免同源 CSRF 校验。少带一次就是 403。
     */
    private suspend inline fun <reified T> request(
        method: String,
        path: String,
        body: JsonObject?,
    ): T {
        val token = tokenProvider?.invoke()
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                // 后端对非 application/json 直接 415（`api/same-origin-json.mjs:26-28`）。
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(json.encodeToString(body).toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) throw parseError(status, text)
            return json.decodeFromString(text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 错误体有两种形状，必须都兼容：
     * `{error}` 与 `{error, message}`（中文提示，后者的来源见
     * `src/lib/forum-access/http.ts:95-101`）。message 声明为可空。
     */
    private fun parseError(status: Int, body: String): ForumApiException {
        val error = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)
                ?.get("error")?.toString()?.trim('"')
        }.getOrNull()
        val detail = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)
                ?.get("message")?.toString()?.trim('"')
        }.getOrNull()
        return ForumApiException(status, error, detail)
    }

    private fun queryString(query: List<Pair<String, String>>): String =
        if (query.isEmpty()) "" else query.joinToString("&", prefix = "?") {
            "${encode(it.first)}=${encode(it.second)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 服务端返回了非 2xx。
     *
     * [status] 原样带出便于上层区分（401 该跳登录、403 多半是 CSRF、5xx 该重试）；
     * [errorCode] 是后端的机器可读码（如 `UNAUTHORIZED` / `CROSS_ORIGIN_REQUEST`）；
     * [detail] 是可选的中文提示，**可能为 null**，且是给接口调用方看的文案，
     * 不一定适合终端用户 —— 所以名字不叫 message，免得和 `Throwable.message` 混淆。
     */
    class ForumApiException(
        val status: Int,
        val errorCode: String?,
        val detail: String?,
    ) : IOException("HTTP $status ${errorCode ?: ""} ${detail ?: ""}".trim()) {
        /** 需要登录才能做这件事。 */
        val isUnauthorized: Boolean get() = status == 401
    }

    companion object {
        const val DefaultBaseUrl = "https://forum.seu.wiki"
        const val PageSize = 20

        /**
         * 把服务端的相对资源路径拼成可直接加载的绝对 URL。
         *
         * `post_assets.asset_url` 与上传返回的 `path` 都是 `/api/media/...` 形式
         * （`src/lib/media/security.mjs:154-159`），不带域名，客户端必须自己拼。
         * **bookmarks 返回的 `preview_image_url` 可能是外部 URL**，那种原样返回。
         *
         * 放 companion 是因为它是纯函数：渲染层手上往往只有 URL 字符串，
         * 没有 client 实例，不该为此在 UI 里造一个。
         */
        fun absoluteUrl(path: String?, baseUrl: String = DefaultBaseUrl): String? {
            if (path.isNullOrBlank()) return null
            if (path.startsWith("http://") || path.startsWith("https://")) return path
            return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
    }
}

// MARK: - DTO

@Serializable
private data class AuthorDto(
    val id: String,
    val display_name: String? = null,
    val username: String? = null,
    val avatar_url: String? = null,
)

@Serializable
private data class TagDto(
    val id: String,
    val name: String,
    val slug: String,
)

@Serializable
private data class ImageDto(
    val id: String,
    val asset_url: String,
    val mime_type: String? = null,
    val sort_order: Int = 0,
)

/**
 * 列表项与详情项共用一套字段，差别只有 `bookmarked`（详情才有）。
 * 列表接口的 selectColumns 里没有它，所以给它 null 默认。
 */
@Serializable
private data class PostDto(
    val id: String,
    val title: String? = null,
    val content: String,
    val post_type: String = "normal",
    val created_at: String? = null,
    val likes_count: Int = 0,
    val comments_count: Int = 0,
    val author: AuthorDto? = null,
    val images: List<ImageDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val bookmarked: Boolean? = null,
)

@Serializable
private data class PostsResponse(
    val posts: List<PostDto> = emptyList(),
    /** 游标原样回传，不要解析（后端在游标里编码了排序语义）。 */
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
private data class PostDetailResponse(
    val post: PostDto,
    val featured: Boolean = false,
)

@Serializable
private data class CommentDto(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val content: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class CommentsResponse(
    val comments: List<CommentDto> = emptyList(),
    val authors: Map<String, AuthorDto> = emptyMap(),
)

/** 视图字段是 **camelCase**，与帖子接口的 snake_case 相反。 */
@Serializable
private data class ViewerDto(
    val id: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val roleLabel: String? = null,
    val email: String? = null,
    val username: String? = null,
    val forumRole: String? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
private data class MeResponse(
    val isAuthenticated: Boolean = false,
    val viewer: ViewerDto? = null,
    /** 失败时可能带，且 `isAuthenticated` 仍是 true，所以这个字段也得声明。 */
    val error: String? = null,
)

/** 创建接口返回的是 posts 表**整行**（有 author_id、没有 author/images）。 */
@Serializable
private data class RawPostDto(
    val id: String,
    val author_id: String? = null,
    val title: String? = null,
    val content: String = "",
)

@Serializable
private data class CreatedPostResponse(val post: RawPostDto)

@Serializable
private data class CreatedCommentResponse(val comment: CommentDto)

@Serializable
private data class LikedResponse(val liked: Boolean)

@Serializable
private data class BookmarkedResponse(val bookmarked: Boolean)

@Serializable
private data class BookmarkDto(
    val id: String,
    @SerialName("target_type") val targetType: String = "post",
    @SerialName("target_id") val targetId: String,
    @SerialName("created_at") val createdAt: String? = null,
    val target: BookmarkTargetDto? = null,
)

/**
 * 收藏条目里的目标帖。
 *
 * ⚠️ 这里字段是 **snake_case**（`preview_image_url` / `likes_count`），而顶层
 * 响应外层也是 snake_case —— 与 `/api/featured`、`/api/me` 的 camelCase 不同，
 * 三种风格在同一套 API 里混用，别想当然。
 */
@Serializable
private data class BookmarkTargetDto(
    val id: String,
    val title: String? = null,
    val excerpt: String = "",
    @SerialName("preview_image_url") val previewImageUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("comments_count") val commentsCount: Int = 0,
    val author: AuthorDto? = null,
)

@Serializable
private data class BookmarksResponse(
    val bookmarks: List<BookmarkDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

// MARK: - DTO → model

private fun AuthorDto.toAuthor() = ForumAuthor(
    id = id,
    displayName = display_name,
    username = username,
    avatarUrl = avatar_url,
)

private fun PostDto.toPost() = ForumPost(
    id = id,
    title = title,
    content = content,
    postType = post_type,
    createdAt = parseIso8601(created_at),
    likesCount = likes_count,
    commentsCount = comments_count,
    author = author?.toAuthor(),
    images = images.map { ForumImage(it.id, it.asset_url, it.mime_type, it.sort_order) },
    tags = tags.map { ForumTag(it.id, it.name, it.slug) },
    bookmarked = bookmarked ?: false,
)

private fun ViewerDto.toViewer() = ForumViewer(
    id = id,
    displayName = displayName,
    avatarUrl = avatarUrl,
    roleLabel = roleLabel,
    email = email,
    username = username,
    forumRole = forumRole,
    capabilities = capabilities,
)