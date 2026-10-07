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
     * 服务端返回的收藏态，只在帖子详情里有（列表接口不下发，见
     * `posts/route.ts` 的 selectColumns）。列表里恒为 false。
     */
    val bookmarked: Boolean = false,
    /** 该帖是否在启用的精选区里，同样只有详情接口给。 */
    val featured: Boolean = false,
    /**
     * 本地记的点赞态。**不是服务端事实** —— 服务端没有对应只读接口，
     * 冷启动恒为 false，用户点一次才知道真相。
     */
    val likedByMe: Boolean = false,
) {
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

/** 帖子列表的一页。[nextCursor] 原样回传即可，不要自己解析。 */
data class ForumPostPage(
    val posts: List<ForumPost>,
    val nextCursor: String?,
)

/** 评论列表。[authors] 是以 userId 为 key 的 map，可能缺 key（作者被软删除）。 */
data class ForumCommentsPage(
    val comments: List<ForumComment>,
    val authors: Map<String, ForumAuthor>,
)

/** 帖子排序。与后端 `parsePublicListSort` 一一对应，写错值后端返 400。 */
enum class ForumSort(val key: String) {
    Latest("latest"),
    Top("top"),
    ;

    companion object {
        fun fromKey(key: String?): ForumSort = if (key == "top") Top else Latest
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
