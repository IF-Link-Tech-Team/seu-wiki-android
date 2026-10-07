package tech.iflink.seuwiki.data

import kotlinx.serialization.Serializable
import tech.iflink.seuwiki.models.DocEntry
import tech.iflink.seuwiki.models.DocKind
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 文档条目详情接口的客户端。
 *
 * 与 [FeedApiClient] 同样只用 `HttpURLConnection` + 已有的 kotlinx-serialization，
 * 不引入网络库；所有方法阻塞 I/O，调用方需在 IO 调度器上执行（见 [DocsStore]）。
 *
 * **这个接口不带 Authorization**。`api/site` 这一组是公开只读的 GET，后端不校验
 * 鉴权。挂了 token 只会把「能不能读到内容」和「登录态是否健康」耦合起来。
 *
 * 曾经的 `survival()`（手册文档树）与 `experience()`（经验长文）已随信源切换移除：
 * 手册改走论坛后端的 `/api/handbook/` 系列接口（见 [ForumApiClient]），经验长文整体下线。
 * 这里只剩条目详情——收藏与深链仍会按 slug 打开它。
 */
class DocsApiClient(
    private val baseUrl: String = "https://seu.wiki",
) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
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
