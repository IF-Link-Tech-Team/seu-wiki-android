package tech.iflink.seuwiki.data

/**
 * Logto OIDC 单点配置：所有端点、客户端标识集中在此，改环境只动这一处。
 *
 * 逐项对应 iOS 的 `AuthConfig`（`Services/Auth/AuthConfig.swift`），两端读同一组值，
 * 换环境时不会只改一边。后端契约见 `seu-wiki-forum/docs/auth.md` §2
 * 「Bearer access tokens (native app)」。
 */
data class AuthConfig(
    /** Logto issuer。端点路径固定如下，不依赖运行时 discovery。 */
    val issuer: String = "https://auth.iflink.tech/oidc",
    /**
     * Logto 控制台注册的 **Native** 应用 ID（public client，无 secret）。
     *
     * 对应控制台里的应用「SEU Wiki Android」（2026-10-05 注册，Native App 类型）。
     * 独立于 IF.Link 社区 App 的「IF.Link App」——redirect URI 与 scope 粒度各自独立，
     * 不共用一张登记列表。
     */
    val clientId: String = "yb6csafyv7tviokwbbu2w",
    /**
     * 回调 URL 的**兜底**形态（custom scheme）。scheme 已在 AndroidManifest 注册。
     *
     * 已不是首选，但删不得 —— 见 [APP_LINK_REDIRECT_URI] 说明的降级路径。
     */
    val redirectUri: String = "$REDIRECT_SCHEME://callback",
    val callbackScheme: String = REDIRECT_SCHEME,
    /**
     * 请求的 scope。
     *
     * - `openid` / `profile` / `email`：UserInfo 里拿到 sub、昵称、邮箱，这些**已经在用**。
     * - `offline_access`：换 refresh token。**必须同时带 `prompt=consent`**，否则 Logto
     *   按 OIDC Core §6 忽略它、不签发 refresh token，详见 `AuthStore.offlineAccessGranted`。
     * - `roles`：**目前只是先要着，代码里一处都没用**。Logto 的 UserInfo 确实会返回
     *   （实测 `["community_super_admin"]`），但两端的 UserInfo 解析都没有这个字段、
     *   Session 里也不存，全工程没有任何按角色分支的逻辑 —— 早期那句「否则社区角色映射
     *   静默降级」是在描述一个不存在的功能，已删除。
     *
     * 保留在 scope 里是因为它不影响 token 形态、也不带来任何副作用，等到真要做社区/论坛的
     * 角色化功能（比如按版主身份显示管理入口）时就不用重新走一遍授权。**但那时要动的不只是
     * 这里**：得把 `roles` 加进 `AuthStore` 的 UserInfo 解析、`Session` 字段与持久化，
     * 否则请求了也是白请求。
     */
    val scopes: List<String> = listOf("openid", "profile", "email", "roles", "offline_access"),
    /**
     * API resource：**留空**（null），Logto 签发 opaque access token，后端走 UserInfo 校验。
     *
     * 与 IF.Link App 的生产实况一致（见 `原生app/android/.../core/config/AuthConfig.kt`，
     * 该配置根本没有 resource 字段），也是本应用在 Logto 控制台的默认形态 ——
     * Native 应用页没有 API resource 字段可填。
     */
    val resource: String? = null,
) {
    val authorizationEndpoint: String get() = "$issuer/auth"
    val tokenEndpoint: String get() = "$issuer/token"
    val userinfoEndpoint: String get() = "$issuer/me"
    val endSessionEndpoint: String get() = "$issuer/session/end"

    /** clientId 仍为占位值时为 false：UI 据此禁用登录按钮并提示「登录服务配置中」。 */
    val isConfigured: Boolean
        get() = clientId.isNotEmpty() && clientId != "YOUR_LOGTO_NATIVE_APP_ID"

    companion object {
        /** 与 iOS `tech.iflink.seuwiki` 保持一致，两端回调 scheme 相同。 */
        const val REDIRECT_SCHEME = "tech.iflink.seuwiki"

        /**
         * App Link（已验证 https 链接）回调，供 Android 消除系统确认框。
         *
         * custom scheme 的固有问题：系统无法预知哪个 App 会响应
         * `tech.iflink.seuwiki://`，于是每次回调都要弹「callback 想要打开外部应用
         * SEU.wiki」的确认框。换成**已验证的 https 域名**后，系统在安装时就确认过
         * `https://seu.wiki/callback` 属于本 App，回调直接进 App，一个框都不弹。
         *
         * 前置条件三件套，缺一不可：
         * 1. `https://seu.wiki/.well-known/assetlinks.json` 可访问，且 JSON 里的
         *    签名指纹与本 APK 的签名证书一致（已挂在 iflink-prod 的 Caddy 上）；
         * 2. AndroidManifest 里对应的 intent-filter 带 `android:autoVerify="true"`；
         * 3. Logto 应用「SEU Wiki Android」把这条 URI 也加进 redirect URI 列表。
         *
         * **为什么还要留着 custom scheme**：Android 12+ 只有在 App Link 校验通过时
         * 才会静默打开；校验没过（或 Android 11 及以下，系统根本不强制校验）时，
         * https 链接会退回成「在浏览器打开」—— 登录就断了。所以 [AuthStore] 每次
         * 登录前查一次系统域校验状态，用 https 还是 custom scheme 现场决定，
         * 保证任何设备上都能登录完。
         *
         * iOS 端不需要这套：`ASWebAuthenticationSession` 走 custom scheme 是系统
         * 原生支持的路径，本来就没有确认框，Universal Links 对登录场景无收益。
         */
        const val APP_LINK_HOST = "seu.wiki"
        const val APP_LINK_REDIRECT_URI = "https://$APP_LINK_HOST/callback"
    }
}
