package tech.iflink.seuwiki.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * IF.Link 统一登录态：OIDC 授权码 + **PKCE** 流程，对应 iOS 的 `AuthStore`。
 *
 * 与 iOS 现状的差别要说清楚：iOS 侧目前还是本地 stub（`auth.login(username, password)`
 * 只切状态、伪造资料，不发网络请求），本实现直接接真实的 Logto。配置项与端点
 * 完全对齐 iOS 的 `AuthConfig`，后端契约见 `seu-wiki-forum/docs/auth.md` §2：
 *
 * - 授权码 + PKCE（S256），public client 无 secret；
 * - 必须请求 `openid profile email roles`，否则 UserInfo 缺声明、角色映射静默降级；
 * - 之后的后端请求一律带 `Authorization: Bearer <access_token>`，该头是权威凭证，
 *   token 无效只会得到匿名/401，绝不回退 Cookie 会话。
 *
 * [clientId] 未配置时（仍是占位符）[isConfigured] 为 false，UI 降级提示，不发请求。
 *
 * 依赖说明：PKCE 的 SHA-256/Base64、随机数、token 交换都走 JDK 自带能力
 * （`MessageDigest` / `SecureRandom` / `HttpURLConnection`），与 `FeedApiClient`
 * 一样**不引入新的网络库**；Custom Tabs 用已在依赖里的 `androidx.browser`。
 */
class AuthStore private constructor(
    context: Context,
    private val config: AuthConfig,
) {

    private val prefs = context.getSharedPreferences("seu_wiki_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpJson()

    /** 登录态。`null` 表示从未登录或已登出。 */
    data class Session(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
        val subject: String,
        val displayName: String,
        val email: String,
        val avatarUrl: String?,
    )

    var session by mutableStateOf(readPersisted())
        private set

    /** 正在与 Logto 交互（打开浏览器 / 换 token），UI 据此显示 loading。 */
    var isBusy by mutableStateOf(false)
        private set

    /** 最近一次失败的提示，`null` 表示没有错误。UI 应如实展示，不要吞掉。 */
    var lastError by mutableStateOf<String?>(null)
        private set

    val isLoggedIn: Boolean get() = session != null
    val isConfigured: Boolean get() = config.isConfigured

    /** 头像占位用的首字，对齐 iOS `AuthStore.initials`。 */
    val initials: String
        get() = session?.displayName?.trim()?.firstOrNull()?.uppercase() ?: ""

    // MARK: - Public API

    /**
     * 拼出授权 URL 并返回需要保留的 PKCE verifier / state。
     *
     * 拆成「拼 URL」与「处理回调」两步，是为了让打开浏览器这一步留在 UI 层
     * （需要 Context + Custom Tabs），其余逻辑保持可测。
     */
    fun buildAuthorizationRequest(): AuthorizationRequest? {
        if (!config.isConfigured) {
            lastError = "登录服务配置中"
            return null
        }
        val verifier = randomUrlSafe(32)
        val challenge = base64Url(sha256(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(16)
        val url = buildString {
            append(config.authorizationEndpoint)
            append("?client_id=").append(enc(config.clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(config.redirectUri))
            append("&scope=").append(enc(config.scopes.joinToString(" ")))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            // resource 让 Logto 签发面向本 API 的 JWT；opaque token 也能用，故可空。
            config.resource?.let { append("&resource=").append(enc(it)) }
        }
        return AuthorizationRequest(url = url, verifier = verifier, state = state)
    }

    /**
     * 处理 Custom Tabs 回调。
     *
     * 校验 `state` 防 CSRF，再拿 code 换 token、拉 UserInfo 填资料。
     * 任何一步失败都只写 [lastError]，不抛异常 —— 失败后 UI 仍停在「未登录」。
     */
    suspend fun handleCallback(uri: Uri): Boolean {
        isBusy = true
        lastError = null
        try {
            if (uri.scheme != config.callbackScheme) return false
            val error = uri.getQueryParameter("error")
            if (error != null) {
                lastError = uri.getQueryParameter("error_description") ?: "登录失败：$error"
                return false
            }
            val code = uri.getQueryParameter("code")
            if (code.isNullOrEmpty()) {
                lastError = "登录回调缺少 code"
                return false
            }
            val pending = readPending()
            if (pending == null) {
                lastError = "登录会话已失效，请重试"
                return false
            }
            if (uri.getQueryParameter("state") != pending.state) {
                lastError = "state 校验失败，已中止登录"
                return false
            }
            clearPending()

            val token = exchangeCode(code, pending.verifier)
            val userInfo = fetchUserInfo(token.accessToken)
            val newSession = Session(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAt = System.currentTimeMillis() + token.expiresInMillis,
                subject = userInfo.subject,
                displayName = userInfo.name.ifEmpty {
                    userInfo.email.substringBefore('@').ifEmpty { "IF.Link 用户" }
                },
                email = userInfo.email,
                avatarUrl = userInfo.avatarUrl,
            )
            persist(newSession)
            session = newSession
            return true
        } catch (e: Exception) {
            lastError = "登录失败：${e.message ?: e::class.java.simpleName}"
            return false
        } finally {
            isBusy = false
        }
    }

    /**
     * 取一个可用的 access token，必要时用 refresh token 换新的。
     *
     * [FeedApiClient] 每次请求前调它，登录态下就自动带上新鲜凭证；
     * 刷新失败说明会话已失效，清本地登录态并返回 null（回到匿名请求）。
     */
    suspend fun accessToken(): String? {
        val current = session ?: return null
        if (current.expiresAt - System.currentTimeMillis() > EXPIRY_SKEW_MS) {
            return current.accessToken
        }
        val refresh = current.refreshToken ?: run {
            logout()
            return null
        }
        return try {
            val form = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to config.clientId,
            ) + resourceParam()
            val body = http.postForm(config.tokenEndpoint, form)
            val token = TokenResponse.from(json.parseToJsonElement(body).jsonObject)
            val renewed = current.copy(
                accessToken = token.accessToken,
                // Logto 可能在刷新时轮换 refresh token，缺省则沿用旧的。
                refreshToken = token.refreshToken ?: refresh,
                expiresAt = System.currentTimeMillis() + token.expiresInMillis,
            )
            persist(renewed)
            session = renewed
            renewed.accessToken
        } catch (_: Exception) {
            logout()
            null
        }
    }

    /** 清掉最近一次错误提示（UI 展示过之后调用）。 */
    fun dismissError() {
        lastError = null
    }

    /** 本地登出：清 token 与资料。 */
    fun logout() {
        prefs.edit().clear().apply()
        session = null
        lastError = null
    }

    // MARK: - Private

    /** 授权请求 + 需要跨进程保留的 PKCE 材料。 */
    data class AuthorizationRequest(val url: String, val verifier: String, val state: String)

    private data class PendingRequest(val verifier: String, val state: String)

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInMillis: Long,
    ) {
        companion object {
            fun from(obj: JsonObject): TokenResponse {
                val access = obj["access_token"]?.jsonPrimitive?.content
                    ?: error("token 响应缺少 access_token")
                val refresh = obj["refresh_token"]?.jsonPrimitive?.content
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                return TokenResponse(access, refresh, expiresIn * 1000L)
            }
        }
    }

    private data class UserInfo(
        val subject: String,
        val name: String,
        val email: String,
        val avatarUrl: String?,
    )

    private suspend fun exchangeCode(code: String, verifier: String): TokenResponse {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "client_id" to config.clientId,
            "code_verifier" to verifier,
        ) + resourceParam()
        val body = http.postForm(config.tokenEndpoint, form)
        return TokenResponse.from(json.parseToJsonElement(body).jsonObject)
    }

    private suspend fun fetchUserInfo(accessToken: String): UserInfo = withContext(Dispatchers.IO) {
        val body = http.get(config.userinfoEndpoint, mapOf("Authorization" to "Bearer $accessToken"))
        val obj = json.parseToJsonElement(body).jsonObject
        UserInfo(
            subject = obj["sub"]?.jsonPrimitive?.content ?: "",
            name = obj["name"]?.jsonPrimitive?.content
                ?: obj["preferred_username"]?.jsonPrimitive?.content ?: "",
            email = obj["email"]?.jsonPrimitive?.content ?: "",
            avatarUrl = obj["picture"]?.jsonPrimitive?.content,
        )
    }

    private fun resourceParam(): Map<String, String> =
        config.resource?.let { mapOf("resource" to it) } ?: emptyMap()

    private fun persist(s: Session) {
        prefs.edit()
            .putString(KEY_ACCESS, s.accessToken)
            .putString(KEY_REFRESH, s.refreshToken)
            .putLong(KEY_EXPIRES, s.expiresAt)
            .putString(KEY_SUB, s.subject)
            .putString(KEY_NAME, s.displayName)
            .putString(KEY_EMAIL, s.email)
            .putString(KEY_AVATAR, s.avatarUrl)
            .apply()
    }

    /**
     * 读回本地会话。
     *
     * 没有任何 token 时返回 null（未登录）；有 token 但已过期时**仍然恢复**，
     * 交给 [accessToken] 用 refresh token 续期 —— 冷启动时不该把还能续的会话丢掉。
     */
    private fun readPersisted(): Session? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            expiresAt = prefs.getLong(KEY_EXPIRES, 0L),
            subject = prefs.getString(KEY_SUB, "").orEmpty(),
            displayName = prefs.getString(KEY_NAME, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            avatarUrl = prefs.getString(KEY_AVATAR, null),
        )
    }

    // PKCE 材料只活在「点登录 → 浏览器回调」这一小段时间里，用完即清。
    private fun savePending(p: PendingRequest) = prefs.edit()
        .putString(KEY_VERIFIER, p.verifier)
        .putString(KEY_STATE, p.state)
        .apply()

    private fun readPending(): PendingRequest? {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        return PendingRequest(verifier, state)
    }

    private fun clearPending() = prefs.edit()
        .remove(KEY_VERIFIER)
        .remove(KEY_STATE)
        .apply()

    /** 保存当前这次请求的 PKCE 材料，**必须在打开浏览器之前**调用。 */
    fun rememberPending(request: AuthorizationRequest) =
        savePending(PendingRequest(request.verifier, request.state))

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /**
         * 应用级单例。
         *
         * OIDC 回调由独立的 `AuthCallbackActivity` 处理，它和 `RootView` 里的 UI 必须
         * 看到**同一个** [session]（一个 `mutableStateOf`），回调写完才能让界面自动重组；
         * 各持一份实例会导致「token 已存进 SharedPreferences 但界面仍显示未登录」。
         */
        @Volatile
        private var instance: AuthStore? = null

        fun get(context: Context): AuthStore = instance ?: synchronized(this) {
            instance ?: AuthStore(context.applicationContext, AuthConfig()).also { instance = it }
        }

        /** 提前这么多毫秒就当作过期，避免请求正好卡在边界上被拒。 */
        private const val EXPIRY_SKEW_MS = 60_000L

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_SUB = "subject"
        private const val KEY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_AVATAR = "avatar_url"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "pkce_state"
    }
}

/**
 * 极简 JSON over HTTP，刻意只支持本文件需要的两种请求。
 *
 * 和 [FeedApiClient] 一样用 `HttpURLConnection` + 已有 kotlinx-serialization，
 * 不引入 OkHttp/Retrofit。
 */
private class HttpJson {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.readBody()
        }

    suspend fun postForm(url: String, form: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val body = form.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.readBody()
        }

    private fun HttpURLConnection.readBody(): String {
        return try {
            val stream = if (responseCode in 200..299) inputStream else errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (responseCode !in 200..299) {
                error("HTTP $responseCode${text.take(120).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
            }
            text
        } finally {
            disconnect()
        }
    }
}
