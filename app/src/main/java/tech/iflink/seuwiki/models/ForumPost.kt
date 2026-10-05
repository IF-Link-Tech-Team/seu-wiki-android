package tech.iflink.seuwiki.models

/**
 * Forum topic — one of the 8 study-direction topics in seu-forum's tag catalog.
 *
 * The catalog has no HTTP endpoint, so it is mirrored here from
 * `src/lib/tags/catalog.mjs` (8 topics + 29 subtags, two levels).
 */
data class ForumTopic(
    val slug: String,
    val name: String,
    val iconKey: String,
    val subtags: List<ForumSubtag>,
    val postCount: Int = 0,
)

/** A second-level tag under a [ForumTopic]. */
data class ForumSubtag(
    val slug: String,
    val name: String,
)

/** A forum post, shaped from seu-forum's `PublicPostListItem`. */
data class ForumPost(
    val id: String,
    val authorName: String,
    val authorHeadline: String,
    val title: String,
    val excerpt: String,
    val tags: List<String>,
    val commentsCount: Int,
    val likesCount: Int,
    val viewsCount: Int = 0,
    val createdAt: Long? = null,
    val isFeatured: Boolean = false,
    val images: List<ForumImage> = emptyList(),
)

/** An attached post image. [url] may be absolute or `/api/media/...` relative. */
data class ForumImage(
    val id: String,
    val url: String,
)

/** The four Experience sub-pages, in console order. */
enum class ForumFeedTab(val label: String) {
    Hot("热门"),
    Following("关注"),
    Topics("话题"),
    Handbook("生存手册");

    companion object {
        val all: List<ForumFeedTab> get() = entries.toList()
    }
}

/** The full study tag catalog, mirroring seu-forum's `catalog.mjs`. */
object TopicCatalog {

    val topics: List<ForumTopic> = listOf(
        ForumTopic(
            slug = "baoyan", name = "保研", iconKey = "graduationcap", postCount = 1284,
            subtags = listOf(
                ForumSubtag("baoyan-jingyan", "经验分享"),
                ForumSubtag("baoyan-xialingying", "夏令营"),
                ForumSubtag("baoyan-yutuimian", "预推免"),
                ForumSubtag("baoyan-wenshu", "文书修改"),
                ForumSubtag("baoyan-taoci", "导师套磁"),
            ),
        ),
        ForumTopic(
            slug = "kaoyan", name = "考研", iconKey = "book", postCount = 862,
            subtags = listOf(
                ForumSubtag("kaoyan-zexiao", "择校择专业"),
                ForumSubtag("kaoyan-chushi", "初试经验"),
                ForumSubtag("kaoyan-fushi", "复试调剂"),
                ForumSubtag("kaoyan-ziliao", "资料分享"),
            ),
        ),
        ForumTopic(
            slug = "liuxue", name = "留学", iconKey = "airplane", postCount = 743,
            subtags = listOf(
                ForumSubtag("liuxue-dingwei", "申请定位"),
                ForumSubtag("liuxue-yuyan", "语言考试"),
                ForumSubtag("liuxue-wenshu", "文书推荐信"),
                ForumSubtag("liuxue-offer", "offer比较"),
            ),
        ),
        ForumTopic(
            slug = "srtp", name = "科研与 SRTP", iconKey = "flask", postCount = 519,
            subtags = listOf(
                ForumSubtag("srtp-shenqing", "项目申请"),
                ForumSubtag("srtp-jinzu", "进组经验"),
                ForumSubtag("srtp-lunwen", "论文写作"),
                ForumSubtag("srtp-zhongqi", "中期答辩"),
            ),
        ),
        ForumTopic(
            slug = "jingsai", name = "学科竞赛", iconKey = "trophy", postCount = 456,
            subtags = listOf(
                ForumSubtag("jingsai-shumo", "数学建模"),
                ForumSubtag("jingsai-dianzi", "电子设计"),
                ForumSubtag("jingsai-tiaozhanbei", "挑战杯"),
                ForumSubtag("jingsai-acm", "ACM"),
            ),
        ),
        ForumTopic(
            slug = "zhuanye", name = "转专业", iconKey = "arrow.triangle.branch", postCount = 187,
            subtags = listOf(
                ForumSubtag("zhuanye-zhengce", "政策解读"),
                ForumSubtag("zhuanye-kaohe", "考核经验"),
            ),
        ),
        ForumTopic(
            slug = "shixi", name = "实习就业", iconKey = "briefcase", postCount = 934,
            subtags = listOf(
                ForumSubtag("shixi-neitui", "实习内推"),
                ForumSubtag("shixi-qiuzhao", "秋招春招"),
                ForumSubtag("shixi-mianjing", "面经"),
            ),
        ),
        ForumTopic(
            slug = "shenghuo", name = "校园生活", iconKey = "leaf", postCount = 1120,
            subtags = listOf(
                ForumSubtag("shenghuo-shitang", "食堂测评"),
                ForumSubtag("shenghuo-sushe", "宿舍"),
                ForumSubtag("shenghuo-xuanke", "选课避雷"),
            ),
        ),
    )

    val subtagCount: Int get() = topics.sumOf { it.subtags.size }

    fun topic(slug: String): ForumTopic? = topics.firstOrNull { it.slug == slug }

    /** The topic that owns a slug, whether the slug is a topic or a subtag. */
    fun topicForSlug(slug: String): ForumTopic? =
        topics.firstOrNull { it.slug == slug }
            ?: topics.firstOrNull { t -> t.subtags.any { it.slug == slug } }

    /** Chinese display name for either a topic slug or a subtag slug. */
    fun nameForSlug(slug: String): String? =
        topic(slug)?.name
            ?: topics.firstNotNullOfOrNull { t -> t.subtags.firstOrNull { it.slug == slug }?.name }
}
