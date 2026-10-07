package tech.iflink.seuwiki.models

/**
 * 论坛领域模型。
 *
 * 字段**逐个对着后端契约抄**，没有一处是本 App 自己加的：
 * - 列表/详情结构见 `seu-wiki-forum` `src/lib/services/posts.ts:307-318,417-428`
 * - 作者名片见 `src/lib/services/users.ts:108-113`（只有 4 个字段，其余用户资料
 *   刻意不对外暴露）
 * - 评论见 `src/lib/services/comments.ts:98-130`
 *
 * 契约里有几个坑，这里先写死，后面写客户端时不要"顺手优化"掉：
 *
 * 1. **`likes_count` / `comments_count` 是 posts 表上的反范式 integer 列**，
 *    不是聚合出来的。而且 `comments_count` 在评论被软删除后**不会递减**
 *    （后端没有对应逻辑）—— 界面上数字只增不减是服务端行为，不是本App 的 bug。
 * 2. **`author` 可能为 null**：作者被软删除时后端就返回 null
 *    （`posts.ts:425` 的 `?? null`），所以这里不是非空类型。
 * 3. **服务端没有"我是否已点赞"的只读接口**。`POST /api/post-likes` 是 toggle，
 *    只在点击后才知道结果。已点赞状态只能靠本地记，所以 [ForumPost.likedByMe]
 *    是**客户端本地状态**，冷启动后为 false —— 别把它当成服务端事实。
 */
data class ForumPost(
    val id: String,
    val title: String?,
    val content: String,
    val postType: String,
    val createdAt: Long?,
    val likesCount: Int,
    val commentsCount: Int,
    val author: ForumAuthor?,
    val images: List<ForumImage> = emptyList(),
    val tags: List<ForumTag> = emptyList(),
    /**
     * 浏览数。`views_count` 是 posts 表上的反范式列，列表与详情都下发；
     * 详情页打开时客户端会再打一次 `POST /api/posts/:id/view` 让它 +1
     * （见 ForumStore.loadDetail），所以详情里看到的数字可能比列表大 1。
     */
    val viewsCount: Int = 0,
    /**
     * 置顶时间。非空即置顶帖 —— 热榜（`sort=hot`）里置顶帖排在最前。
     * PostgREST 序列化的 timestamptz 可能带 6 位微秒，`parseIso8601` 能解。
     */
    val pinnedAt: Long? = null,
    /**
     * 服务端返回的收藏态，只在帖子详情里有（列表接口不下发，见
     * `posts/route.ts` 的 selectColumns）。列表里恒为 false。
     */
    val bookmarked: Boolean = false,
    /** 该帖是否在启用的精选区里，同样只有详情接口给。 */
    val featured: Boolean = false,
    /**
     * 该帖被沉淀成的手册文章（`GET /api/posts/:id` 的 `handbook_article`，
     * 取最新一篇未删除的），只在详情接口下发。详情页据此给「查看手册文章」入口。
     */
    val handbookArticle: HandbookArticleRef? = null,
    /**
     * 本地记的点赞态。**不是服务端事实** —— 服务端没有对应只读接口，
     * 冷启动恒为 false，用户点一次才知道真相。
     */
    val likedByMe: Boolean = false,
) {
    /** 是否置顶：`pinned_at` 非空即置顶。列表卡片据此加置顶标记。 */
    val isPinned: Boolean get() = pinnedAt != null

    /**
     * 列表里的正文摘要。
     *
     * 后端在收藏接口里给的是 `excerpt`（服务端截的 ≤110 字符），但帖子列表
     * 直接给全文 `content`，摘要得客户端自己截。空白的正文（全图片帖）截出
     * 空串时退回 postType，避免行里出现一条横线。
     */
    val excerpt: String
        get() = content.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 110) it else it.take(110) + "…"
        }.ifEmpty { postType }
}

/**
 * 作者名片。
 *
 * 后端 `PublicUserCard` 只有这 4 个字段 —— `bio` / `contact_email` / `wechat_id`
 * 等资料**故意不出现在任何 API 响应里**，所以这里也不能加。
 *
 * [displayName] 可能为 null（`users.display_name` 可空），[nameOrFallback] 负责兜底。
 */
data class ForumAuthor(
    val id: String,
    val displayName: String?,
    val username: String?,
    val avatarUrl: String?,
) {
    /** 用户没设昵称时退回 `@handle`，再没有就退回「匿名」。不能渲染成空白。 */
    val nameOrFallback: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: "匿名"
}

/** 帖子图片附件。`assetUrl` 是 `/api/media/...` 相对路径，要拼 baseUrl。 */
data class ForumImage(
    val id: String,
    val assetUrl: String,
    val mimeType: String?,
    val sortOrder: Int,
)

/**
 * 话题标签。
 *
 * 后端只有 `id` / `name` / `slug` 三个字段对外 —— **没有 `parent_id`**，
 * 也就意味着服务端**没有暴露标签的两级结构**，更没有 `/api/tags` 列表接口。
 * 两级结构只能靠本地镜像的 [TopicCatalog] 还原（它对应后端 `lib/tags/catalog.mjs`）。
 */
data class ForumTag(
    val id: String,
    val name: String,
    val slug: String,
)

/** 评论。[parentId] 非空表示这是一条楼中楼回复。 */
data class ForumComment(
    val id: String,
    val authorId: String,
    val content: String,
    val parentId: String?,
    val likesCount: Int,
    val createdAt: Long?,
)

/** `GET /api/me` 里的 `viewer`。字段是 **camelCase**，与帖子接口的 snake_case 相反。 */
data class ForumViewer(
    val id: String,
    val displayName: String?,
    val avatarUrl: String?,
    val roleLabel: String?,
    val email: String?,
    val username: String?,
    val forumRole: String?,
    val capabilities: List<String> = emptyList(),
) {
    val nameOrFallback: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: "我"
}

/**
 * 帖子列表的一页。
 *
 * [nextCursor] 原样回传即可，不要自己解析。**热榜（sort=hot）例外**：它用 offset
 * 分页（热度分是动态的，keyset 游标会错位），响应给的是 `next_offset` /
 * `total_count` 而不是 `next_cursor`；两者二选一，不会同时非空。
 */
data class ForumPostPage(
    val posts: List<ForumPost>,
    val nextCursor: String?,
    /** 仅热榜：下一页的 offset；null 表示没有更多。 */
    val nextOffset: Int? = null,
    /** 仅热榜：命中的总条数。 */
    val totalCount: Int? = null,
)

/** 评论列表。[authors] 是以 userId 为 key 的 map，可能缺 key（作者被软删除）。 */
data class ForumCommentsPage(
    val comments: List<ForumComment>,
    val authors: Map<String, ForumAuthor>,
)

/**
 * 帖子排序。与后端 `parsePublicListSort` 一一对应，写错值后端返 400。
 *
 * 注意 [Hot] 的分页与其余两个**不同**：它是 offset 分页（`next_offset`），
 * 其余是 keyset 游标（`next_cursor`）。对 hot 传 cursor 后端直接
 * 400 `INVALID_CURSOR`。
 */
enum class ForumSort(val key: String) {
    Latest("latest"),
    Top("top"),
    Hot("hot"),
    ;

    companion object {
        fun fromKey(key: String?): ForumSort = entries.firstOrNull { it.key == key } ?: Latest
    }
}

/** 可删除内容的类型，对应 `DELETE /api/content/[targetType]/[targetId]`。 */
enum class ForumTargetType(val key: String) {
    Post("post"),
    Comment("comment"),
}
/** 一条收藏记录。[post] 是服务端剔除已删除内容后剩下的目标帖。 */
data class ForumBookmarkedPost(
    val bookmarkId: String,
    val post: ForumPost,
)

/**
 * 收藏列表的一页。
 *
 * [nextCursor] 非 null **不等于还有下一页** —— 服务端会剔除目标已删除的条目但游标
 * 照样前进（`services/bookmarks.ts:141`），所以会出现「本页不足 limit 条却还有游标」。
 */
data class ForumBookmarkPage(
    val items: List<ForumBookmarkedPost>,
    val nextCursor: String?,
)

/**
 * 帖子详情里关联的手册文章（`handbook_article`：指向该帖的未删除手册文章中
 * 最新一篇）。只有 id 与标题，够渲染「查看手册文章」入口。
 */
data class HandbookArticleRef(
    val id: String,
    val title: String,
)

// MARK: - 东大生存手册（forum.seu.wiki /api/handbook/*）

/**
 * 手册板块（`GET /api/handbook/sections` 的 `sections[]`）。
 *
 * 板块就是论坛的话题标签：手册按 tag 组织，主题板块下挂子标签。
 * [articleCount] 主题板块是其全部子标签文章数之和。
 */
data class HandbookSectionInfo(
    val slug: String,
    val name: String,
    val articleCount: Int,
    val children: List<HandbookChild> = emptyList(),
)

/** 板块下的子标签。板块详情接口里的 children **没有** article_count，只有列表接口给。 */
data class HandbookChild(
    val slug: String,
    val name: String,
    val articleCount: Int = 0,
)

/**
 * `GET /api/handbook/sections/:slug` 的响应。
 *
 * [type] 为 "topic" 时 [children] 是该主题的子标签（不含文章数）；
 * 为 "tag" 时 [parentSlug] 指向所属主题。[articles] 按 published_at 倒序。
 */
data class HandbookSectionPage(
    val type: String,
    val slug: String,
    val name: String,
    val parentSlug: String? = null,
    val children: List<HandbookChild> = emptyList(),
    val articles: List<HandbookArticleSummary> = emptyList(),
)

/** 手册文章摘要（板块详情与 `GET /api/handbook/articles` 列表共用）。 */
data class HandbookArticleSummary(
    val id: String,
    val tagSlug: String,
    val title: String,
    val authorDisplay: String?,
    val publishedAt: Long?,
    /** 这篇文章从哪个帖子沉淀而来；null 表示编辑直接撰写。 */
    val sourcePost: HandbookArticleRef? = null,
)

/** 手册文章详情的「原帖」：带计数与作者，够渲染「查看原帖讨论」卡片。 */
data class HandbookSourcePost(
    val id: String,
    val title: String?,
    val likesCount: Int,
    val commentsCount: Int,
    val viewsCount: Int,
    val author: ForumAuthor?,
)

/**
 * `GET /api/handbook/articles/:id` 的文章详情。
 *
 * [contentHtml] 是服务端消毒过的 HTML，复用 `campusHtmlToAnnotatedString`
 * 渲染管线（与长文详情同一条路径）；`content_md` 客户端用不到，不解析。
 */
data class HandbookArticle(
    val id: String,
    val tagSlug: String,
    val title: String,
    val authorDisplay: String?,
    val contentHtml: String,
    val publishedAt: Long?,
    val updatedAt: Long?,
    val sourcePost: HandbookSourcePost? = null,
)

// MARK: - 关注（/api/follows、/api/feed/following）

/** `GET /api/follows?target_type=tag` 里的一条关注记录。 */
data class FollowedTag(
    val slug: String,
    val name: String,
    /** 所属主题 slug；子标签的关注也能据此外聚到主题。可能为 null。 */
    val topicSlug: String?,
)

/**
 * 关注流的引导主题（`GET /api/feed/following` 在**零关注**时随空列表下发的
 * `suggested_tags`）。是对象数组（slug/name/post_count），不是字符串数组。
 */
data class SuggestedTopic(
    val slug: String,
    val name: String,
    val postCount: Int,
)

/** `GET /api/feed/following` 的一页：keyset 游标，约定与 /api/posts latest 一致。 */
data class FollowingFeedPage(
    val posts: List<ForumPost>,
    val nextCursor: String?,
    /** 仅零关注时非空。 */
    val suggestedTags: List<SuggestedTopic> = emptyList(),
)

// MARK: - 论坛搜索（GET /api/search）

/**
 * 搜索结果里的帖子。**它不是 [ForumPost]**：搜索接口给的是 `snippet`
 * （服务端按关键词截的片段），没有 content / images / tags。
 */
data class ForumSearchPost(
    val id: String,
    val title: String?,
    val snippet: String,
    val author: ForumAuthor?,
    val likesCount: Int,
    val commentsCount: Int,
    val viewsCount: Int,
    val createdAt: Long?,
)

/** 搜索结果里的手册文章（`articles.items[]`）。 */
data class ForumSearchArticle(
    val id: String,
    val title: String,
    val snippet: String,
    val tagSlug: String,
    val publishedAt: Long?,
    /** 直接跳原帖用的 id；没有来源帖时为 null。 */
    val sourcePostId: String?,
)

/**
 * `GET /api/search?q=&type=all` 的响应：posts 与 articles 两组各自带游标。
 * 游标带 scope，续翻哪一组就回传哪一组的 `next_cursor`。
 */
data class ForumSearchResults(
    val posts: List<ForumSearchPost>,
    val postsNextCursor: String?,
    val articles: List<ForumSearchArticle>,
    val articlesNextCursor: String?,
)
