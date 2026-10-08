# SEU.wiki · Android

东南大学校园资讯与经验社区 App 的 Android 端。**iOS 先行，本端对齐**。

同仓库内的 iOS 端在 `../seu-wiki-app`，两份自检套件（`SelfCheckTest.kt` / `SelfCheck.swift`）
**逐条对齐**，共用同一份用例清单 —— 谁改坏了另一端也该知道。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # 不设会直接失败
./gradlew assembleDebug assembleRelease testDebugUnitTest
```

`JAVA_HOME` 是硬要求：本机默认 JDK 版本高于 AGP 支持范围，不显式指定 17 构建起不来。

自检是 Robolectric 跑的，**不需要真机或模拟器**：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest
```

### 装到模拟器

Debug 与 Release **签名不同**，`install -r` 会静默失败（不报错但没装上）：

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb uninstall tech.iflink.seuwiki          # 先卸
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

测大字号：`adb shell settings put system font_scale 2.0`，**用完务必改回 1.0**。

## 目录

```
app/src/main/java/tech/iflink/seuwiki/
├── MainActivity.kt      # 单 Activity，Compose 入口
├── data/                # API 客户端 + 状态容器（ViewModel）+ 落盘
├── design/              # 设计系统：品牌色、卡片、图标映射、共用组件
├── models/              # 数据模型（对齐真实 API 契约）
└── ui/                  # 页面：home / feed / experience / tools / search / profile / detail
```

- `ui/RootView.kt` 是导航宿主，也是 tab 栏所在（`BottomTabBar`）。
- `design/SeuIcons.kt` 是 SF Symbol → Material 图标的映射表，**两端共用同一份 SF 名**。

## 两条别踩的线

### 1. 生产路径不许有 Mock

资讯走 `seu.wiki` 的 `/api/site/*`；社区（帖子、热榜、关注流、东大生存手册、论坛搜索、
通知、发帖配图上传、编辑/管理删除）全部接论坛后端 **`https://forum.seu.wiki`**，与 App
登录共用同一个 Logto 会话（`Authorization: Bearer`，不存在第二次登录）。后端没返回的字段
（`campus` / `audience` / `deadline`）就**不编造默认值**，对应筛选项显式置灰。
未接通的功能显式标注「即将推出」或直接隐藏。

早期的 `/api/site/docs/survival`（手册文档树）与 `/api/site/docs/experience`
（经验长文）信源已随社区接通移除：手册改走论坛的 `/api/handbook/` 系列接口，
经验长文整体由论坛经验帖取代。`DocsStore` 只剩旧文档条目的详情缓存
（收藏与深链仍会按 slug 打开它们）。

### 2. 平台机制不跨端模仿

iOS 用 SwiftUI 原生控件，本端用 Material 原生控件 —— 同一个意图、两端各自的实现。
**不要**把 iOS 的某个交互搬过来照抄，反之亦然：

| 关注点 | 本端做法 |
|---|---|
| 返回 | 系统返回键 / 手势，不用 iOS 那种居中返回胶囊的视觉照搬 |
| 长列表选择 | Material 对话框；iOS 是 `.searchable` |
| 危险操作 | 系统 `AlertDialog` 二次确认 |
| 触控目标 | 48dp（视觉可以更小，命中区不行） |
| 提醒列表 | 左滑删除（不是行尾 `×`） |

而**视觉**两端统一：品牌色 `#0E5A46` / 深色强调 `#34D6AB`、卡片、console 胶囊、bento、三信源搜索。

#### tab 栏：贴底 + 子页面隐藏（安卓）/ 常驻（iOS）

**这两端形态不一样是机制差异的结果，不是视觉没对齐。** 记这一条是因为很容易被
「两端统一视觉」这句话带偏，把安卓的底栏又改回悬浮。

- **安卓贴底、子页面隐藏**：有系统返回键/手势，「退回一级页面」几乎零成本，
  子页面里切 tab 的需求很弱，所以子页面直接撤掉栏体（`LocalTabBarVisible`），
  底部让给页面自己的操作。栏体既然只在一级页面出现，悬浮遮挡就换不回任何东西 ——
  国产 Android 底栏一律通宽贴底。
- **iOS 常驻**：没有这一层返回链路，详情页里切 tab 是正常动线，Apple 也要求常驻。
  那边用系统 `TabView`，栏体由系统绘制，形态不由我们的代码决定。

改贴底时一并做了三件配套，缺一件就会出空档或脏边：

1. 栏体末尾补 `Spacer(windowInsetsBottomHeight(navigationBars))`，材质一路落到屏幕底边，
   否则手势导航条后面会露出 `groupedBackground` 形成色带。
2. 底色从 95% 半透明改成**不透明**的 `secondaryGroupedBackground` —— 半透明是给
   悬浮玻璃用的，贴底后列表正文会隔着栏体读出来。
3. `TabBarClearance` 去掉原来那 16.dp（悬浮胶囊上下各 8.dp 的外边距），贴底后多留就是死白。

选中态**只有品牌绿加一次性按压波纹**，没有常驻灰底 —— 「按到了」和「我在哪」是两件事，
而「我在哪」品牌绿已经说完了，灰底在通宽底栏上还多出一块长期不消失的灰斑。

## 自检

`app/src/test/java/tech/iflink/seuwiki/SelfCheckTest.kt`，80+ 条用例，**每一条都真的打生产代码**。

它抓到过几个代码审查看不出来的 bug：日期解析（后端混用带时刻、只有日期、6 位微秒
三种格式）、分页合并去重、HTML 实体与段落粘连、slug 的 `%2F` 编码（不编码直接 404）、
对比度（品牌亮绿上压白字只有 1.83:1）。

论坛链路有一组**本地 HTTP 回放**断言：真的起 socket 服务器，真的把 `Authorization`
头写出去，真的解析 JSON —— 钉住 Bearer 免登录链路、hot 的 offset 分页（不许带 cursor）、
浏览计数失败静默、关注 body 白名单、401 识别成未登录、手册与搜索两桶解析。

有一条断言值得单独说：它扫全量源码，把所有传给 `SeuIcons.of(...)` 的字面量抠出来
跟映射表比对。`SeuIcons.of` 找不到名字会**静默返回 `Icons.Outlined.Apps`（九宫格）** ——
一个看着正常、实则语义完全不相干的图形，代码照样编译、构建照样全绿。
这条是模拟器截图里看到「收藏按钮渲染成九宫格」之后补的。

新增这类修复时请一并补断言。

## 参与共建

这是东大学生共建的开源项目（MIT），欢迎提 Issue 和 PR。改动前先读
`AGENTS.md` —— 账号体系铁律、构建要求（JDK 17）、自检对齐规则都在里面。
涉及双端行为的改动，记得同步 iOS 端 `../seu-wiki-app` 的 `SelfCheck.swift` 断言。

## License

MIT，见 `LICENSE`。
