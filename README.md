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

- `ui/RootView.kt` 是导航宿主。**子页面隐藏 tab 栏**，按**栈顶** destination 判断
  （不是「当前属于哪个 tab」—— 详情页上那个判断仍为真，正是 tab 栏从前常驻的成因）。
- `design/SeuIcons.kt` 是 SF Symbol → Material 图标的映射表，**两端共用同一份 SF 名**。

## 两条别踩的线

### 1. 生产路径不许有 Mock

资讯、手册、经验长文、统一搜索全部接 `seu-wiki-v2` 线上接口。后端没返回的字段
（`campus` / `audience` / `deadline`）就**不编造默认值**，对应筛选项显式置灰。
未接通的功能显式标注「即将推出」或直接隐藏。

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

## 自检

`app/src/test/java/tech/iflink/seuwiki/SelfCheckTest.kt`，39 条断言，**每一条都真的打生产代码**。

它抓到过几个代码审查看不出来的 bug：日期解析（后端混用带时刻与只有日期两种格式）、
分页合并去重、HTML 实体与段落粘连、slug 的 `%2F` 编码（不编码直接 404）、
对比度（品牌亮绿上压白字只有 1.83:1）。

有一条断言值得单独说：它扫全量源码，把所有传给 `SeuIcons.of(...)` 的字面量抠出来
跟映射表比对。`SeuIcons.of` 找不到名字会**静默返回 `Icons.Outlined.Apps`（九宫格）** ——
一个看着正常、实则语义完全不相干的图形，代码照样编译、构建照样全绿。
这条是模拟器截图里看到「收藏按钮渲染成九宫格」之后补的。

新增这类修复时请一并补断言。
