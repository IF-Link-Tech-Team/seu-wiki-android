package tech.iflink.seuwiki.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * `body.zh` 是服务端下发的**白名单 HTML**（p / br / strong / em / u / a /
 * ul / ol / li / h1..h6 / blockquote / img 等），不是任意 HTML。
 *
 * iOS 端把它交给 `NSAttributedString` 的 HTML 解析器，再把每个 run 的字体重设为
 * `.body` 动态字体、只保留粗体 trait。Compose 没有等价的 HTML 渲染，所以这里用
 * 一个针对白名单的极小解析器直接产出 [AnnotatedString]，效果对齐：
 *
 * - 块级标签与 `br` 换行
 * - `strong` / `b` → 粗体，`em` / `i` → 斜体，`u` → 下划线
 * - `a` → accent 色 + 下划线 + 可点击链接
 * - `img` → 收进 [CampusHtml.images]，由调用方决定是否用 Coil 渲染
 * - 不在白名单里的标签丢掉标签本身，内容保留
 *
 * 只解码服务端实际会产出的那几个 entity，不引完整解析器。
 */
data class CampusHtml(
    val text: AnnotatedString,
    val images: List<String> = emptyList(),
)

/**
 * 把白名单 HTML 转成可渲染的 [AnnotatedString]。
 *
 * 不会抛异常：畸形标记降级为纯文本，和 SwiftUI 侧 `try? NSAttributedString(...)`
 * 的静默回退一致。[accentColor] 用于链接着色。
 */
fun campusHtmlToAnnotatedString(
    html: String,
    accentColor: Color,
): CampusHtml {
    val parser = WhitelistHtmlParser()
    parser.parse(html)
    return parser.build(accentColor)
}

private class WhitelistHtmlParser {

    /** 一段同样式、同链接的连续文本。 */
    private class Segment {
        val text = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var href: String? = null
    }

    private val segments = mutableListOf<Segment>()
    private val images = mutableListOf<String>()

    private var bold = false
    private var italic = false
    private var underline = false
    private var href: String? = null

    private var pendingBreak = false
    private var listDepth = 0
    private var atLineStart = true

    fun parse(html: String) {
        var i = 0
        while (i < html.length) {
            if (html[i] != '<') {
                i += appendChar(html, i)
            } else {
                val close = html.indexOf('>', i)
                if (close < 0) {
                    // 标签没闭合，当普通文本。
                    i += appendChar(html, i)
                } else {
                    handleTag(html.substring(i + 1, close).trim())
                    i = close + 1
                }
            }
        }
    }

    private fun handleTag(tag: String) {
        if (tag.startsWith("!") || tag.startsWith("?")) return          // 注释 / doctype
        val closing = tag.startsWith("/")
        val body = if (closing) tag.drop(1) else tag
        val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()

        when (name) {
            "br" -> breakLine(force = true)

            "p", "div", "section", "article", "blockquote", "tr",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "dd", "dt",
            -> if (closing) breakLine(force = false)

            "ul", "ol" -> {
                if (closing) {
                    listDepth = (listDepth - 1).coerceAtLeast(0)
                    breakLine(force = false)
                } else {
                    listDepth++
                }
            }

            "strong", "b" -> bold = !closing
            "em", "i" -> italic = !closing
            "u" -> underline = !closing

            "a" -> href = if (closing) null else hrefOf(body)

            "img" -> if (!closing) srcOf(body)?.let(images::add)

            // 不在白名单的标签：静默忽略标签本身，内容照常。
            else -> Unit
        }
    }

    /**
     * `br` 只换行；块级标签闭合则分段。
     *
     * 段落分隔用两个换行，这样正文里 `<p>` 之间有可见的段间距，而不是像 iOS
     * 那样靠 WebKit 的默认 block margin —— Compose 没有等价物，只能用空行模拟。
     */
    private fun breakLine(force: Boolean) {
        flushPendingBreak()
        if (force) {
            appendRaw("\n")
            atLineStart = true
        } else if (segments.isNotEmpty()) {
            pendingBreak = true
        }
    }

    private fun flushPendingBreak() {
        if (pendingBreak) {
            appendRaw("\n\n")
            atLineStart = true
            pendingBreak = false
        }
    }

    /** 追加 html[i] 处的一个字符（entity 视作一个整体），返回消耗的字符数。 */
    private fun appendChar(html: String, i: Int): Int {
        if (html[i] != '&') {
            appendCharRaw(html[i])
            return 1
        }
        val semi = html.indexOf(';', i)
        if (semi < 0 || semi - i > 8) {
            appendCharRaw('&')
            return 1
        }
        val decoded = when (html.substring(i + 1, semi)) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "#39", "apos" -> "'"
            "nbsp" -> " "
            else -> null
        }
        val length = semi - i + 1
        if (decoded != null) {
            decoded.forEach { appendCharRaw(it) }
        } else {
            html.substring(i, semi + 1).forEach { appendCharRaw(it) }
        }
        return length
    }

    private fun appendCharRaw(ch: Char) {
        if (ch == '\n') {
            appendRaw("\n")
            atLineStart = true
            return
        }
        if (ch != ' ' && ch != '\t') {
            if (atLineStart && listDepth > 0) {
                // 列表项补一个项目符号，视觉上接近系统列表。
                appendRaw("• ")
            }
            atLineStart = false
        }
        currentSegment().text.append(ch)
    }

    /** 不带样式的裸文本（换行、项目符号）。 */
    private fun appendRaw(value: String) {
        currentSegment().text.append(value)
    }

    private fun currentSegment(): Segment {
        val last = segments.lastOrNull()
        if (last != null && last.bold == bold && last.italic == italic &&
            last.underline == underline && last.href == href
        ) {
            return last
        }
        return Segment().also {
            it.bold = bold
            it.italic = italic
            it.underline = underline
            it.href = href
            segments += it
        }
    }

    private fun hrefOf(body: String): String? = attrOf(body, "href")

    private fun srcOf(body: String): String? = attrOf(body, "src")

    /** 取出 `name="value"` 或 `name='value'`，未找到返回 null。 */
    private fun attrOf(body: String, name: String): String? {
        val pattern = Regex("(?i)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        val m = pattern.find(body) ?: return null
        return m.groupValues.getOrNull(2)
            ?: m.groupValues.getOrNull(3)
            ?: m.groupValues.getOrNull(4)
    }

    fun build(accentColor: Color): CampusHtml {
        flushPendingBreak()
        bold = false
        italic = false
        underline = false
        href = null

        val annotated = buildAnnotatedString {
            segments.forEach { segment ->
                val raw = segment.text.toString()
                if (raw.isEmpty()) return@forEach
                val link = segment.href
                val style = SpanStyle(
                    color = if (link != null) accentColor else Color.Unspecified,
                    fontWeight = if (segment.bold) FontWeight.Bold else null,
                    fontStyle = if (segment.italic) FontStyle.Italic else null,
                    textDecoration = when {
                        segment.underline || link != null -> TextDecoration.Underline
                        else -> null
                    },
                )
                if (link != null) {
                    withStyle(style) {
                        pushLink(LinkAnnotation.Url(url = link, styles = TextLinkStyles(style)))
                        append(raw)
                        pop()
                    }
                } else {
                    withStyle(style) { append(raw) }
                }
            }
        }
        return CampusHtml(annotated, images)
    }
}
