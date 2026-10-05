package tech.iflink.seuwiki.data

import kotlinx.serialization.Serializable
import tech.iflink.seuwiki.models.DocAnchor
import tech.iflink.seuwiki.models.DocEntry
import tech.iflink.seuwiki.models.DocFilter
import tech.iflink.seuwiki.models.DocGroup
import tech.iflink.seuwiki.models.DocKind
import tech.iflink.seuwiki.models.DocPart
import tech.iflink.seuwiki.models.FeedItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 手册 / 经验长文 / 统一搜索三类文档接口的客户端。
 *
 * 与 [FeedApiClient] 同样只用 `HttpURLConnection` + 已有的 kotlinx-serialization，
 * 不引入网络库；所有方法阻塞 I/O，调用方需在 IO 调度器上执行（见 [DocsStore]）。
 *
 * **这些接口一律不带 Authorization**。`api/site` 这一组都是公开只读的 GET，
 * 后端不校验鉴权（实测带一个乱写的 Bearer 一样返回 200）。挂了 token 只会带来
 * 两个坏处：把「能不能读到内容」和「登录态是否健康」耦合起来，以及让每次文档
 * 请求都可能被一次续期失败牵连。
 */
class DocsApiClient(
    private val baseUrl: String = "https://seu.wiki",
) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** `GET /api/site/docs/survival` —— 真实的「篇 → 组 → 条」文档树。 */
    suspend fun survival(): List<DocPart> {
        val dto: SurvivalResponse = get("/api/site/docs/survival", emptyList())
        return dto.parts.map { it.toDocPart() }
    }

    /**
     * `GET /api/site/docs/experience?category=&grade=&college=`
     *
     * 返回条目列表与**服务端给的**分面筛选项；筛选项随筛选结果变化，
     * 所以每次都连同 items 一起取回，不单独缓存。
     */
    suspend fun experience(
        category: String? = null,
        grade: String? = null,
        college: String? = null,
    ): ExperiencePage {
        val query = buildList {
            category?.takeIf { it.isNotBlank() }?.let { add("category" to it) }
            grade?.takeIf { it.isNotBlank() }?.let { add("grade" to it) }
            college?.takeIf { it.isNotBlank() }?.let { add("college" to it) }
        }
        val dto: ExperienceResponse = get("/api/site/docs/experience", query)
        return ExperiencePage(
            items = dto.items.map { it.toDocEntry(DocKind.Experience) },
            filters = dto.filters.map { DocFilter(it.key, it.label, it.values) },
        )
    }

    /** `GET /api/site/docs/{slug}` —— 条目详情，含正文 `html` 与目录 `headings`。 */
    suspend fun docDetail(slug: String): DocDetail {
        val dto: DocDetailResponse = get("/api/site/docs/${encodeSlug(slug)}", emptyList())
        return DocDetail(
            entry = dto.toDocEntry(DocKind.fromKey(dto.kind) ?: DocKind.Survival),
            html = dto.html.orEmpty(),
            headings = dto.headings.map {
                DocHeading(it.id, it.text, it.depth ?: 2)
            },
            sourceUrl = dto.sourceUrl,
            prevSlug = dto.prev?.slug,
            nextSlug = dto.next?.slug,
        )
    }

    // MARK: - 结果类型

    data class ExperiencePage(
        val items: List<DocEntry>,
        val filters: List<DocFilter>,
    )

    data class DocDetail(
        val entry: DocEntry,
        val html: String,
        val headings: List<DocHeading>,
        val sourceUrl: String? = null,
        val prevSlug: String? = null,
        val nextSlug: String? = null,
    )

    /** 正文里的一级标题，用于生成目录。[depth] 是 `h1..h6` 的层级。 */
    data class DocHeading(
        val id: String,
        val text: String,
        val depth: Int,
    )

    // MARK: - Plumbing

    private suspend inline fun <reified T> get(
        path: String,
        query: List<Pair<String, String>>,
    ): T {
        val url = buildString {
            append(baseUrl).append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" })
            }
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw DocsApiException(status, path)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return json.decodeFromString(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /**
         * 编码 slug 时**必须保留斜杠字面量**。
         *
         * slug 形如 `survival/观点篇/1-认识`，里面既有中文也有 `/`。后端路由是
         * 通配的 `api/site/docs/` 下的通配路由，整段 slug（含斜杠）一起匹配。所以：
         *
         * - `Uri.encode(slug)` / `URLEncoder.encode(slug)` 会把 `/` 编成 `%2F`，
         *   多数服务端在匹配路由前先解码，反而还能work，但**不保证**；
         * - 更关键的是 `URLEncoder` 是 *form* 编码，空格会变成 `+`，
         *   在 path 段里 `+` 就是字面的加号，不是空格。
         *
         * 这里只对非 ASCII 做 percent-encoding，斜杠原样保留，与实测可用的
         * `survival/%E8%A7%82%E7%82%B9%E7%AF%87/1-%E8%AE%A4%E8%AF%86` 一致。
         */
        fun encodeSlug(slug: String): String {
            val out = StringBuilder(slug.length + 16)
            slug.toByteArray(Charsets.UTF_8).forEach { b ->
                val c = b.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '_' || c == '.' || c == '~' || c == '/' || c == ':'
                ) {
                    out.append(c)
                } else {
                    out.append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
            return out.toString()
        }
    }
}

/** 文档接口的非 2xx 响应。 */
class DocsApiException(val status: Int, val path: String) :
    IOException("GET $path 返回 HTTP $status")

/** `GET /api/site/docs/survival` 的响应。 */
@Serializable
private data class SurvivalResponse(val parts: List<SurvivalPartDto> = emptyList())

@Serializable
private data class SurvivalPartDto(
    val key: String = "",
    val label: String = "",
    val groups: List<SurvivalGroupDto> = emptyList(),
)

@Serializable
private data class SurvivalGroupDto(
    val key: String = "",
    val items: List<DocEntryDto> = emptyList(),
)

/** 手册 / 经验条目，字段完全一致，只靠外层的 kind 区分。 */
@Serializable
private data class DocEntryDto(
    val slug: String = "",
    val kind: String? = null,
    val title: String = "",
    val description: String? = null,
    val author: String? = null,
    val occurredAt: String? = null,
    val category: String? = null,
    val grade: String? = null,
    val college: String? = null,
    val part: String? = null,
    val position: Int? = null,
)

@Serializable
private data class ExperienceResponse(
    val filters: List<DocFilterDto> = emptyList(),
    val items: List<DocEntryDto> = emptyList(),
)

@Serializable
private data class DocFilterDto(
    val key: String = "",
    val label: String = "",
    val values: List<String> = emptyList(),
)

/**
 * `GET /api/site/docs/{slug}` 的响应。
 *
 * 目录字段名是 **`headings`**（`[{id, text, depth}]`），不是 outline；
 * 层级字段是 **`depth`**，不是 level。实测 `1 认识` 的目录为
 * `[{"id":"11-大学的定义","text":"1.1 大学的定义","depth":2}, …]`。
 */
@Serializable
private data class DocDetailResponse(
    val slug: String = "",
    val kind: String? = null,
    val title: String = "",
    val description: String? = null,
    val author: String? = null,
    val occurredAt: String? = null,
    val category: String? = null,
    val grade: String? = null,
    val college: String? = null,
    val part: String? = null,
    val position: Int? = null,
    val html: String? = null,
    val headings: List<DocHeadingDto> = emptyList(),
    val sourceUrl: String? = null,
    val prev: DocSiblingDto? = null,
    val next: DocSiblingDto? = null,
)

@Serializable
private data class DocHeadingDto(
    val id: String = "",
    val text: String = "",
    val depth: Int? = null,
)

@Serializable
private data class DocSiblingDto(
    val slug: String = "",
    val title: String = "",
)

// MARK: - DTO 映射

private fun SurvivalPartDto.toDocPart() = DocPart(
    key = key,
    label = label,
    groups = groups.map { g ->
        DocGroup(
            key = g.key,
            items = g.items.map { it.toDocEntry(DocKind.Survival) },
        )
    },
)

private fun DocEntryDto.toDocEntry(kind: DocKind) = DocEntry(
    slug = slug,
    kind = DocKind.fromKey(kind.key) ?: kind,
    title = title,
    description = description?.takeIf { it.isNotBlank() },
    author = author?.takeIf { it.isNotBlank() },
    occurredAt = occurredAt.toEpochMillisOrNull(),
    category = category?.takeIf { it.isNotBlank() },
    grade = grade?.takeIf { it.isNotBlank() },
    college = college?.takeIf { it.isNotBlank() },
    part = part?.takeIf { it.isNotBlank() },
    position = position,
)

private fun DocDetailResponse.toDocEntry(kind: DocKind) = DocEntry(
    slug = slug,
    kind = DocKind.fromKey(kind.key) ?: kind,
    title = title,
    description = description?.takeIf { it.isNotBlank() },
    author = author?.takeIf { it.isNotBlank() },
    occurredAt = occurredAt.toEpochMillisOrNull(),
    category = category?.takeIf { it.isNotBlank() },
    grade = grade?.takeIf { it.isNotBlank() },
    college = college?.takeIf { it.isNotBlank() },
    part = part?.takeIf { it.isNotBlank() },
    position = position,
)

/**
 * 宽松解析时间字段，统一走 [parseIso8601]。
 *
 * 手册条目的 `occurredAt` 大量是**只有日期**的 `YYYY-MM-DD`，交给
 * `Instant.parse` / `OffsetDateTime.parse` 会直接抛 `DateTimeParseException`；
 * [parseIso8601] 已补上 `LocalDate` 分支（见那里的说明）。
 */
private fun String?.toEpochMillisOrNull(): Long? = parseIso8601(this?.trim())
