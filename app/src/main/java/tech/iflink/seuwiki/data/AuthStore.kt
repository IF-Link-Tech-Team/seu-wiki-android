package tech.iflink.seuwiki.data

import android.content.Context
import android.content.SharedPreferences
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val prefs: SharedPreferences = SecurePrefs.open(appContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpJson()

    /**
     * 续期互斥锁：保证同一时刻只有一次 refresh 在途。
     *
     * Logto 对 public client 开了 **Rotate refresh token** —— 每次用旧 refresh token
     * 换 access token 都会作废旧的、签发一个新的。于是两个并发续期（资讯列表正在
     * loadMore、搜索页又在请求）里，**后到的那个必然拿到 400 invalid_grant**，
     * 按 S-1 的规则那就是该登出 —— 用户只是在正常刷列表却被登出了。
     * 合并并发续期后，这个窗口根本不会出现。
     */
    private val refreshMutex = Mutex()

    /**
     * 会话代号。每次真正建立或清空会话（登录成功 / 登出）自增。
     *
     * 续期是**异步**的：用户在续期在途时点了退出，`logout()` 清空会话并把代号 +1；
     * 稍后那次续期才返回，若不管它，它会拿着自己那份**过期的** [Session] 把 token
     * `persist` 回去并赋值给 [session]，用户明明点了退出却又被「复活」。
     * 写回前比对代号，不一致就整份丢弃。
     */
    private val sessionGeneration = AtomicLong(0)

    /**
     * **应用级**协程域：OIDC 回调的换 token 挂在它上面。
     *
     * 之前换 token 跑在 `AuthCallbackActivity` 的 `lifecycleScope` 里，而那个
     * Activity 又是 `noHistory` 的。用户切出去看一眼短信验证码再切回来，
     * Activity 已被销毁 → 协程被取消 → 但 code 是**一次性**的、PKCE 材料也已在
     * 开始处理时就清掉了，用户回到 App 只看到「登录会话已失效」。
     *
     * AuthStore 本身是应用级单例，它的生命周期比任何 Activity 都长，
     * 所以换 token 的过程挂在这里最合适：Activity 随便被销毁都不影响它跑完。
     * `SupervisorJob` 保证一次回调失败不会连累后续回调。
     */
    val callbackScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
     * 是否需要在下一次授权时**强制重新登录**（登出后置位，登录成功即清）。
     *
     * 对应 S-7：只清本地会话的话，浏览器里的 Logto SSO cookie 还在，有效期长于
     * refresh token 的 14 天。用户退出后重新点登录，浏览器发现会话仍然有效，
     * **静默地**直接签发新 code 回到原账号 —— 于是「换个账号登录」根本做不到，
     * 界面上表现为退出登录后一登录又变回自己。
     */
    private val forceReauth: Boolean
        get() = prefs.getBoolean(KEY_FORCE_REAUTH, false)

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
        // prompt 的取值：
        // - 恒定带 consent —— 没有它 Logto 按 OIDC Core §6 会忽略 offline_access、
        //   不签发 refresh token，access token 一小时后过期就没法续了；
        // - 登出后再登录额外带 login —— 让浏览器不要拿还在的 SSO 会话静默签发 code。
        // 多个 prompt 值按空格分隔，符合 OIDC Core §3.1.2.1。
        // first-party 应用带上 consent 不会重复弹授权页。
        val prompts = buildList {
            add("consent")
            if (forceReauth) add("login")
        }
        val url = buildString {
            append(config.authorizationEndpoint)
            append("?client_id=").append(enc(config.clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&scope=").append(enc(config.scopes.joinToString(" ")))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            append("&prompt=").append(prompts.joinToString(" "))
            // resource 让 Logto 签发面向本 API 的 JWT；opaque token 也能用，故可空。
            config.resource?.let { append("&resource=").append(enc(it)) }
        }
        Log.i(
            "AuthStore",
            "本次登录 redirect_uri=$redirectUri，prompt=$prompts，App Link 已校验=${isAppLinkVerified()}",
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
            // 登录成功：换代（让在途续期作废）并撤销「必须重新登录」的标记。
            sessionGeneration.incrementAndGet()
            persist(newSession)
            prefs.edit().putBoolean(KEY_FORCE_REAUTH, false).apply()
            session = newSession
            if (token.refreshToken == null) {
                // 不该发生（授权恒定带 prompt=consent）。真发生说明服务端没签发，
                // access token 一小时后过期就无法续期，提前告知比一小时后静默登出好。
                Log.w("AuthStore", "登录成功但未拿到 refresh token，续期能力缺失")
            }
            return true
        } catch (e: CancellationException) {
            throw e
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
     * **只有** token 端点明确回复「这个 refresh token 不能再用了」时才清会话：
     * HTTP 400 且 body 里是 `invalid_grant`，或 HTTP 401。其余一切失败
     * （超时、断网、5xx、JSON 解析失败、被上层取消）都**保留**登录态，
     * 本次请求按匿名发出。
     *
     * 这条边界很重要：之前这里是 `catch (_: Exception) { logout() }`，把
     * `CancellationException` 也一起吞了。而搜索框每敲一个字 `LaunchedEffect(query)`
     * 就会取消上一个在途请求 —— 用户正常打字就能被静默登出。取消更是**不能**登出的：
     * 它只说明「没人再要这个结果了」，和会话是否还有效毫无关系。
     *
     * 并发续期由 [refreshMutex] 合并；返回前还会比对 [sessionGeneration]，
     * 避免续期在途时用户登出、结果又把会话写回来。
     */
    suspend fun accessToken(): String? {
        val current = session ?: return null
        if (current.expiresAt - System.currentTimeMillis() > EXPIRY_SKEW_MS) {
            return current.accessToken
        }
        // 没有 refresh token 就永远续不了期，属于真实的死路，只能登出。
        // 正常情况下不该出现：授权请求恒定带 prompt=consent（见 [buildAuthorizationRequest]），
        // Logto 一定会签发 refresh_token。真出现了说明服务端策略变了，登出让用户重来一次。
        val refresh = current.refreshToken ?: run {
            Log.w("AuthStore", "会话没有 refresh token，无法续期，改为登出")
            logout()
            return null
        }

        return refreshMutex.withLock {
            // 双重检查：等锁期间可能已经有别的协程续好了，也可能用户已经登出。
            val latest = session
            if (latest == null) return@withLock null
            if (latest.expiresAt - System.currentTimeMillis() > EXPIRY_SKEW_MS) {
                return@withLock latest.accessToken
            }
            val generationAtStart = sessionGeneration.get()

            try {
                val form: Map<String, String> = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to (latest.refreshToken ?: refresh),
                    "client_id" to config.clientId,
                ) + resourceParam()
                val body = http.postForm(config.tokenEndpoint, form)
                val token = TokenResponse.from(json.parseToJsonElement(body).jsonObject)
                val renewed = latest.copy(
                    accessToken = token.accessToken,
                    // Logto 文档：public client（Native/SPA）开启 Rotate refresh token 后，
                    // 每次用 refresh token 换 access token 一定会签发新的 refresh token，
                    // 所以这里 `?: refresh` 只是兜底，不是常态。
                    refreshToken = token.refreshToken ?: latest.refreshToken ?: refresh,
                    expiresAt = System.currentTimeMillis() + token.expiresInMillis,
                )
                // 用户可能在续期在途时登出过：代号变了就别写回，否则会话被复活。
                if (sessionGeneration.get() != generationAtStart) {
                    Log.i("AuthStore", "续期返回时会话已变更（疑似已登出），丢弃本次结果")
                    return@withLock null
                }
                persist(renewed)
                session = renewed
                renewed.accessToken
            } catch (e: CancellationException) {
                // 取消不是「会话失效」，绝不能顺手登出。
                throw e
            } catch (e: HttpStatusException) {
                if (e.invalidatesGrant) {
                    Log.w("AuthStore", "refresh token 已被服务端拒绝（HTTP ${e.status}），清会话")
                    logout()
                } else {
                    // 5xx / 429 这类服务端故障，refresh token 大概率还是好的。
                    Log.w("AuthStore", "续期失败但 grant 可能仍有效（HTTP ${e.status}），保留登录态")
                }
                null
            } catch (e: Exception) {
                // 断网、超时、解析失败：保留登录态，本次匿名请求。
                Log.w("AuthStore", "续期遇到非致命错误（${e::class.java.simpleName}），保留登录态")
                null
            }
        }
    }

    /** 清掉最近一次错误提示（UI 展示过之后调用）。 */
    fun dismissError() {
        lastError = null
    }

    /**
     * 退出登录：**本地立刻清干净**，并异步吊销服务端的 refresh token。
     *
     * 只清本地是不够的。refresh token 在 Logto 侧还有最长 14 天有效期，
     * 浏览器里的 SSO cookie 也还在 —— 用户重新点登录时浏览器发现会话仍有效，
     * 会**静默地**直接签发新 code 返回原账号，结果是「换个账号」根本做不到。
     * 所以这里按 RFC 7009 吊销 refresh token（吊销会连带结束该 SSO 会话），
     * 并置位 [forceReauth]，让下一次授权额外带 `prompt=login`。
     *
     * 吊销是**尽力而为**：它失败不影响本地已经清干净的登录态，用户照样登出成功。
     * 放在后台发出去、不阻塞 UI。
     */
    fun logout() {
        val refresh = session?.refreshToken
        clearSession()
        session = null
        lastError = null
        // 代号 +1：让此刻仍在途的续期在写回前发现「会话已变」并丢弃结果。
        sessionGeneration.incrementAndGet()
        prefs.edit().putBoolean(KEY_FORCE_REAUTH, true).apply()

        if (!refresh.isNullOrBlank()) {
            callbackScope.launch(Dispatchers.IO) {
                runCatching {
                    http.postForm(
                        config.revocationEndpoint,
                        mapOf(
                            "token" to refresh,
                            "token_type_hint" to "refresh_token",
                            "client_id" to config.clientId,
                        ),
                    )
                }.onFailure {
                    // 吊销失败只是让「下次静默回原账号」的风险回来一点，
                    // 不该因此把登出搞失败，也不必打扰用户。
                    Log.w("AuthStore", "吊销 refresh token 失败：${it::class.java.simpleName}")
                }
            }
        }
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

        /** 登出后置位：下次授权必须带 prompt=login，避免浏览器用还在的 SSO 会话静默登录。 */
        private const val KEY_FORCE_REAUTH = "force_reauth"
    }
}

/**
 * token 端点回了非 2xx。
 *
 * 单独一个类型，是为了让 [AuthStore.accessToken] 能区分「这个 refresh token 确实废了」
 * 和「服务器/network 抖了一下」—— 前者该登出，后者只该把本次请求降级成匿名。
 */
class HttpStatusException(
    val status: Int,
    val body: String,
) : Exception("HTTP $status${body.take(120).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}") {

    /**
     * 这个响应是否意味着 refresh token 已被授权服务器作废。
     *
     * Logto 对作废的 refresh token 回 **400 + `{"error":"invalid_grant"}`**；
     * token 端点整体不可用或凭证被彻底拒绝时回 401。两者都说明本地这份会话
     * 确实没法再用了。其余（429 限流、5xx 故障、网关错误）refresh token 仍是好的，
     * 登出只会白白把用户踢下线。
     */
    val invalidatesGrant: Boolean
        get() = status == 401 || (status == 400 && body.contains("invalid_grant"))
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
            val status = responseCode
            val stream = if (status in 200..299) inputStream else errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (status !in 200..299) throw HttpStatusException(status, text)
            text
        } finally {
            disconnect()
        }
    }
}
