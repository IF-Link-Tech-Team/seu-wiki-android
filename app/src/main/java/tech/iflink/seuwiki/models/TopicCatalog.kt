package tech.iflink.seuwiki.models

/**
 * Forum topic — one of the 8 study-direction topics in seu-forum's tag catalog.
 *
 * The catalog has no HTTP endpoint, so it is mirrored here from
 * `src/lib/tags/catalog.mjs` (8 topics + 29 subtags, two levels).
 *
 * 这些话题名与子标签是**真实的目录内容**，不是界面文案，所以刻意不进
 * `strings.xml`：翻译它们会破坏与后端 slug 的对应关系。
 *
 * 注意这里**没有** `postCount`。原来的实现给每个话题硬编码了 1284 / 862 / 743
 * 这样的帖子数，而论坛后端没有暴露按话题统计的 HTTP 路由，这些数字根本取不到
 * 来源 —— 界面上却会把它们当真实数据展示给用户。iOS 侧从来没有显示过这个字段
 * （它只存在于 `PreviewSample.swift` 样例数据里），只有 Android 的个人页
 * 「关注的话题」把它渲染了出来。取不到真实数据就不显示，这比显示一个编造的
 * 数字诚实。
 */
data class ForumTopic(
    val slug: String,
    val name: String,
    val iconKey: String,
    val subtags: List<ForumSubtag>,
)

/** A second-level tag under a [ForumTopic]. */
data class ForumSubtag(
    val slug: String,
    val name: String,
)

/** The full study tag catalog, mirroring seu-forum's `catalog.mjs`. */
object TopicCatalog {

    val topics: List<ForumTopic> = listOf(
        ForumTopic(
            slug = "baoyan", name = "保研", iconKey = "graduationcap",
            subtags = listOf(
                ForumSubtag("baoyan-jingyan", "经验分享"),
                ForumSubtag("baoyan-xialingying", "夏令营"),
                ForumSubtag("baoyan-yutuimian", "预推免"),
                ForumSubtag("baoyan-wenshu", "文书修改"),
                ForumSubtag("baoyan-taoci", "导师套磁"),
            ),
        ),
        ForumTopic(
            slug = "kaoyan", name = "考研", iconKey = "book",
            subtags = listOf(
                ForumSubtag("kaoyan-zexiao", "择校择专业"),
                ForumSubtag("kaoyan-chushi", "初试经验"),
                ForumSubtag("kaoyan-fushi", "复试调剂"),
                ForumSubtag("kaoyan-ziliao", "资料分享"),
            ),
        ),
        ForumTopic(
            slug = "liuxue", name = "留学", iconKey = "airplane",
            subtags = listOf(
                ForumSubtag("liuxue-dingwei", "申请定位"),
                ForumSubtag("liuxue-yuyan", "语言考试"),
                ForumSubtag("liuxue-wenshu", "文书推荐信"),
                ForumSubtag("liuxue-offer", "offer比较"),
            ),
        ),
        ForumTopic(
            slug = "srtp", name = "科研与 SRTP", iconKey = "flask",
            subtags = listOf(
                ForumSubtag("srtp-shenqing", "项目申请"),
                ForumSubtag("srtp-jinzu", "进组经验"),
                ForumSubtag("srtp-lunwen", "论文写作"),
                ForumSubtag("srtp-zhongqi", "中期答辩"),
            ),
        ),
        ForumTopic(
            slug = "jingsai", name = "学科竞赛", iconKey = "trophy",
            subtags = listOf(
                ForumSubtag("jingsai-shumo", "数学建模"),
                ForumSubtag("jingsai-dianzi", "电子设计"),
                ForumSubtag("jingsai-tiaozhanbei", "挑战杯"),
                ForumSubtag("jingsai-acm", "ACM"),
            ),
        ),
        ForumTopic(
            slug = "zhuanye", name = "转专业", iconKey = "arrow.triangle.branch",
            subtags = listOf(
                ForumSubtag("zhuanye-zhengce", "政策解读"),
                ForumSubtag("zhuanye-kaohe", "考核经验"),
            ),
        ),
        ForumTopic(
            slug = "shixi", name = "实习就业", iconKey = "briefcase",
            subtags = listOf(
                ForumSubtag("shixi-neitui", "实习内推"),
                ForumSubtag("shixi-qiuzhao", "秋招春招"),
                ForumSubtag("shixi-mianjing", "面经"),
            ),
        ),
        ForumTopic(
            slug = "shenghuo", name = "校园生活", iconKey = "leaf",
            subtags = listOf(
                ForumSubtag("shenghuo-shitang", "食堂测评"),
                ForumSubtag("shenghuo-sushe", "宿舍"),
                ForumSubtag("shenghuo-xuanke", "选课避雷"),
            ),
        ),
    )

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
