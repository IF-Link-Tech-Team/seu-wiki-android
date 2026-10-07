package tech.iflink.seuwiki.data

import kotlinx.serialization.Serializable
import tech.iflink.seuwiki.models.CampusAudience
import tech.iflink.seuwiki.models.FeedCategory
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.models.ValueTier
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `seu.wiki` 只读 API 的客户端（`/api/site` 这一组）。
 *
 * 契约见 seu-wiki-v2 `packages/contracts/src/site.ts`，对应 iOS 端的
 * `FeedAPIClient`。匿名可访问，登录非必需；已登录时自动附带 Bearer 凭证。
 *
 * 四个接口全是 GET，且 kotlinx-serialization 已在依赖里，所以这里直接用
 * `HttpURLConnection` 手写，不引入 Retrofit / OkHttp —— 少两个依赖，也少一层
 * 注解处理器。所有方法都做阻塞 I/O，调用方需在 IO 调度器上执行（见 [FeedStore]）。
 */
class FeedApiClient(
    private val baseUrl: String = "https://seu.wiki",
    /**
     * 取当前 access token；返回 null 就匿名请求。
     *
     * 这些接口匿名可访问，登录不是前置条件 —— 但带上 `Authorization: Bearer` 后
     * 后端会按登录身份返回个人化内容（契约见 seu-wiki-forum `docs/auth.md` §2）。
     * 该头是权威凭证：token 无效只会得到匿名/401，后端**不会**回退 Cookie 会话，
     * 所以这里传出去的必须是 [AuthStore.accessToken] 续过期的结果。
     */
    private val tokenProvider: (suspend () -> String?)? = null,
) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** GET /api/site/timeline?channel=all&category=&cursor=&limit= */
    suspend fun timeline(category: FeedCategory?, cursor: String?, limit: Int = PageSize): FeedPage {
        val query = buildList {
            add("channel" to "all")
            add("limit" to limit.toString())
            category?.let { add("category" to it.key) }
            cursor?.let { add("cursor" to it) }
        }
        val dto: TimelineResponse = get("/api/site/timeline", query)
        return FeedPage(dto.cards.map { it.item.toFeedItem() }, dto.nextCursor)
    }

    /** GET /api/site/for-you?college=&degree=&grade=&interests=&cursor=&limit= */
    suspend fun forYou(
        college: String,
        degree: String,
        grade: String,
        interests: List<String>,
        cursor: String?,
        limit: Int = PageSize,
    ): FeedPage {
        val query = buildList {
            add("limit" to limit.toString())
            if (college.isNotEmpty()) add("college" to college)
            if (degree.isNotEmpty()) add("degree" to degree)
            if (grade.isNotEmpty()) add("grade" to grade)
            if (interests.isNotEmpty()) add("interests" to interests.joinToString(","))
            cursor?.let { add("cursor" to it) }
        }
        val dto: ForYouResponse = get("/api/site/for-you", query)
        return FeedPage(dto.items.map { it.toFeedItem() }, dto.nextCursor)
    }

    /** GET /api/site/items/{id} —— 详情，列表响应里没有 body / links.original。 */
    suspend fun itemDetail(id: String): RemoteFeedDetail {
        val dto: ItemDetailResponse = get("/api/site/items/${encode(id)}", emptyList())
        return RemoteFeedDetail(
            originalTitle = dto.originalTitle,
            summary = dto.summary,
            reason = dto.reason,
            bodyHtml = dto.body?.zh,
            originalUrl = dto.links?.original,
        )
    }

    /**
     * `GET /api/site/pool?q=&type=feed&page=&limit=` —— 资讯池搜索。
     *
     * 只留 `items`（资讯卡）：响应里的 `docs`（手册 / 经验条目）已随
     * `/api/site/docs` 信源切换弃用，帖子与手册文章的搜索改走论坛后端的
     * `GET /api/search`（见 [ForumApiClient.search]）。
     *
     * [limit] 服务端上限 40，超出会被截断，这里先夹住。
     */
    suspend fun pool(
        query: String,
        type: String = "feed",
        page: Int = 1,
        limit: Int = 40,
    ): PoolPage {
        val dto: PoolResponse = get(
            "/api/site/pool",
            buildList {
                add("q" to query)
                add("type" to type)
                add("page" to page.toString())
                add("limit" to limit.coerceIn(1, 40).toString())
            },
        )
        return PoolPage(
            items = dto.items.map { it.toFeedItem() },
            page = dto.page,
            pageCount = dto.pageCount,
            total = dto.total,
        )
    }

    // MARK: - Plumbing

    private suspend inline fun <reified T> get(
        path: String,
        query: List<Pair<String, String>>,
    ): T {
        val token = tokenProvider?.invoke()
        val url = buildString {
            append(baseUrl).append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" })
            }
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            // for-you 是 `private, no-store`，因人而异，不该缓存；timeline 带 ETag，
            // 由 HttpURLConnection 的条件请求自行协商 304。
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw FeedApiException(status)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return json.decodeFromString(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 服务端返回了非 2xx，状态码原样抛出便于上层区分。 */
    class FeedApiException(val status: Int) : IOException("HTTP $status")

    companion object {
        const val PageSize = 20
    }
}

/** One page of items plus the opaque keyset cursor for the next one. */
data class FeedPage(val items: List<FeedItem>, val nextCursor: String?)

/** pool 响应是按页翻的，不是 cursor。 */
data class PoolPage(
    val items: List<FeedItem>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
)

/**
 * `/api/site/items/{id}` 里 app 用到的那部分。
 *
 * [bodyHtml] 仍是服务端的白名单 HTML，转成带样式文本是
 * [campusHtmlToAnnotatedString] 的职责，这样即使解析失败也能退到摘要而不是空白正文。
 */
data class RemoteFeedDetail(
    val originalTitle: String?,
    val summary: String?,
    val reason: String?,
    val bodyHtml: String?,
    val originalUrl: String?,
)

// MARK: - DTO（只声明用到的字段，其余靠 ignoreUnknownKeys 忽略）

@Serializable
private data class SourceDto(val name: String)

/** 契约里有 campus 受众，但线上响应目前不下发；全部可空容错。 */
@Serializable
private data class CampusDto(
    val identities: List<String>? = null,
    val colleges: List<String>? = null,
    val grades: List<String>? = null,
    val deadline: String? = null,
    val valueTier: String? = null,
)

@Serializable
private data class FeedItemSummaryDto(
    val id: String,
    val title: String,
    val summary: String? = null,
    val reason: String? = null,
    val source: SourceDto,
    val publishedAt: String? = null,
    val timelineAt: String,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val score: Int? = null,
    val selected: Boolean = false,
    val campus: CampusDto? = null,
    val matchReasons: List<String>? = null,
    val rankScore: Double? = null,
)

@Serializable
private data class TimelineResponse(
    val cards: List<TimelineCard> = emptyList(),
    val nextCursor: String? = null,
) {
    @Serializable
    data class TimelineCard(val item: FeedItemSummaryDto)
}

@Serializable
private data class ForYouResponse(
    val items: List<FeedItemSummaryDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
private data class PoolResponse(
    val items: List<FeedItemSummaryDto> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
)

@Serializable
private data class ItemDetailResponse(
    val originalTitle: String? = null,
    val summary: String? = null,
    val reason: String? = null,
    val links: LinksDto? = null,
    val body: BodyDto? = null,
) {
    @Serializable
    data class LinksDto(val original: String? = null)

    @Serializable
    data class BodyDto(val zh: String? = null)
}

/**
 * DTO → model。
 *
 * 与 `FeedItemSummaryDTO.feedItem()` 逐字段对齐：摘要回退到 `reason`，发布时间
 * 回退到 `timelineAt`，未知分类降级为 校园新闻，受众缺失时保持"未知"而不是空集合，
 * 这样客户端的「全部」筛选不会误删线上条目。
 */
private fun FeedItemSummaryDto.toFeedItem(): FeedItem {
    val audience = CampusAudience(
        identities = campus?.identities.orEmpty(),
        colleges = campus?.colleges.orEmpty(),
        grades = campus?.grades.orEmpty(),
        deadline = campus?.deadline?.let { parseIso8601(it) },
        valueTier = ValueTier.fromKey(campus?.valueTier),
    )
    return FeedItem(
        id = id,
        title = title,
        summary = summary ?: reason.orEmpty(),
        sourceName = source.name,
        category = FeedCategory.fromKey(category) ?: FeedCategory.News,
        tags = tags,
        publishedAt = publishedAt?.let { parseIso8601(it) } ?: parseIso8601(timelineAt),
        // 列表响应不含 links，进入详情时由 itemDetail 补齐。
        originalUrl = null,
        score = score ?: 0,
        isSelected = selected,
        audience = audience,
        matchReasons = matchReasons.orEmpty(),
    )
}

/**
 * 宽松解析服务端的时间字段，任何一种格式解析不了都返回 null 而不是抛异常。
 *
 * 服务端下发的 `occurredAt` / `publishedAt` / `campus.deadline` 实际有三种形态：
 * 1. 带时区的 ISO-8601：`2026-10-04T04:47:20.333Z`、`2025-09-30T08:00:00+08:00`；
 * 2. 不带时区的本地时间：`2025-09-30T08:00:00`；
 * 3. **只有日期**：`2025-09-30`。
 *
 * 第 3 种是 `deadline` 的常态（实测多条通知的截止时间都只有年月日）。
 * `Instant.parse` 和 `LocalDateTime.parse` 对它**都会抛
 * `DateTimeParseException`**，于是原来的实现里截止时间被整个丢掉 ——
 * 界面上「10 月 12 日截止」那条横幅从来没出现过。补上 `LocalDate` 分支。
 *
 * 只有日期时按 **UTC 零点**解释：这是唯一一个不需要猜时区的选择。
 */
internal fun parseIso8601(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
        ?: runCatching {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
}
