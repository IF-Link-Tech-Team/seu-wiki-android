package tech.iflink.seuwiki.models

/**
 * Feed categories — the 8 `CATEGORIES` of seu-wiki-v2 `industry/taxonomy.ts`.
 *
 * [key] and [label] are taken from the contract. The contract ships no icon, so
 * [iconKey] is a client-side choice made to match the SF Symbol the iOS build
 * uses for the same category.
 *
 * [label] is not named `name` because Kotlin's `Enum.name` is final.
 */
enum class FeedCategory(
    val key: String,
    val label: String,
    val iconKey: String,
) {
    Academic("academic", "教务", "building.columns"),
    Aid("aid", "奖助", "gift"),
    Competition("competition", "竞赛科研", "trophy"),
    Exchange("exchange", "交流升学", "airplane"),
    Career("career", "实习就业", "briefcase"),
    Club("club", "社团活动", "person.3"),
    Life("life", "生活服务", "fork.knife"),
    News("news", "校园新闻", "newspaper");

    companion object {
        fun fromKey(key: String?): FeedCategory? = entries.firstOrNull { it.key == key }
        val all: List<FeedCategory> get() = entries.toList()
    }
}

/** How valuable a notice is, mirroring the contract's `CampusAudience.valueTier`. */
enum class ValueTier(val key: String) {
    Action("action"),
    Opportunity("opportunity"),
    News("news");

    companion object {
        fun fromKey(key: String?): ValueTier =
            entries.firstOrNull { it.key == key } ?: News
    }
}

/**
 * Campus audience semantics.
 *
 * The live site-feed responses do not currently carry these fields, so the
 * list values are usually empty — [FeedFilter] treats empty as "unknown" and
 * does not exclude on it, matching the iOS behaviour.
 */
data class CampusAudience(
    val identities: List<String> = emptyList(),
    val colleges: List<String> = emptyList(),
    val grades: List<String> = emptyList(),
    val deadline: Long? = null,
    val valueTier: ValueTier = ValueTier.News,
)

/** One notice in the feed. */
data class FeedItem(
    val id: String,
    val title: String,
    val summary: String,
    val sourceName: String,
    val category: FeedCategory,
    val tags: List<String> = emptyList(),
    val publishedAt: Long? = null,
    val originalUrl: String? = null,
    val score: Int = 0,
    val isSelected: Boolean = false,
    val audience: CampusAudience = CampusAudience(),
    val matchReasons: List<String> = emptyList(),
    val channel: String = "news",
)
