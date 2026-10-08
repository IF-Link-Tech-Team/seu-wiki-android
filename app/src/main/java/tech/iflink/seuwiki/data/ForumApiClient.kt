package tech.iflink.seuwiki.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import tech.iflink.seuwiki.models.FollowedTag
import tech.iflink.seuwiki.models.FollowingFeedPage
import tech.iflink.seuwiki.models.ForumAuthor
import tech.iflink.seuwiki.models.ForumComment
import tech.iflink.seuwiki.models.ForumBookmarkPage
import tech.iflink.seuwiki.models.ForumBookmarkedPost
import tech.iflink.seuwiki.models.ForumCommentsPage
import tech.iflink.seuwiki.models.ForumImage
import tech.iflink.seuwiki.models.ForumNotification
import tech.iflink.seuwiki.models.ForumNotificationPage
import tech.iflink.seuwiki.models.ForumNotificationPost
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumPostPage
import tech.iflink.seuwiki.models.ForumSearchArticle
import tech.iflink.seuwiki.models.ForumSearchPost
import tech.iflink.seuwiki.models.ForumSearchResults
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.models.ForumTag
import tech.iflink.seuwiki.models.ForumTargetType
import tech.iflink.seuwiki.models.ForumViewer
import tech.iflink.seuwiki.models.HandbookArticle
import tech.iflink.seuwiki.models.HandbookArticleRef
import tech.iflink.seuwiki.models.HandbookArticleSummary
import tech.iflink.seuwiki.models.HandbookChild
import tech.iflink.seuwiki.models.HandbookSectionInfo
import tech.iflink.seuwiki.models.HandbookSectionPage
import tech.iflink.seuwiki.models.HandbookSourcePost
import tech.iflink.seuwiki.models.SuggestedTopic
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
 * OkHttp 默认不带 CookieJar，天然满足。
 *
 * ## 依赖选择
 *
 * OkHttp + kotlinx.serialization，不引入 Retrofit。最初用的是手写
 * `HttpURLConnection`，但它的 `setRequestMethod` 白名单没有 PATCH
 * （JVM 与 Android 都抛 `ProtocolException`；JDK 17 起反射绕法也被
 * 模块封装挡死），编辑帖子需要 PATCH，因此迁到 OkHttp。
 * 所有方法都是阻塞 I/O，调用方负责切到 IO 线程（见 [ForumStore]）。
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
     * `GET /api/posts?sort=&limit=&tag=&cursor=&offset=` —— 公开帖子列表，**匿名可读**。
     *
     * [cursor] 必须原样回传后端给的 `next_cursor`，不要解析也不要自己拼。
     * 切换 [sort] 时必须丢弃旧游标：后端游标里编码了排序语义，跨 sort 复用直接
     * 400 `INVALID_CURSOR`（`src/lib/api/public-read.ts:75-87`）。
     *
     * **热榜（[ForumSort.Hot]）用 [offset] 而不是 cursor**：热度分随互动实时变化，
     * keyset 会错位，所以热榜响应给 `total_count` / `next_offset`
     * （`posts/route.ts` 的 hot 分支）。对 hot 传 cursor 会被判 400 `INVALID_CURSOR`。
     *
     * @param tag 话题 slug。**未收录的 slug 返回 200 + 空数组**，不是 404。
     *   且 hot 的 tag 过滤是**精确 slug**：筛主题不会聚合子标签的帖子。
     */
    suspend fun posts(
        sort: ForumSort = ForumSort.Latest,
        tag: String? = null,
        cursor: String? = null,
        limit: Int = PageSize,
        offset: Int? = null,
    ): ForumPostPage {
        val dto: PostsResponse = get(
            "/api/posts",
            buildList {
                add("sort" to sort.key)
                add("limit" to limit.coerceIn(1, 50).toString())
                tag?.takeIf { it.isNotBlank() }?.let { add("tag" to it) }
                if (sort == ForumSort.Hot) {
                    // 热榜只认 offset；cursor 一个字节都不能带（带了就是 400）。
                    offset?.takeIf { it > 0 }?.let { add("offset" to it.toString()) }
                } else {
                    cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
                }
            },
        )
        return ForumPostPage(
            posts = dto.posts.map { it.toPost() },
            nextCursor = dto.nextCursor,
            nextOffset = dto.nextOffset,
            totalCount = dto.totalCount,
        )
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
            handbookArticle = dto.handbookArticle?.let { HandbookArticleRef(it.id, it.title) },
        )
    }

    /**
     * `POST /api/posts/{id}/view` —— 浏览计数 +1，返回服务端权威 `views`。
     *
     * 匿名也可调（无 body、无鉴权要求）。**失败必须静默**：浏览计数是锦上添花，
     * 绝不能因为这一发失败让详情页显示错误 —— 调用方（ForumStore）用
     * `runCatching` 包住，拿到 null 就保持详情接口给的旧值。
     */
    suspend fun incrementView(id: String): Int? = runCatching {
        val dto: ViewResponse = send("POST", "/api/posts/${encode(id)}/view", null)
        dto.views
    }.getOrNull()

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
     * `PATCH /api/posts/{id}` —— 作者编辑自己的帖子。
     *
     * body 白名单只有 `title`/`content` 两个 key（`posts/[id]/route.ts` 的
     * PATCH），标签不可改；`title` 省略或空串都会被后端存成 null，
     * 所以这里沿用发帖的「空标题不带 key」约定。`content` 后端强制非空。
     * 返回的是更新后的 posts 整行，客户端用不上，统一刷新详情即可。
     */
    suspend fun updatePost(postId: String, title: String?, content: String) {
        val body = buildJsonObject {
            put("content", content)
            title?.takeIf { it.isNotBlank() }?.let { put("title", it.trim()) }
        }
        send<JsonObject>("PATCH", "/api/posts/${encode(postId)}", body)
    }

    /**
     * `DELETE /api/admin/content/{targetType}/{targetId}` —— 管理删除，
     * 需要 `admin:content:delete` 能力（owner/admin/moderator）。
     *
     * 与作者自删的差别：路由不同、confirmation 字面量必须是 `admin_delete`、
     * 服务端写 `admin_delete` 审计。Idempotency-Key 可省略（缺省时服务端
     * 自己生成，`readIdempotencyKey` 允许 null）。
     */
    suspend fun adminDeleteContent(targetType: ForumTargetType, targetId: String) {
        val body = buildJsonObject { put("confirmation", "admin_delete") }
        send<JsonObject>(
            "DELETE",
            "/api/admin/content/${targetType.key}/${encode(targetId)}",
            body,
        )
    }

    /**
     * `POST /api/media/upload`（multipart/form-data）—— 给已存在的帖子传一张配图。
     *
     * 契约（`media/upload/route.ts` + `lib/media/upload-handler.mjs`）：
     * - 字段：`file`（二进制）、`purpose=post-image`、`postId=<uuid>`；
     * - 仅 jpeg/png/webp、单张 ≤5MB，服务端嗅探内容与声明类型必须一致；
     * - 帖子必须已存在且当前用户是作者（顺序永远是先建帖/保存，再传图）；
     * - 成功 201 `{media:{path, contentType, size, assetId}}`。
     * Bearer token 豁免同源 CSRF 校验，原生端没有 Origin 也能传。
     */
    suspend fun uploadPostImage(postId: String, bytes: ByteArray, contentType: String = "image/jpeg") {
        val token = tokenProvider?.invoke()
            ?: throw ForumApiException(401, "UNAUTHORIZED", null)
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("purpose", "post-image")
            .addFormDataPart("postId", postId)
            .addFormDataPart(
                "file",
                "image.jpg",
                bytes.toRequestBody(contentType.toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/media/upload")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        SharedHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
        }
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

    // MARK: - 东大生存手册（匿名可读）

    /** `GET /api/handbook/sections` —— 手册板块列表（主题 + 子标签 + 文章数）。 */
    suspend fun handbookSections(): List<HandbookSectionInfo> {
        val dto: HandbookSectionsResponse = get("/api/handbook/sections", emptyList())
        return dto.sections.map { it.toSectionInfo() }
    }

    /**
     * `GET /api/handbook/sections/{slug}` —— 板块详情 + 文章列表（published_at 倒序）。
     *
     * slug 未收录时后端返 404 `SECTION_NOT_FOUND`，异常原样抛出由上层显示空态。
     */
    suspend fun handbookSection(slug: String): HandbookSectionPage {
        val dto: HandbookSectionResponse = get("/api/handbook/sections/${encode(slug)}", emptyList())
        return HandbookSectionPage(
            type = dto.section.type,
            slug = dto.section.slug,
            name = dto.section.name ?: dto.section.slug,
            parentSlug = dto.section.parentSlug,
            children = dto.section.children.map { HandbookChild(it.slug, it.name, it.articleCount ?: 0) },
            articles = dto.articles.map { it.toSummary() },
        )
    }

    /**
     * `GET /api/handbook/articles/{id}` —— 文章详情。
     *
     * [HandbookArticle.contentHtml] 是服务端消毒过的 HTML，直接进
     * `campusHtmlToAnnotatedString` 渲染管线。
     */
    suspend fun handbookArticle(id: String): HandbookArticle {
        val dto: HandbookArticleResponse = get("/api/handbook/articles/${encode(id)}", emptyList())
        val a = dto.article
        return HandbookArticle(
            id = a.id,
            tagSlug = a.tagSlug,
            title = a.title,
            authorDisplay = a.authorDisplay?.takeIf { it.isNotBlank() },
            contentHtml = a.contentHtml.orEmpty(),
            publishedAt = parseIso8601(a.publishedAt),
            updatedAt = parseIso8601(a.updatedAt),
            sourcePost = a.sourcePost?.let { sp ->
                HandbookSourcePost(
                    id = sp.id,
                    title = sp.title,
                    likesCount = sp.likesCount,
                    commentsCount = sp.commentsCount,
                    viewsCount = sp.viewsCount,
                    author = sp.author?.toAuthor(),
                )
            },
        )
    }

    // MARK: - 关注（需登录）

    /**
     * `GET /api/follows?target_type=tag` —— 本人关注的板块标签，**需登录**（401 未登录）。
     *
     * 目录固定不分页（响应的 `next_cursor` 恒为 null）。
     */
    suspend fun followedTags(): List<FollowedTag> {
        val dto: FollowsResponse = get("/api/follows", listOf("target_type" to "tag"))
        return dto.follows.map {
            FollowedTag(slug = it.tagSlug, name = it.tagName, topicSlug = it.topicSlug)
        }
    }

    /**
     * `POST /api/follows` —— 关注/取关 toggle，返回**切换后**的 `followed`。
     *
     * Body 是严格白名单 `{target_type, target_id}`，多一个 key 就 400
     * （`lib/follows/create-input.mjs`）。tag 的 target_id 是 slug。
     */
    suspend fun toggleFollowTag(slug: String): Boolean {
        val body = buildJsonObject {
            put("target_type", "tag")
            put("target_id", slug)
        }
        val dto: FollowedResponse = send("POST", "/api/follows", body)
        return dto.followed
    }

    /**
     * `GET /api/feed/following` —— 关注流，**需登录**（401 未登录）。
     *
     * keyset 分页，游标约定与 `/api/posts` latest 一致。零关注时 posts 为空且
     * 下发 `suggested_tags`（对象数组：slug/name/post_count），供关注引导。
     */
    suspend fun followingFeed(cursor: String? = null, limit: Int = PageSize): FollowingFeedPage {
        val dto: FollowingFeedResponse = get(
            "/api/feed/following",
            buildList {
                add("limit" to limit.coerceIn(1, 50).toString())
                cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
            },
        )
        return FollowingFeedPage(
            posts = dto.posts.map { it.toPost() },
            nextCursor = dto.nextCursor,
            suggestedTags = dto.suggestedTags.map {
                SuggestedTopic(slug = it.slug, name = it.name, postCount = it.postCount)
            },
        )
    }

    // MARK: - 通知（需登录）

    /**
     * `GET /api/notifications?limit=&cursor=` —— 当前用户通知列表，**需登录**
     * （未登录 401 `UNAUTHORIZED`）。
     *
     * keyset 游标（latest 排序），约定与 `/api/posts` latest 一致。
     * 响应顺带下发 `unread_count`，角标直接用，不必再发一发请求。
     * `actor` / `post` 都可能为 null（动作方被软删 / 目标帖已删），条目仍在。
     */
    suspend fun notifications(cursor: String? = null, limit: Int = PageSize): ForumNotificationPage {
        val dto: NotificationsResponse = get(
            "/api/notifications",
            buildList {
                add("limit" to limit.coerceIn(1, 50).toString())
                cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
            },
        )
        return ForumNotificationPage(
            items = dto.notifications.map { it.toNotification() },
            nextCursor = dto.next_cursor,
            unreadCount = dto.unread_count,
        )
    }

    /**
     * `POST /api/notifications/mark-read` —— 把当前用户的通知全部标记已读。
     *
     * body 必须是**空对象**（`notifications/mark-read/route.ts` 的
     * `hasOnlyKeys(parsed.body, [])`，多一个 key 就 400 `INVALID_BODY`）。
     * 返回服务端回执的未读数（成功时恒 0）。
     */
    suspend fun markNotificationsRead(): Int {
        val dto: MarkNotificationsReadResponse =
            send("POST", "/api/notifications/mark-read", buildJsonObject {})
        return dto.unread_count
    }

    // MARK: - 搜索（匿名可读）

    /**
     * `GET /api/search?q=&type=all` —— 论坛统一搜索：帖子 + 手册文章两组同返。
     *
     * 空关键词后端直接 400 `EMPTY_QUERY`，调用方（SearchStore）在 trim 后为空时
     * 不该发请求。两组各带 scope 游标，续翻哪一组回传哪一组的 next_cursor。
     */
    suspend fun search(query: String, limit: Int = PageSize): ForumSearchResults {
        val dto: ForumSearchResponse = get(
            "/api/search",
            listOf("q" to query, "type" to "all", "limit" to limit.coerceIn(1, 50).toString()),
        )
        return ForumSearchResults(
            posts = dto.posts?.items.orEmpty().map { it.toSearchPost() },
            postsNextCursor = dto.posts?.nextCursor,
            articles = dto.articles?.items.orEmpty().map { it.toSearchArticle() },
            articlesNextCursor = dto.articles?.nextCursor,
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
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        // 后端对非 application/json 直接 415（`api/same-origin-json.mjs:26-28`）。
        val requestBody = body?.let {
            json.encodeToString(it).toRequestBody(JsonMediaType)
        } ?: EmptyBody.takeIf { method == "POST" || method == "PUT" || method == "PATCH" }
        // OkHttp 的 method() 不做白名单校验，PATCH/带 body 的 DELETE 都合法；
        // 但 POST/PUT/PATCH 强制要有 body，无 body 的写操作补一个空 body。
        builder.method(method, requestBody)
        SharedHttpClient.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseError(response.code, text)
            return json.decodeFromString(text)
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

        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        /** 无 body 的 POST 也要占一个空 body（OkHttp 硬性要求），Content-Length: 0。 */
        private val EmptyBody = ByteArray(0).toRequestBody(null, 0, 0)

        /**
         * 共享的 OkHttpClient：连接池/线程池全局一份。默认无 CookieJar，
         * 即「从不读写 Cookie」的契约由实现保证（见类注释）。
         */
        private val SharedHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

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
    val views_count: Int = 0,
    val pinned_at: String? = null,
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
    /** 仅热榜（sort=hot）：offset 分页。 */
    @SerialName("next_offset") val nextOffset: Int? = null,
    @SerialName("total_count") val totalCount: Int? = null,
)

@Serializable
private data class HandbookArticleLinkDto(
    val id: String,
    val title: String = "",
)

@Serializable
private data class PostDetailResponse(
    val post: PostDto,
    val featured: Boolean = false,
    /** 该帖沉淀出的手册文章（最新一篇）；没有时为 null。 */
    @SerialName("handbook_article") val handbookArticle: HandbookArticleLinkDto? = null,
)

@Serializable
private data class ViewResponse(val views: Int = 0)

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

// MARK: - DTO：手册 / 关注 / 搜索

@Serializable
private data class HandbookChildDto(
    val slug: String,
    val name: String,
    /** 板块列表接口给；板块详情接口的 children 没有这个字段。 */
    @SerialName("article_count") val articleCount: Int? = null,
)

@Serializable
private data class HandbookSectionDto(
    val slug: String,
    val name: String? = null,
    /** "topic"（含 children）或 "tag"（含 parent_slug）。 */
    val type: String = "topic",
    @SerialName("article_count") val articleCount: Int? = null,
    @SerialName("parent_slug") val parentSlug: String? = null,
    val children: List<HandbookChildDto> = emptyList(),
)

@Serializable
private data class HandbookSectionsResponse(
    val sections: List<HandbookSectionDto> = emptyList(),
)

@Serializable
private data class HandbookSectionResponse(
    val section: HandbookSectionDto,
    val articles: List<HandbookArticleSummaryDto> = emptyList(),
)

@Serializable
private data class HandbookArticleSummaryDto(
    val id: String,
    @SerialName("tag_slug") val tagSlug: String,
    val title: String,
    @SerialName("author_display") val authorDisplay: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("source_post") val sourcePost: HandbookArticleLinkDto? = null,
)

@Serializable
private data class HandbookArticleResponse(
    val article: HandbookArticleDto,
)

@Serializable
private data class HandbookArticleDto(
    val id: String,
    @SerialName("tag_slug") val tagSlug: String,
    val title: String,
    @SerialName("author_display") val authorDisplay: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("source_post") val sourcePost: HandbookSourcePostDto? = null,
)

@Serializable
private data class HandbookSourcePostDto(
    val id: String,
    val title: String? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("comments_count") val commentsCount: Int = 0,
    @SerialName("views_count") val viewsCount: Int = 0,
    val author: AuthorDto? = null,
)

@Serializable
private data class FollowDto(
    @SerialName("tag_slug") val tagSlug: String,
    @SerialName("tag_name") val tagName: String,
    @SerialName("topic_slug") val topicSlug: String? = null,
)

@Serializable
private data class FollowsResponse(
    val follows: List<FollowDto> = emptyList(),
    /** tag 关注目录固定不分页，恒为 null。 */
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
private data class FollowedResponse(val followed: Boolean = false)

@Serializable
private data class SuggestedTopicDto(
    val slug: String,
    val name: String,
    @SerialName("post_count") val postCount: Int = 0,
)

@Serializable
private data class FollowingFeedResponse(
    val posts: List<PostDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    /** 仅零关注时下发；是对象数组，不是字符串数组。 */
    @SerialName("suggested_tags") val suggestedTags: List<SuggestedTopicDto> = emptyList(),
)

@Serializable
private data class SearchPostDto(
    val id: String,
    val title: String? = null,
    val snippet: String = "",
    val author: AuthorDto? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("comments_count") val commentsCount: Int = 0,
    @SerialName("views_count") val viewsCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class SearchArticleDto(
    val id: String,
    val title: String,
    val snippet: String = "",
    @SerialName("tag_slug") val tagSlug: String,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("source_post_id") val sourcePostId: String? = null,
)

@Serializable
private data class SearchPostGroupDto(
    val items: List<SearchPostDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
private data class SearchArticleGroupDto(
    val items: List<SearchArticleDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** type=all 时两组同返；游标带 scope，续翻哪组回传哪组。 */
@Serializable
private data class ForumSearchResponse(
    val posts: SearchPostGroupDto? = null,
    val articles: SearchArticleGroupDto? = null,
)

/** 通知条目。`actor` / `post` 服务端都可能给 null（软删用户 / 已删帖）。 */
@Serializable
private data class NotificationDto(
    val id: String,
    val type: String = "comment",
    val target_type: String? = null,
    val target_id: String? = null,
    val read_at: String? = null,
    val created_at: String? = null,
    val actor: AuthorDto? = null,
    val post: NotificationPostDto? = null,
)

@Serializable
private data class NotificationPostDto(
    val id: String,
    val title: String? = null,
    val excerpt: String = "",
)

@Serializable
private data class NotificationsResponse(
    val notifications: List<NotificationDto> = emptyList(),
    val next_cursor: String? = null,
    val unread_count: Int = 0,
)

@Serializable
private data class MarkNotificationsReadResponse(
    val marked: Int = 0,
    val unread_count: Int = 0,
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
    viewsCount = views_count,
    pinnedAt = parseIso8601(pinned_at),
    author = author?.toAuthor(),
    images = images.map { ForumImage(it.id, it.asset_url, it.mime_type, it.sort_order) },
    tags = tags.map { ForumTag(it.id, it.name, it.slug) },
    bookmarked = bookmarked ?: false,
)

private fun HandbookSectionDto.toSectionInfo() = HandbookSectionInfo(
    slug = slug,
    // name 缺失时退回 slug：板块名在 TopicCatalog 能补，但那属于 UI 层的选择。
    name = name ?: slug,
    articleCount = articleCount ?: 0,
    children = children.map { HandbookChild(it.slug, it.name, it.articleCount ?: 0) },
)

private fun HandbookArticleSummaryDto.toSummary() = HandbookArticleSummary(
    id = id,
    tagSlug = tagSlug,
    title = title,
    authorDisplay = authorDisplay?.takeIf { it.isNotBlank() },
    publishedAt = parseIso8601(publishedAt),
    sourcePost = sourcePost?.let { HandbookArticleRef(it.id, it.title) },
)

private fun SearchPostDto.toSearchPost() = ForumSearchPost(
    id = id,
    title = title,
    snippet = snippet,
    author = author?.toAuthor(),
    likesCount = likesCount,
    commentsCount = commentsCount,
    viewsCount = viewsCount,
    createdAt = parseIso8601(createdAt),
)

private fun SearchArticleDto.toSearchArticle() = ForumSearchArticle(
    id = id,
    title = title,
    snippet = snippet,
    tagSlug = tagSlug,
    publishedAt = parseIso8601(publishedAt),
    sourcePostId = sourcePostId,
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

private fun NotificationDto.toNotification() = ForumNotification(
    id = id,
    type = type,
    targetId = target_id.orEmpty(),
    readAt = parseIso8601(read_at),
    createdAt = parseIso8601(created_at),
    actor = actor?.toAuthor(),
    post = post?.let { ForumNotificationPost(it.id, it.title, it.excerpt) },
)