package tech.iflink.seuwiki.models

/**
 * 文档条目（旧 `/api/site/docs/` 信源）。
 *
 * 手册树与经验长文列表接口已移除（手册改走论坛后端 `/api/handbook/` 系列接口，经验长文
 * 整体下线），这个模型只剩一个用途：`GET /api/site/docs/{slug}` 详情响应里的
 * 条目元信息——收藏与深链仍会按 slug 打开旧文档条目
 * （见 `ui/detail/DocScreens.kt` 的 `DocEntryDetailScreen`）。
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

/** 条目属于哪一套文档。[Survival] 是生存手册，[Experience] 是经验长文。 */
enum class DocKind(val key: String) {
    Survival("survival"),
    Experience("experience");

    companion object {
        fun fromKey(key: String?): DocKind? = entries.firstOrNull { it.key == key }
    }
}
