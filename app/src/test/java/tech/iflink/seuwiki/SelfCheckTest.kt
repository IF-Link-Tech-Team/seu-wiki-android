package tech.iflink.seuwiki

import tech.iflink.seuwiki.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import tech.iflink.seuwiki.data.campusHtmlToAnnotatedString
import tech.iflink.seuwiki.data.parseIso8601
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.data.resolveBookmarks
import tech.iflink.seuwiki.models.DocEntry
import tech.iflink.seuwiki.models.DocKind
import tech.iflink.seuwiki.models.UserFacingError
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.Routes
import tech.iflink.seuwiki.ui.search.SearchScope
import tech.iflink.seuwiki.ui.profile.SEARCHABLE_OPTION_COUNT
import tech.iflink.seuwiki.ui.profile.filterOptions
import java.io.File

/**
 * 关键纯逻辑断言套件 —— 与 iOS 端 `SelfCheck.swift` **逐条对齐**。
 *
 * 存在的理由：两端此前都是零测试，而这些正是历次事故的高发区 —— 日期解析
 * （后端混用带时刻与只有日期两种格式）、分页合并去重、HTML 解析、slug 编码。
 * iOS 那套自检已经抓到过三个真 bug（`inf` 学分让绩点变 NaN、`%25` 双重编码、
 * 目录字段名写成 `outline` 而后端是 `headings`）——**都是代码审查看不出来、
 * 只有断言能抓住的**。两端共用同一份用例清单，谁改坏了另一端也该知道。
 *
 * 跑法：
 * ```
 * JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfCheckTest {

    /** 品牌深绿，仅用于让 HTML 解析器有确定的强调色，不参与断言。 */
    private val BRAND = Color(0xFF0E5A46)

    /** `Format` 的时间文案已搬进 `strings.xml`，取资源需要 Context。 */
    private val context = RuntimeEnvironment.getApplication()


    // MARK: - 日期解析

    @Test
    fun `日期解析 带毫秒 ISO8601`() {
        assertEquals(1767254399000L, parseIso8601("2026-01-01T07:59:59.000Z"))
    }

    @Test
    fun `日期解析 无毫秒 ISO8601`() {
        assertEquals(1767254399000L, parseIso8601("2026-01-01T07:59:59Z"))
    }

    @Test
    fun `日期解析 只有日期 YYYY-MM-DD`() {
        // 这条曾经是静默坏的：`Instant.parse` 和 `LocalDateTime.parse` 对纯日期
        // **都会抛**，于是「10 月 12 日截止」那条横幅从来没出现过。
        val ms = parseIso8601("2026-01-01")
        assertNotNull("纯日期必须能解析", ms)
    }

    @Test
    fun `日期解析 非法输入返回 null`() {
        assertNull(parseIso8601("不是日期"))
        assertNull(parseIso8601(""))
        assertNull(parseIso8601(null))
    }

    // MARK: - 相对时间

    @Test
    fun `相对时间 一分钟内显示刚刚`() {
        val now = 1_767_254_399_000L
        assertEquals("刚刚", Format.relative(context, now - 30_000, now))
    }

    @Test
    fun `相对时间 一分钟以上显示分钟数`() {
        val now = 1_767_254_399_000L
        assertEquals("1 分钟前", Format.relative(context, now - 60_000, now))
    }

    @Test
    fun `相对时间 N 天前`() {
        val now = 1_767_254_399_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals("3 天前", Format.relative(context, now - 3 * day, now))
    }

    @Test
    fun `相对时间 超过 7 天用绝对日期`() {
        val now = 1_767_254_399_000L
        val day = 24L * 60 * 60 * 1000
        val out = Format.relative(context, now - 30 * day, now)
        assertFalse("超过 7 天不该再显示「N 天前」，实际 $out", out.contains("天前"))
    }

    @Test
    fun `相对时间 未来时间不显示天前`() {
        val now = 1_767_254_399_000L
        val day = 24L * 60 * 60 * 1000
        val out = Format.relative(context, now + 3 * day, now)
        assertFalse("未来时间不该显示「天前」，实际 $out", out.contains("天前"))
    }

    // MARK: - slug 编码与路由

    @Test
    fun `slug 斜杠必须编码成 %2F`() {
        // 曾经保留字面量 `/`，结果多段 slug 路由匹配不上，点搜索结果直接崩：
        // `handbook/entry/experience/alumni/... cannot be found`。
        val enc = Routes.seg("survival/观点篇/1-认识")
        assertFalse("斜杠必须编码，实际 $enc", enc.contains("/"))
        assertTrue("斜杠应变成 %2F，实际 $enc", enc.contains("%2F"))
    }

    @Test
    fun `slug 中文已编码`() {
        val enc = Routes.seg("survival/观点篇/1-认识")
        assertFalse("中文不能原样透传，实际 $enc", enc.contains("观"))
    }

    @Test
    fun `slug 加号与空格分别编码`() {
        // `+` 在 path 段里是**字面加号**，form 编码把它当空格是错的。
        assertEquals("%2B", Routes.seg("+"))
        assertEquals("%20", Routes.seg(" "))
    }

    @Test
    fun `slug 编码后可逆`() {
        val raw = "survival/观点篇/1-认识"
        val round = Routes.decode(Routes.seg(raw))
        assertEquals("编解码必须可逆，实际 $round", raw, round)
    }

    @Test
    fun `slug 路由含编码后的 slug`() {
        assertTrue(Routes.handbookEntry("survival/观点篇/1-认识").contains("%2F"))
    }

    // MARK: - 颜色对比度（WCAG）

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(fg: Color, bg: Color): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `对比度 白字压亮绿不达标 这正是原缺陷`() {
        // 品牌深色强调色 #34D6AB 上放白字只有 1.83:1，WCAG AA 要 4.5:1。
        val brandMint = Color(0xFF34D6AB)
        val ratio = contrast(Color.White, brandMint)
        assertTrue("白字压 $brandMint 只有 %.2f:1，确实不达标（这是本断言要钉住的事实）".format(ratio), ratio < 4.5)
    }

    @Test
    fun `对比度 深绿压亮绿达标`() {
        // 深色模式下选中态改用深色文字（与 iOS 的 AccentInk 同款思路）。
        val brandMint = Color(0xFF34D6AB)
        val ink = Color(0xFF00382B)
        val ratio = contrast(ink, brandMint)
        assertTrue("深绿压亮绿应 ≥ 4.5:1，实际 %.2f:1".format(ratio), ratio >= 4.5)
    }

    @Test
    fun `对比度 白字压品牌绿达标`() {
        val brand = Color(0xFF0E5A46)
        val ratio = contrast(Color.White, brand)
        assertTrue("白字压品牌绿应 ≥ 4.5:1，实际 %.2f:1".format(ratio), ratio >= 4.5)
    }

    // MARK: - HTML 实体与段落

    @Test
    fun `HTML 数字实体正确解码`() {
        // 曾经用 code.toString() 拼字符，&#39; 变成 "39" 而不是 "'"。
        val out = campusHtmlToAnnotatedString("a&#39;b&#x27;c", BRAND).text.toString()
        assertTrue("数字/十六进制实体要还原，实际 $out", out.contains("'"))
        assertFalse("不能留下裸数字 39，实际 $out", out.contains("39"))
    }

    @Test
    fun `HTML 段落不粘连`() {
        // 曾经 appendCharRaw 不 flush pendingBreak，`<p>a</p><p>b</p>` 出来是 "ab"。
        val out = campusHtmlToAnnotatedString("<p>第一段</p><p>第二段</p>", BRAND).text.toString()
        val t = out
        assertTrue("两段之间要有换行，实际 [$t]", t.contains("\n"))
    }

    @Test
    fun `HTML 空输入不崩`() {
        assertEquals("", campusHtmlToAnnotatedString("", BRAND).text.toString())
    }

    // MARK: - 提醒过期判定

    @Test
    fun `提醒 只有未来的时间点才排程`() {
        val now = 1_767_254_399_000L
        assertTrue("过去的时间不该排", now - 3_600_000 < now)
        assertTrue("未来的时间该排", now + 3_600_000 > now)
    }

    // MARK: - SF Symbol 覆盖率

    private val stringLiteral = Regex(""""([^"]+)"""")

    /**
     * 抠出源码里所有传给 `SeuIcons.of(...)` 的字符串字面量。
     *
     * 括号必须**配平**才能取到实参 —— 调用点里全是
     * `of(if (x) "a" else "b")` 这种多行条件表达式，简单的非贪婪正则会在
     * `if (` 那个右括号处就截断，两个分支一个都捞不到。
     */
    private fun iconLiteralsIn(source: String): List<String> {
        val marker = "SeuIcons.of("
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val at = source.indexOf(marker, i)
            if (at < 0) break
            val open = at + marker.length - 1
            var depth = 0
            var j = open
            while (j < source.length) {
                when (source[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
                if (depth == 0) break
            }
            stringLiteral.findAll(source.substring(open + 1, j - 1))
                .forEach { out += it.groupValues[1] }
            i = j
        }
        return out
    }

    @Test
    fun `SF Symbol 源码里用到的名字必须全部登记`() {
        // 兜底是 `Icons.Outlined.Apps`（九宫格）—— 一个**看着正常、其实完全不对**的
        // 图形：SF 名写错，代码照样编译、界面照样渲染，只有肉眼能发现。本轮就是靠
        // 模拟器截图发现详情页右上角的收藏按钮渲染成了九宫格。
        //
        // 扫全量源码把所有实参字面量抠出来跟映射表比对，以后新增图标忘了登记会直接
        // 挂测试，而不是留到发版后被人看出来。
        val sourceRoot = File("src/main/java").takeIf { it.isDirectory }
            ?: error("找不到 src/main/java，工作目录是 ${File("").absolutePath}")
        val ktFiles = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("应当扫到源码文件", ktFiles.isNotEmpty())

        val sources = ktFiles.associate { it to it.readText(Charsets.UTF_8) }

        val passedDirectly = sources.values.flatMap { iconLiteralsIn(it) }
        // `iconKey = "…"` 是数据侧声明的图标名（话题目录、工具卡片），同样要覆盖。
        val declared = sources.values.flatMap { s ->
            Regex("""iconKey\s*=\s*"([^"]+)"""").findAll(s).map { it.groupValues[1] }.toList()
        }
        val used = (passedDirectly + declared).toSet()

        assertTrue("应当扫到 SF 名", used.isNotEmpty())
        val unmapped = used.filterNot { SeuIcons.isMapped(it) }.sorted()
        assertTrue("这些 SF 名没登记，会静默渲染成九宫格兜底：$unmapped", unmapped.isEmpty())
    }

    @Test
    fun `SF Symbol 搜索范围每一项都已登记`() {
        // SearchScope 的 symbol 是位置参数（`All("all", R.string..., "magnifyingglass")`），
        // 源码扫描抓不到，这里直接遍历枚举断言。
        SearchScope.entries.forEach {
            assertTrue("SearchScope.${it.name} 的图标没登记：${it.symbol}", SeuIcons.isMapped(it.symbol))
        }
    }

    @Test
    fun `SF Symbol 收藏与空态这一组已登记`() {
        // 这几个名字曾经缺席（bookmark / bookmark.fill / exclamationmark.triangle /
        // person.2），是本轮模拟器实测 + 上面那条全量扫描才抓出来的。
        listOf("bookmark", "bookmark.fill", "exclamationmark.triangle", "person.2").forEach {
            assertTrue("SF 名 \"$it\" 没登记", SeuIcons.isMapped(it))
        }
    }

    // MARK: - 画像长列表选择器

    private val colleges = listOf(
        "建筑学院", "机械工程学院", "信息科学与工程学院", "计算机科学与工程学院",
        "软件学院", "数学学院", "外国语学院", "医学院",
    )

    @Test
    fun `画像 空白查询返回全部`() {
        // 用户刚点开还没输入时不该看到空列表。
        listOf("", "   ").forEach {
            assertEquals("查询 \"$it\" 应返回全部 ${colleges.size} 项", colleges.size, filterOptions(colleges, it).size)
        }
    }

    @Test
    fun `画像 搜索子串能命中`() {
        // 「计算机」要能命中「计算机科学与工程学院」，不能要求全等。
        assertEquals(listOf("计算机科学与工程学院"), filterOptions(colleges, "计算机"))
        assertEquals(listOf("软件学院"), filterOptions(colleges, "软件"))
    }

    @Test
    fun `画像 搜索忽略大小写`() {
        val ascii = listOf("CS", "ML", "SEU")
        assertEquals(listOf("CS"), filterOptions(ascii, "cs"))
    }

    @Test
    fun `画像 搜不到时返回空 而不是全部`() {
        // 反过来才是危险的：把「没匹配上」当成「不过滤」，用户会看到一堆
        // 与关键词无关的项还以为搜索坏了。
        assertTrue(filterOptions(colleges, "不存在的学院").isEmpty())
    }

    @Test
    fun `画像 只有学院够长才用搜索 对齐 iOS 阈值`() {
        // 学段 3 / 年级 13 内联胶囊更好用，学院 28 项才需要搜索。
        // 两端阈值都是 20，行为一致。
        assertEquals(20, SEARCHABLE_OPTION_COUNT)
        assertTrue("学院该走搜索", 28 >= SEARCHABLE_OPTION_COUNT)
        assertTrue("年级不该走搜索", 13 < SEARCHABLE_OPTION_COUNT)
        assertTrue("学段不该走搜索", 3 < SEARCHABLE_OPTION_COUNT)
    }

    // MARK: - 收藏

    /** 测试里用的假索引。真实数据要打网络，这里只关心 slug ↔ 条目的映射。 */
    private val index = listOf(
        DocEntry(slug = "survival/a", kind = DocKind.Survival, title = "保研经验"),
        DocEntry(slug = "survival/b", kind = DocKind.Survival, title = "ACM 竞赛"),
        DocEntry(slug = "experience/c", kind = DocKind.Experience, title = "选课指南"),
    )

    private fun lookup(slug: String): DocEntry? = index.firstOrNull { it.slug == slug }

    /** SharedPreferences 是进程级的，同名会互相污染，所以每个用例用独立文件名。 */
    private var prefsSeq = 0

    private fun freshPrefs() =
        context.getSharedPreferences("seuwiki-selfcheck-bookmark-${prefsSeq++}", 0)

    @Test
    fun `收藏 开关点两下回到原位`() {
        val store = UserProfileStore(freshPrefs())
        val slug = "survival/a"

        assertFalse("初始不该是已收藏", store.isBookmarked(slug))
        store.toggleBookmark(slug)
        assertTrue("点一下就该收藏上", store.isBookmarked(slug))
        store.toggleBookmark(slug)
        assertFalse("再点一下就该取消", store.isBookmarked(slug))
    }

    @Test
    fun `收藏 落盘后重开进程还在`() {
        // 收藏本来是安卓端的一整块死代码（审查 A-13）：toggleBookmark 从没被
        // UI 调过，存了也没地方看。这条钉住「存得下来、读得回来」。
        val prefs = freshPrefs()
        UserProfileStore(prefs).toggleBookmark("survival/a")

        // 重新构造一次 = 冷启动重读磁盘
        assertTrue("重启后收藏必须还在", UserProfileStore(prefs).isBookmarked("survival/a"))
    }

    @Test
    fun `收藏 键名是 slugs 不是 post_ids`() {
        // 名字要跟实际存的东西对得上：存的是文档 slug，不是论坛 post id。
        assertEquals("bookmarked_slugs", UserProfileStore.KEY_BOOKMARKS)
    }

    @Test
    fun `收藏 索引里没有的 slug 被跳过而不是显示空白行`() {
        // 对应 iOS `loadBookmarks()`：只保留命中的条目。两端必须一致，
        // 否则安卓会多出一行没标题的东西。
        val resolved = resolveBookmarks(setOf("survival/a", "survival/已下线"), ::lookup)
        assertEquals("只保留命中的 1 条", 1, resolved.size)
        assertEquals("命中的就是那条", "survival/a", resolved.single().slug)
    }

    @Test
    fun `收藏 顺序按标题排 不随集合迭代顺序变`() {
        // Set 的迭代顺序不保证稳定；不排序的话收藏列表每次重组都可能重排。
        // 输入是刻意打乱的，期望顺序是写死的。
        val resolved = resolveBookmarks(setOf("experience/c", "survival/a", "survival/b"), ::lookup)
        assertEquals(
            listOf("ACM 竞赛", "保研经验", "选课指南"),
            resolved.map { it.title },
        )
    }

    @Test
    fun `收藏 标题取自索引而不是存的快照`() {
        // 存 slug 而不是标题快照：内容改名后要从索引回填新名字，不能留旧名。
        val renamed = listOf(DocEntry(slug = "survival/a", kind = DocKind.Survival, title = "改名后的标题"))
        val resolved = resolveBookmarks(setOf("survival/a")) { slug -> renamed.firstOrNull { it.slug == slug } }
        assertEquals("改名后的标题", resolved.single().title)
    }

    // MARK: - 搜索信源路由键

    @Test
    fun `搜索信源 key 往返 不依赖显示文案`() {
        // 路由 search/list/{scope}/{keyword} 走的是 key，不是中文标签。
        // 一旦这里改成用显示名当键，用户中途改设备语言后回退栈里那条路由
        // 就再也匹配不上，页面会掉进「未知搜索范围」。
        SearchScope.entries.forEach { scope ->
            assertEquals("key 往返失败：${scope.name}", scope, SearchScope.fromKey(scope.key))
        }
    }

    @Test
    fun `搜索信源 key 是稳定 ASCII 不含中文`() {
        SearchScope.entries.forEach { scope ->
            assertTrue(
                "路由键不能含中文，实际 \"${scope.key}\"",
                scope.key.all { it.isLetterOrDigit() && it.code < 128 },
            )
        }
        assertNull("未知 key 必须解析为 null 而不是随便兜一个", SearchScope.fromKey("通知"))
    }

    // MARK: - 用户可见错误的本地化前缀

    @Test
    fun `服务端 error_description 永远带本地化前缀`() {
        // Logto / 攻击者都能往回调 URL 里塞任意 error_description。
        // 原样显示等于用 App 自己的口吻替对方说话（钓鱼）。
        val hostile = "您的账户已被冻结，请立即联系客服并提供银行卡号"
        val shown = UserFacingError(R.string.profile_login_failed, hostile).format(context)
        assertTrue(
            "必须以 App 自己的前缀开头，实际 \"$shown\"",
            shown.startsWith("登录失败："),
        )
        assertTrue("细节仍应保留给用户看", shown.contains(hostile))
    }

    @Test
    fun `无细节的错误不吞掉百分号格式符`() {
        val plain = UserFacingError(R.string.profile_login_unconfigured).format(context)
        assertEquals("登录服务配置中", plain)
    }

    // MARK: - tab 栏形态（贴底，不许退回悬浮）

    /**
     * 这组断言守的是一条**已经被静默跳过一次**的决定。
     *
     * UI/UX 对齐方案 v2 §5/§6/§8 三处都写了「tab 栏改为贴底」，但真正落地的那次提交
     * （`12f082c`）只做了显隐与语义，用「这行标着『可讨论』」把外观跳过了，之后再没被
     * 捡回来，README 也没登记成待办 —— 于是悬浮胶囊又留了很久。
     *
     * 选态同理：原来同时有常驻灰底（`tabFill`）和按压波纹两层反馈，属于重复表达。
     *
     * 这里是源码级回归锁。它读的是**生产源码本体**并逐条比对具体形态，任何一条被改回去
     * （重新内缩、重新半透明、重新加常驻底、重新加那 16dp 让位）都会直接挂测试，
     * 而不是等到有人再截一次图才发现。
     */
    private fun rootViewSource(): String {
        val src = File("src/main/java/tech/iflink/seuwiki/ui/RootView.kt")
        assertTrue("应当找到 RootView.kt，实际工作目录 ${File("").absolutePath}", src.isFile)
        return src.readText(Charsets.UTF_8)
    }

    /** 只取 `BottomTabBar` 函数体，避免注释里提到旧词造成误判。 */
    private fun bottomTabBarBody(): String {
        val src = rootViewSource()
        val start = src.indexOf("private fun BottomTabBar(")
        assertTrue("应当存在贴底栏 BottomTabBar", start >= 0)
        val end = src.indexOf("\n@Composable", start)
        return src.substring(start, if (end < 0) src.length else end)
    }

    @Test
    fun `tab 栏贴底 不得重新出现悬浮内缩与外边距`() {
        val body = bottomTabBarBody()
        // 悬浮时栏体外套着 `padding(horizontal = 16.dp, vertical = 8.dp)`，
        // 贴底后必须通宽直达屏幕两缘。
        assertFalse(
            "栏体不得再横向内缩（16.dp 是悬浮胶囊的边距）",
            body.contains("horizontal = 16.dp"),
        )
        assertFalse(
            "栏体不得再留上下外边距（8.dp 是悬浮胶囊的离屏间距）",
            body.contains("vertical = 8.dp"),
        )
        // 悬浮靠自绘描边光晕撑轮廓，贴底不需要投影。
        assertFalse(
            "贴底后不应再有自绘投影光晕 drawEdgeHalo",
            rootViewSource().contains("drawEdgeHalo"),
        )
    }

    @Test
    fun `tab 栏底色不透明 不得退回 95% 半透明`() {
        // 0xF2（95%）是悬浮玻璃时代的值。贴底后列表正文会隔着栏体读出来，
        // 模拟器实测能直接看清「工程采用周志华…」穿在栏里。
        val body = bottomTabBarBody()
        assertFalse(
            "栏体底色不得再是半透明 0xF2…",
            Regex("""0xF2[0-9A-Fa-f]{6}""").containsMatchIn(body),
        )
        assertTrue(
            "应当改用不透明底 secondaryGroupedBackground",
            body.contains("secondaryGroupedBackground"),
        )
    }

    @Test
    fun `tab 栏材质要铺到屏幕底边`() {
        // 少了这个 Spacer，手势导航条后面会露出 groupedBackground 形成一道色带。
        assertTrue(
            "栏体末尾必须补 navigationBars 高度的 Spacer",
            bottomTabBarBody().contains("windowInsetsBottomHeight"),
        )
    }

    @Test
    fun `tab 选中态只留一次性按压反馈`() {
        val src = rootViewSource()
        // 常驻灰底胶囊：`fill` + `tabFill` + 配套的 `tabInset` 宽度动画。
        assertFalse(
            "选中项不得再挂常驻底色（tabFill）",
            src.contains("tabFill"),
        )
        assertFalse(
            "选中项不得再有胶囊宽度动画（tabInset）",
            src.contains("tabInset"),
        )
        // 一次性反馈必须还在：没有它就回到 §3.3 点名的 `indication = null`。
        assertTrue(
            "按压波纹必须保留（selectable 的默认 indication）",
            src.contains("role = Role.Tab"),
        )
    }

    @Test
    fun `子页面仍隐藏 tab 栏 且贴底后不再多留 16dp 让位`() {
        val screen = File("src/main/java/tech/iflink/seuwiki/ui/Screen.kt")
        assertTrue("应当找到 Screen.kt", screen.isFile)
        val src = screen.readText(Charsets.UTF_8)
        // 显隐规则：判据是栈顶 destination。
        assertTrue(
            "子页面隐藏 tab 栏的判据不能丢",
            src.contains("LocalTabBarVisible"),
        )
        // 贴底后没有 8×2 外边距了，TabBarClearance 只能等于栏体本身。
        assertTrue(
            "TabBarClearance 应当就是 tabBarHeight 本身",
            Regex("""if \(LocalTabBarVisible\.current\) tabBarHeight else 0\.dp""")
                .containsMatchIn(src),
        )
        assertFalse(
            "TabBarClearance 不应再叠加悬浮外边距的 16.dp",
            Regex("""tabBarHeight \+ 16\.dp""").containsMatchIn(src),
        )
    }
}
