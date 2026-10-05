package tech.iflink.seuwiki.models

import androidx.annotation.StringRes
import tech.iflink.seuwiki.R

/**
 * 生存手册 / 经验长文条目，对应 `GET /api/site/docs/survival` 与
 * `GET /api/site/docs/experience` 里的 `items[]`。
 *
 * 这两类内容共用同一套字段，只是 `kind` 不同，所以用同一个模型；
 * 搜索接口 `/api/site/pool` 的 `docs[]` 也是它的精简投影（见 [DocRef]）。
 */
data class DocEntry(
    val slug: String,
    val kind: DocKind,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val occurredAt: Long? = null,
    val category: String? = null,
    val grade: String? = null,
    val college: String? = null,
    /** 所属「篇」，例如「观点篇」。 */
    val part: String? = null,
    /** 篇内排序号。 */
    val position: Int? = null,
)

/** 条目属于哪一套文档。[survival] 是生存手册，[experience] 是经验长文。 */
enum class DocKind(val key: String, @StringRes val labelRes: Int) {
    Survival("survival", R.string.doc_kind_survival),
    Experience("experience", R.string.doc_kind_experience);

    companion object {
        fun fromKey(key: String?): DocKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 生存手册树的一个「组」。服务端返回的 `groups[].key` 可能是空串
 * （表示该篇下未再分组），所以不能用 key 做 id。
 */
data class DocGroup(
    val key: String,
    val items: List<DocEntry>,
)

/**
 * 生存手册树的一个「篇」，例如「序言 / 观点篇 / 学习篇 / 方向篇」。
 *
 * 结构是真实的「篇 → 组 → 条」三层，`GET /api/site/docs/survival` 直接给出，
 * 客户端不该自己造一套扁平的章节列表。
 */
data class DocPart(
    val key: String,
    val label: String,
    val groups: List<DocGroup>,
) {
    /** 该篇下的全部条目，按服务端给定的篇内顺序。 */
    val entries: List<DocEntry> get() = groups.flatMap { it.items }
}

/**
 * `/api/site/pool` 的 `docs[]` 一项：搜索结果里的手册 / 经验条目。
 *
 * 与 [DocEntry] 的区别是它可能带 [anchor] —— 命中的是正文里的某个小节，
 * 点进去应当定位到那一小节而不是从头开始。
 */
data class DocRef(
    val slug: String,
    val kind: DocKind,
    val title: String,
    val description: String? = null,
    val occurredAt: Long? = null,
    val anchor: DocAnchor? = null,
)

/**
 * 搜索命中的正文锚点。
 *
 * 服务端给的是**标题文案**（实测 `{id: "关于保研心态", text: "关于保研心态。"}`），
 * 不是一个能直接当 URL 片段用的 id：正文的 `id` 往往是 slugify 过的
 * （实测 `h2 id="11-大学的定义"`），两者对不上。
 *
 * 所以定位时以 [text] 为准，按标题文案在正文里定位；[id] 仅在恰好相等时直接用。
 */
data class DocAnchor(
    val id: String,
    val text: String,
)

/**
 * 经验长文接口返回的分面筛选项，对应 `filters[]`。
 *
 * `values` 是服务端给的**真实**可选项（实测场景 7 项、年级 6 项、学院 4 项），
 * 界面上不应该自己编一套分类。
 */
data class DocFilter(
    val key: String,
    val label: String,
    val values: List<String>,
)

/**
 * 经验 tab 的四个子页，按 console 胶囊顺序。
 *
 * 取代原先的 `ForumFeedTab`：那个 enum 的「热门 / 关注」两页是靠
 * `MockData.forumPosts` 的假帖子撑着的，而 seu-wiki-forum 至今没有任何
 * HTTP API 路由，社区功能接不通。现在四个子页全部来自真实文档接口。
 */
enum class ExperienceTab(val key: String, @StringRes val labelRes: Int) {
    /** 经验长文列表。 */
    Hot("hot", R.string.experience_tab_hot),

    /** 经验长文的分面筛选（场景 / 年级 / 学院）。 */
    Topics("topics", R.string.experience_tab_topics),

    /** 东大生存手册文档树。 */
    Handbook("handbook", R.string.experience_tab_handbook),

    /** 社区关注 —— 尚未上线，界面给诚实空状态。 */
    Following("following", R.string.experience_tab_following);

    companion object {
        val all: List<ExperienceTab> get() = entries.toList()
    }
}
