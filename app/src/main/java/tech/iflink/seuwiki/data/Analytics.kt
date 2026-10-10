package tech.iflink.seuwiki.data

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tech.iflink.seuwiki.BuildConfig

/**
 * Umami（自建实例 `https://umami.iflink.tech`）采集契约。
 *
 * 这一层是**纯函数**：给定屏幕路由 / 标题 / 屏幕尺寸，产出与 `POST /api/send`
 * 约定一致的 payload JSON，不碰网络也不碰 Android 上下文，SelfCheckTest 直接断言它。
 * 与 iOS 端的断言共用同一份契约：website id、hostname、`type="event"`、url/title 字段。
 */
object Umami {
    const val WebsiteId = "97ce2a8e-3118-478e-81e3-ea76c9f73f35"
    const val Hostname = "app.seu.wiki"
    const val DefaultEndpoint = "https://umami.iflink.tech/api/send"

    /**
     * 路由 pattern → 规范路径（url to title）。key 就是 `Routes` 里的模板串与五个
     * tab 路由（`NavBackStackEntry.destination.route` 拿到的正是 pattern）。
     *
     * url 一律走这张表：两端（Android / iOS）共用同一份规范路径表，**绝不把
     * `{id}` 占位符或 `?name={name}` 查询串带进 url**。表外的 pattern 退回
     * `/<pattern>` 当 url、pattern 本身当 title，见 [screenUrl] / [screenTitle]。
     */
    internal val Screens = mapOf(
        "home" to ("/home" to "首页"),
        "feed" to ("/feed" to "资讯"),
        "experience" to ("/experience" to "经验"),
        "tools" to ("/tools" to "工具"),
        "search" to ("/search" to "搜索"),
        "profile" to ("/profile" to "个人中心"),
        "home/feed-list" to ("/home/feed-list" to "精选"),
        "home/forum-list" to ("/home/forum-list" to "社区热议"),
        "feed/detail/{id}" to ("/feed/item" to "资讯详情"),
        "forum/detail/{id}" to ("/forum/post" to "帖子详情"),
        "forum/topic/{slug}" to ("/forum/topic" to "话题"),
        "forum/compose" to ("/forum/compose" to "发帖"),
        "forum/edit/{id}" to ("/forum/edit" to "编辑帖子"),
        "forum/bookmarks" to ("/forum/bookmarks" to "我的收藏"),
        "forum/notifications" to ("/forum/notifications" to "论坛通知"),
        "handbook/section/{id}?name={name}" to ("/handbook/section" to "手册板块"),
        "handbook/article/{id}?title={title}" to ("/handbook/article" to "手册文章"),
        "handbook/entry/{slug}" to ("/handbook/doc" to "手册长文"),
        "search/list/{scope}/{keyword}" to ("/search/results" to "搜索结果"),
        "tools/timetable" to ("/tools/timetable" to "课表"),
        "tools/gpa" to ("/tools/gpa" to "绩点计算"),
        "tools/other/{id}" to ("/tools/other" to "工具"),
    )

    /** url 一律来自 [Screens] 规范路径表；表外 pattern 退回 `/<pattern>`。 */
    fun screenUrl(routePattern: String): String =
        Screens[routePattern]?.first ?: "/$routePattern"

    /** 标题来自 [Screens]；表外 pattern 退回 pattern 本身（不兜底成空串）。 */
    fun screenTitle(routePattern: String): String =
        Screens[routePattern]?.second ?: routePattern

    /** 页面浏览事件。`referrer` 是上一个屏幕的 url，首屏传空串。 */
    fun buildScreenViewPayload(
        url: String,
        title: String,
        referrer: String,
        language: String,
        screen: String,
    ): JsonObject = buildJsonObject {
        put("type", "event")
        putJsonObject("payload") {
            put("website", WebsiteId)
            put("hostname", Hostname)
            put("language", language)
            put("screen", screen)
            put("url", url)
            put("title", title)
            put("referrer", referrer)
        }
    }

    /** 自定义事件：在页面浏览字段之外加 `name` 与可选 `data`。 */
    fun buildEventPayload(
        name: String,
        url: String,
        title: String,
        referrer: String,
        language: String,
        screen: String,
        data: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("type", "event")
        putJsonObject("payload") {
            put("website", WebsiteId)
            put("hostname", Hostname)
            put("language", language)
            put("screen", screen)
            put("url", url)
            put("title", title)
            put("referrer", referrer)
            put("name", name)
            if (data != null) put("data", data)
        }
    }
}

/**
 * `POST {endpoint}` 的最小发送器。
 *
 * **fire-and-forget**：离线、超时、非 2xx 一律返回 false，绝不抛异常 ——
 * 统计是旁观者，不许影响业务，也不许重试（重试风暴比丢一条数据更糟）。
 *
 * User-Agent 必填：Umami 服务端靠 IP + UA 生成会话并解析设备/OS，
 * 形状是 `Mozilla/5.0 (Linux; Android <版本>; <机型>) SEUWiki/<versionName>`。
 */
class AnalyticsClient(
    private val endpoint: String = Umami.DefaultEndpoint,
    private val userAgent: String,
) {
    private val json = Json { encodeDefaults = true }

    /** 发一条 payload；成功（2xx）返回 true，任何失败静默吞掉返回 false。 */
    suspend fun post(payload: JsonObject): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .post(json.encodeToString(payload).toRequestBody(JsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        /** 超时要短：这条链路永远不阻塞 UI，慢响应按失败丢弃。 */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 埋点入口（进程级单例）。`RootView` 里 `Analytics.init(context)` 一次，
 * 之后任何位置都能 `trackScreen` / `trackEvent`。
 *
 * - **DEBUG 构建整体不上报**（[BuildConfig.DEBUG]），避免开发污染统计；
 * - 发送走串行内存队列（容量 64，溢出丢最旧），失败即弃，不重试；
 * - 连续两条相同 url 的 screen view 去重（详情页 A→详情页 B 的 pattern 相同，
 *   而 url 就是 pattern，不去重会刷出一串无法区分的重复浏览）。
 */
object Analytics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<JsonObject>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile private var client: AnalyticsClient? = null
    @Volatile private var enabled = false
    @Volatile private var language = "zh-CN"
    @Volatile private var screen = ""
    @Volatile private var lastScreenUrl = ""

    fun init(context: Context) {
        if (client != null) return
        val app = context.applicationContext
        language = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
        val dm = app.resources.displayMetrics
        screen = "${dm.widthPixels}x${dm.heightPixels}"
        client = AnalyticsClient(userAgent = buildUserAgent())
        enabled = !BuildConfig.DEBUG
        scope.launch {
            for (payload in queue) client?.post(payload)
        }
    }

    /** 上报一次屏幕浏览。[routePattern] 是 Navigation 的路由模板（如 `forum/detail/{id}`）。 */
    fun trackScreen(routePattern: String) {
        if (!enabled) return
        val url = Umami.screenUrl(routePattern)
        if (url == lastScreenUrl) return
        val referrer = lastScreenUrl
        lastScreenUrl = url
        enqueue(Umami.buildScreenViewPayload(url, Umami.screenTitle(routePattern), referrer, language, screen))
    }

    /** 上报一次自定义事件，挂在当前屏幕上。 */
    fun trackEvent(name: String, data: JsonObject? = null) {
        if (!enabled) return
        enqueue(Umami.buildEventPayload(name, lastScreenUrl, "", "", language, screen, data))
    }

    private fun enqueue(payload: JsonObject) {
        queue.trySend(payload)
    }

    /**
     * 形如 `Mozilla/5.0 (Linux; Android 15; Pixel 8) SEUWiki/0.3.0`，
     * 让 Umami 能解析出 Android 与机型。
     */
    internal fun buildUserAgent(): String =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "SEUWiki/${BuildConfig.VERSION_NAME}"
}
