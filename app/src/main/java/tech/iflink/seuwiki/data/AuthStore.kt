package tech.iflink.seuwiki.data

import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.net.Uri
import android.os.Build
import android.util.Log
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
 * - 要 refresh token 就必须补 `prompt=consent`，否则 Logto 按 OIDC Core §6 丢弃
 *   `offline_access`，详见 [offlineAccessGranted]；
 * - scope 为 `openid profile email roles offline_access`。其中 `roles` **当前只是先要着**，
 *   代码里没有解析也没有任何按角色分支的逻辑；真要做角色化功能时记得连 UserInfo 解析与
 *   持久化一起补，详见 `AuthConfig.scopes`；
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
    private val appContext: Context,
    private val config: AuthConfig,
) {

    private val prefs = appContext.getSharedPreferences("seu_wiki_auth", Context.MODE_PRIVATE)
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
     * 是否已经向 Logto 取得过「离线访问」授权（即拿到过 refresh token）。
     *
     * OIDC Core §6 规定：请求里带 `offline_access` 时 `prompt` 必须同时带 `consent`，
     * 否则授权服务器**必须忽略** `offline_access`。Logto 严格执行这条 —— 早期只发了
     * `offline_access` 而漏了 `prompt=consent`，token 响应里就**没有 `refresh_token`**，
     * 而 `email` / `roles` 一切正常，极难察觉。后果是 access token 一小时后过期，
     * [accessToken] 拿不到 refresh token 只能 [logout]，表现为「用着用着被静默登出」。
     *
     * 首次登录补上 `prompt=consent`，Logto 弹一次授权页并把 `offline_access` 记进
     * 该用户对本应用的 grant；之后按 OIDC Core 的「其他已满足条件」直接复用该 grant，
     * 不必每次登录都弹授权页。故此标志只置位、登出时也保留，
     * 仅在续期被拒（grant 可能已在服务端失效）时清掉，让下一次登录重新征求同意。
     *
     * 这条不只是为了「能续期」：Logto 文档写明，若授权请求里没有 `offline_access`，
     * 签发出的 refresh token 会被绑定到 user session（固定 14 天 TTL），
     * session 一过期 token 就作废，控制台的 refresh token TTL 设置形同虚设。
     */
    private var offlineAccessGranted: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_GRANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_GRANTED, value).apply()

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
        val redirectUri = resolveRedirectUri()
        val url = buildString {
            append(config.authorizationEndpoint)
            append("?client_id=").append(enc(config.clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&scope=").append(enc(config.scopes.joinToString(" ")))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            // 只在还没拿到过离线授权时补 prompt=consent，见 [offlineAccessGranted]。
            if (!offlineAccessGranted) append("&prompt=consent")
            // resource 让 Logto 签发面向本 API 的 JWT；opaque token 也能用，故可空。
            config.resource?.let { append("&resource=").append(enc(it)) }
        }
        Log.i(
            "AuthStore",
            "本次登录 redirect_uri=$redirectUri，App Link 已校验=${isAppLinkVerified()}",
        )
        return AuthorizationRequest(url = url, verifier = verifier, state = state, redirectUri = redirectUri)
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
            if (!isCallbackUri(uri)) return false
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
                // 没有待处理的 PKCE 材料，但已经有登录态 —— 这是**旧回调被重放**：
                // 浏览器历史里那条 tech.iflink.seuwiki://callback?code=... 被再次点开，
                // 或 Custom Tab 被系统重放。code 是一次性的、verifier 早随首次登录清掉，
                // 重放必然换不到 token，所以这里不换、也不当失败。
                //
                // 关键是要**静默**返回成功：用户此刻是已登录的，对着一个已登录的人弹
                // 「登录会话已失效」纯属误导，而且没有重试的必要 —— 再点一次登录才是。
                if (session != null) return true
                lastError = "登录会话已失效，请重试"
                return false
            }
            if (uri.getQueryParameter("state") != pending.state) {
                lastError = "state 校验失败，已中止登录"
                return false
            }
            clearPending()

            val token = exchangeCode(code, pending.verifier, pending.redirectUri)
            // 拿到 refresh token 即说明 Logto 认可了 offline_access，之后不必再弹授权页。
            if (token.refreshToken != null) offlineAccessGranted = true
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
                // Logto 文档：public client（Native/SPA）开启 Rotate refresh token 后，
                // 每次用 refresh token 换 access token 一定会签发新的 refresh token，
                // 所以这里 `?: refresh` 只是兜底，不是常态。
                refreshToken = token.refreshToken ?: refresh,
                expiresAt = System.currentTimeMillis() + token.expiresInMillis,
            )
            persist(renewed)
            session = renewed
            renewed.accessToken
        } catch (_: Exception) {
            logout()
            // grant 可能已在服务端失效（超过 14 天 TTL、密码重置、管理员清理授权）：
            // 连同「已授权」标志一起清掉，下一次登录会重新弹授权页并取回 refresh token。
            offlineAccessGranted = false
            null
        }
    }

    /** 清掉最近一次错误提示（UI 展示过之后调用）。 */
    fun dismissError() {
        lastError = null
    }

    /**
     * 本地登出：清 token 与资料。
     *
     * 保留 [KEY_OFFLINE_GRANTED]：consent 是「用户对本应用的一次性许可」，
     * 登出不代表收回它 —— 否则每次重新登录都要再看一遍授权页。
     */
    fun logout() {
        clearSession()
        session = null
        lastError = null
    }

    // MARK: - Private

    /**
     * 授权请求 + 需要跨进程保留的 PKCE 材料。
     *
     * [redirectUri] 必须一并带出去：OIDC 要求换 token 时的 `redirect_uri` 与授权
     * 请求里的**完全一致**，而这个值是每次登录现场定的（见 [resolveRedirectUri]），
     * 不存下来就还原不了。
     */
    data class AuthorizationRequest(
        val url: String,
        val verifier: String,
        val state: String,
        val redirectUri: String,
    )

    private data class PendingRequest(
        val verifier: String,
        val state: String,
        val redirectUri: String,
    )

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInMillis: Long,
    ) {
        companion object {
            fun from(obj: JsonObject): TokenResponse {
                val access = obj["access_token"]?.jsonPrimitive?.content
                    ?: error("token 响应缺少 access_token")
                // 同样要排除 JSON null：否则会存下字符串 "null" 当 refresh token。
                val refresh = obj["refresh_token"]?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive?.contentOrNull
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                // 只记字段名和「有没有 refresh_token」，绝不打印任何 token 值。
                Log.i(
                    "AuthStore",
                    "token 响应字段=${obj.keys}，含 refresh_token=${refresh != null}",
                )
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

    private suspend fun exchangeCode(
        code: String,
        verifier: String,
        redirectUri: String,
    ): TokenResponse {
        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
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
            subject = obj.stringOrNull("sub") ?: "",
            // username 是 Logto 的实际 claim 名（iOS 侧写的是 preferred_username，取不到）
            name = obj.stringOrNull("name")
                ?: obj.stringOrNull("username")
                ?: obj.stringOrNull("preferred_username") ?: "",
            email = obj.stringOrNull("email") ?: "",
            avatarUrl = obj.stringOrNull("picture"),
        )
    }

    /**
     * 读一个字符串 claim，JSON null 视为「没有」。
     *
     * 不能直接写 `jsonPrimitive.content`：JSON 字面量 null 经它会得到**字符串 "null"**
     * （四个字符），于是上游的 `?:` 和 `ifEmpty` 两道兜底全部失效 —— 界面上就会把
     * 字面的 "null" 当昵称显示出来。必须先排除 [JsonNull]。
     */
    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun resourceParam(): Map<String, String> =
        config.resource?.let { mapOf("resource" to it) } ?: emptyMap()

    /** 清空会话与在途 PKCE 材料，但保留 [KEY_OFFLINE_GRANTED]。 */
    private fun clearSession() = prefs.edit()
        .remove(KEY_ACCESS)
        .remove(KEY_REFRESH)
        .remove(KEY_EXPIRES)
        .remove(KEY_SUB)
        .remove(KEY_NAME)
        .remove(KEY_EMAIL)
        .remove(KEY_AVATAR)
        .remove(KEY_VERIFIER)
        .remove(KEY_STATE)
        .remove(KEY_REDIRECT_URI)
        .apply()

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
        .putString(KEY_REDIRECT_URI, p.redirectUri)
        .apply()

    private fun readPending(): PendingRequest? {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        val redirectUri = prefs.getString(KEY_REDIRECT_URI, null) ?: return null
        return PendingRequest(verifier, state, redirectUri)
    }

    private fun clearPending() = prefs.edit()
        .remove(KEY_VERIFIER)
        .remove(KEY_STATE)
        .remove(KEY_REDIRECT_URI)
        .apply()

    /** 保存当前这次请求的 PKCE 材料，**必须在打开浏览器之前**调用。 */
    fun rememberPending(request: AuthorizationRequest) =
        savePending(PendingRequest(request.verifier, request.state, request.redirectUri))

    // MARK: - 回调地址：App Link 优先，custom scheme 兜底

    /**
     * 系统现在会不会把 `https://seu.wiki/callback` 直接交给本 App。
     *
     * 直接问系统「seu.wiki 这个域，链接处理权限是不是 VERIFIED」。
     *
     * 不用 `resolveActivity` 那种「这条链接归谁」的间接问法：未验证时系统对该链接
     * 返回的候选里**本 App 也在其中**（intent-filter 匹配得上），而 `resolveActivity`
     * 只有一个返回值，很容易在用户把浏览器设成默认时误判成「已生效」——
     * 那样就会拿 https 去授权，结果浏览器正常打开网页，**登录直接断掉**。
     * 只认 VERIFIED 这个唯一确定的信号，判错方向才是安全的那一侧。
     *
     * 实践上国产 ROM 基本拿不到 VERIFIED：域验证由 GMS 编排且要能连外网，
     * 国内 ROM 的 GMS 多为停用或不可达（实测 Redmi 上 GMS enabled=0、
     * 到 digitalassetlinks.googleapis.com 100% 丢包，状态恒为 1024）。
     * 所以这些设备会一直走 custom scheme，保留原来那个确认框 —— 这是系统限制，
     * 不是配置问题，服务器侧的 assetlinks.json 本身是对的（Google 官方
     * statements:list 接口能正确列出本应用的两条声明）。
     */
    private fun isAppLinkVerified(): Boolean {
        // DomainVerificationManager 是 API 31 才有；更早的版本系统对 https 链接
        // 一律弹「用哪个应用打开」，不如 custom scheme。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val manager =
                appContext.getSystemService(DomainVerificationManager::class.java) ?: return false
            // isLinkHandlingAllowed() 正是「state == DOMAIN_STATE_VERIFIED」的语义化写法。
            // 平台签名把它标成了可空，用 ?: 收口成保守的 false。
            manager.getDomainVerificationUserState(AuthConfig.APP_LINK_HOST)
                ?.isLinkHandlingAllowed == true
        } catch (e: Exception) {
            false
        }
    }

    /** 本次登录该用哪个回调地址，见 [AuthConfig.APP_LINK_REDIRECT_URI]。 */
    private fun resolveRedirectUri(): String =
        if (isAppLinkVerified()) AuthConfig.APP_LINK_REDIRECT_URI else config.redirectUri

    /**
     * 两种回调都认：App Link 发过来的是 `https://seu.wiki/callback?code=...`，
     * custom scheme 发过来的是 `tech.iflink.seuwiki://callback?code=...`。
     * query 参数完全一致，所以解析逻辑不用分叉。
     */
    private fun isCallbackUri(uri: Uri): Boolean {
        if (uri.scheme.equals(config.callbackScheme, ignoreCase = true)) return true
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(AuthConfig.APP_LINK_HOST, ignoreCase = true)
    }

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

        /** 本次授权用的 redirect_uri，换 token 时必须原样回传（OIDC 要求一致）。 */
        private const val KEY_REDIRECT_URI = "redirect_uri"

        /** 是否已取得 offline_access 授权（拿到过 refresh token），见 [offlineAccessGranted]。 */
        private const val KEY_OFFLINE_GRANTED = "offline_access_granted"
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
