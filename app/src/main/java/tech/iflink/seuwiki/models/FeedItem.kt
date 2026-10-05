package tech.iflink.seuwiki.models

import androidx.annotation.StringRes
import tech.iflink.seuwiki.R

/**
 * Feed categories — the 8 `CATEGORIES` of seu-wiki-v2 `industry/taxonomy.ts`.
 *
 * [key] is taken from the contract and is the stable identifier: it goes into
 * request params and route paths, so it must never be localized. The contract
 * ships no icon, so [iconKey] is a client-side choice made to match the SF
 * Symbol the iOS build uses for the same category.
 *
 * The display name lives in `strings.xml` as [labelRes] rather than a `String`
 * field, so the compiler catches a missing/renamed resource at compile time
 * instead of it silently rendering an empty chip.
 */
enum class FeedCategory(
    val key: String,
    @StringRes val labelRes: Int,
    val iconKey: String,
) {
    Academic("academic", R.string.feed_category_academic, "building.columns"),
    Aid("aid", R.string.feed_category_aid, "gift"),
    Competition("competition", R.string.feed_category_competition, "trophy"),
    Exchange("exchange", R.string.feed_category_exchange, "airplane"),
    Career("career", R.string.feed_category_career, "briefcase"),
    Club("club", R.string.feed_category_club, "person.3"),
    Life("life", R.string.feed_category_life, "fork.knife"),
    News("news", R.string.feed_category_news, "newspaper");

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
