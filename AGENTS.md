# AGENTS.md — SEU.wiki Android

Kotlin + Jetpack Compose 原生客户端。本文件记录**必须遵守**的工程规则，改动前先读。

## 账号体系（登录态单一事实源）

2026-10 账号重构后的铁律。历史教训：论坛发帖死循环的根因是 UI 拿
`ForumStore.viewer` 当登录门禁，而 `refreshViewer()` 全工程零调用，viewer 永为
null。以下规则就是为杜绝这一类问题定的。

1. **登录态只有一份**：`AuthStore.session`（Logto 本地会话）。UI 判断「是否已登录」
   一律用 `AuthStore.isLoggedIn`，任何功能模块不得自建第二份登录状态。
2. **门禁只看 `isLoggedIn`**：发帖/点赞/收藏/评论等写操作的入口判断只读
   `isLoggedIn`。**禁止**拿服务端投影（如 `viewer`、`/api/me` 的结果）当门禁——
   它可能根本没拉过、可能是上个账号的残留。viewer 只做展示与自检。
3. **401 是校正信号，不是登录指令**：请求 401 → 对应 state 置 `needsLogin=true`，
   UI 显示登录引导；绝不因为一次 401 直接跳登录页（token 续期由
   `AuthStore.accessToken()` 内部完成）。
4. **token 只有一个来源**：所有需要鉴权的客户端通过构造函数注入
   `tokenProvider = { auth.accessToken() }`（自带续期）。禁止在任何功能模块里
   另起 OIDC 流程或缓存自己的 token。
5. **功能 store 必须实现登录态钩子**：
   - `onSignedIn()`：登录成功（含冷启动时已有会话）后拉取本模块的用户态数据；
   - `onSignedOut()`：清空本模块**全部**用户态数据，防止换账号串数据——包括
     列表/详情里带的 `likedByMe`、`bookmarked` 这类「上个账号视角」的字段。
   - 两个钩子统一在 `RootView` 的 `LaunchedEffect(auth.isLoggedIn)` 里注册调用，
     分散到各页面自己监听是禁止的（首帧执行天然覆盖冷启动已登录）。
   - 与用户数据完全无关的 store（如纯本地的搜索、本地收藏）不实现钩子。
6. **新功能接入账号的三步**：① 客户端注入 `tokenProvider`；② 需要用户态的 store
   实现 `onSignedIn/onSignedOut` 并在 `RootView` 注册；③ UI 门禁用
   `isLoggedIn` 参数传入，不读 store 里的服务端投影。

## 构建与测试

- JDK：`export JAVA_HOME=/opt/homebrew/opt/openjdk@17`（系统默认 Java 版本不对）。
- 构建：`./gradlew assembleDebug`；单测：`./gradlew testDebugUnitTest`。
- `SelfCheckTest`（Robolectric）是合入门禁，与 iOS `SelfCheck.swift` 逐条对齐；
  新增关键逻辑必须补断言，跑红不许提交。
- 版本号走 gradle property：
  `./gradlew assembleRelease -PversionCode=N -PversionName=x.y.z`。
  每次发布 versionCode 必须递增，否则手机不识别为更新。
- release 签名 keystore 在 `~/.seu-wiki-android/`（不进仓库）。

## 代码结构速览

- `data/AuthStore.kt` — Logto 会话、token 续期、登录/登出动作。
- `data/ForumStore.kt` — 论坛全部状态（列表/详情/收藏/关注/手册），实现登录态钩子。
- `data/ForumApiClient.kt` — 论坛后端 HTTP 客户端，token 只靠注入的 tokenProvider。
- `data/Analytics.kt` — Umami 统计（`app.seu.wiki` 站点）：payload 纯函数 + fire-and-forget
  发送器 + 导航层自动屏幕浏览埋点（RootView 的 `currentBackStackEntryFlow`），DEBUG 不上报。
- `ui/RootView.kt` — 路由 + 各 store 装配 + 登录态统一接线（`LaunchedEffect`）。
- `ui/forum/ForumScreens.kt` — 论坛各页面，登录门禁一律吃 `isLoggedIn` 参数。

## 提交与发布

- commit message 风格：`feat(android): …` / `fix(android): …` / `build(android): …`。
- 发布 = 递增版本号 + `assembleRelease` + 打 tag + **GitHub Release（附 APK 与
  mapping）**，两步缺一不可。
