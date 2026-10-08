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
import tech.iflink.seuwiki.data.bookmarkDisplayTitle
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.models.UserFacingError
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.Routes
import tech.iflink.seuwiki.ui.search.SearchScope
import tech.iflink.seuwiki.ui.profile.SEARCHABLE_OPTION_COUNT
import tech.iflink.seuwiki.ui.profile.filterOptions
import java.io.File
import kotlinx.coroutines.runBlocking
import tech.iflink.seuwiki.data.ForumApiClient
import tech.iflink.seuwiki.data.ForumStore
import tech.iflink.seuwiki.models.ForumAuthor
import tech.iflink.seuwiki.models.ForumPost
import tech.iflink.seuwiki.models.ForumSort
import tech.iflink.seuwiki.models.ForumTargetType

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
        // `ForumGlyph(icon = "…")` / `HandbookGlyph("…")` / `StatGlyph(icon = "…")`
        // 这三个包装组件内部才调 SeuIcons.of(icon)，字面量在调用点，直扫扫不到。
        val viaGlyphWrappers = sources.values.flatMap { s ->
            Regex("""(?:ForumGlyph|HandbookGlyph|StatGlyph)\(\s*(?:icon\s*=\s*)?"([^"]+)"""")
                .findAll(s).map { it.groupValues[1] }.toList()
        }
        val used = (passedDirectly + declared + viaGlyphWrappers).toSet()

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
    fun `收藏 兜底标题取 slug 最后一段`() {
        // 手册/经验索引随旧信源移除后，收藏列表不再回填索引标题，
        // 用 slug 的最后一段兜底 —— 它是 slug 里信息量最大的一部分。
        assertEquals("1-认识", bookmarkDisplayTitle("survival/观点篇/1-认识"))
        assertEquals("a", bookmarkDisplayTitle("survival/a"))
        assertEquals("没有斜杠就原样显示", "lonely", bookmarkDisplayTitle("lonely"))
        assertEquals("尾斜杠回退整串", "survival/", bookmarkDisplayTitle("survival/"))
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

    // MARK: - 论坛：免登录（Bearer）链路

    /**
     * 起一个真的本地 HTTP 服务器，捕获请求并回放固定响应。
     *
     * 这不是 mock：请求真的走了一遍 socket，`Authorization` 头真的被 HttpURLConnection
     * 写出去，JSON 真的被 kotlinx.serialization 解析。断言的是**生产代码的行为**，
     * 不是字符串比对。
     */
    private class CapturedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val contentType: String?,
        val body: String?,
    )

    /**
     * 极简 HTTP/1.1 服务器，只够回放固定响应 + 捕获请求。
     *
     * 没用 `com.sun.net.httpserver`：那是 JDK 内部模块（`jdk.httpserver`），
     * Kotlin 解析不到 `com.sun.*`，得改编译参数才能用 —— 为了三条断言去动构建配置
     * 不划算。`ServerSocket` 是 `java.net` 的一部分，零依赖。
     */
    private class LocalApi(private val handler: (CapturedRequest) -> Pair<Int, String>) {
        private val server = java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
        private var thread: Thread? = null

        @Volatile var last: CapturedRequest? = null
            private set

        val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

        fun start(): LocalApi {
            thread = Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { handle(socket) }
                    runCatching { socket.close() }
                }
            }.apply {
                isDaemon = true
                start()
            }
            return this
        }

        private fun handle(socket: java.net.Socket) {
            // 逐字节读到 CRLFCRLF 为止，用 4 字节滑动窗口判断，不引 BufferedReader ——
            // 一旦套上 reader，它会预读，把正文的头几个字节吞进自己的缓冲区。
            //
            // 头按 ISO-8859-1 解（HTTP 头是 ASCII），**正文必须按 UTF-8 解**：客户端
            // `toByteArray(Charsets.UTF_8)` 写出来的中文，用 ISO-8859-1 解会变成乱码
            // （之前就因此让断言读到「æ­£ææ」，一度看着像生产端编码错了）。
            socket.soTimeout = 5_000
            val raw = socket.getInputStream()
            val head = java.io.ByteArrayOutputStream()
            // 移位寄存器：p3/p2/p1 依次是当前字节之前的三个字节。
            // **不能用 window[i % 4] 这种取模存法** —— 存满一轮后数组是旋转的，
            // window[0] 变成最新字节而不是最旧，判断永远不成立，read() 会一直阻塞
            // 把测试挂死（真实踩过）。
            var p3 = -1; var p2 = -1; var p1 = -1
            var foundEnd = false
            while (!foundEnd) {
                val b = raw.read()
                if (b < 0) return
                head.write(b)
                if (p3 == 13 && p2 == 10 && p1 == 13 && b == 10) foundEnd = true
                p3 = p2; p2 = p1; p1 = b
            }

            val headText = head.toString(Charsets.ISO_8859_1)
            val lines = headText.split("\r\n").filter { it.isNotBlank() }
            val parts = (lines.firstOrNull() ?: return).split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val target = parts.getOrElse(1) { "/" }

            val headers = mutableMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = raw.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else {
                null
            }

            val qIdx = target.indexOf('?')
            last = CapturedRequest(
                method = method,
                path = if (qIdx >= 0) target.substring(0, qIdx) else target,
                query = if (qIdx >= 0) target.substring(qIdx + 1) else null,
                authorization = headers["authorization"],
                contentType = headers["content-type"],
                body = body?.ifEmpty { null },
            )

            val (status, json) = handler(requireNotNull(last))
            val bytes = json.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write(
                ("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
            out.write(bytes)
            out.flush()
        }

        fun stop() {
            runCatching { server.close() }
            thread?.interrupt()
        }
    }

    private fun <T> withLocalApi(
        handler: (CapturedRequest) -> Pair<Int, String>,
        block: (LocalApi) -> T,
    ): T {
        val api = LocalApi(handler).start()
        return try {
            block(api)
        } finally {
            api.stop()
        }
    }

    @Test
    fun `论坛请求必须带 App 的 Bearer token`() {
        // 这是「免登录」的机械保证：App 登一次拿到的 access token 必须原样出现在
        // 论坛请求头里。后端 src/lib/logto/bearer.ts 见到这个头才走 Bearer 校验；
        // 少了它，所有写操作会 403 CROSS_ORIGIN_REQUEST（原生客户端没有 Origin/Referer）。
        withLocalApi({ 200 to """{"posts":[],"next_cursor":null}""" }) { api ->
            val client = ForumApiClient(
                baseUrl = api.baseUrl,
                tokenProvider = { "app-access-token-xyz" },
            )
            runBlocking { client.posts() }
            val req = requireNotNull(api.last) { "应当发出过请求" }
            assertEquals("GET", req.method)
            assertEquals("/api/posts", req.path)
            assertEquals(
                "Authorization 头必须带 App 的 access token",
                "Bearer app-access-token-xyz",
                req.authorization,
            )
        }
    }

    @Test
    fun `匿名时不发 Authorization 头`() {
        // 公开只读接口匿名可读，游客不该带一个空头过去。
        withLocalApi({ 200 to """{"posts":[],"next_cursor":null}""" }) { api ->
            runBlocking { ForumApiClient(baseUrl = api.baseUrl).posts() }
            assertNull(
                "未登录时不应伪造 Authorization 头",
                requireNotNull(api.last).authorization,
            )
        }
    }

    @Test
    fun `帖子列表的查询参数与后端契约一致`() {
        // sort 只能是 latest / top，写错值后端直接 400 INVALID_SORT。
        assertEquals("latest", ForumSort.Latest.key)
        assertEquals("top", ForumSort.Top.key)
        assertEquals(ForumSort.Top, ForumSort.fromKey("top"))
        assertEquals(ForumSort.Latest, ForumSort.fromKey(null))
        withLocalApi({ 200 to """{"posts":[],"next_cursor":null}""" }) { api ->
            runBlocking {
                ForumApiClient(baseUrl = api.baseUrl).posts(
                    sort = ForumSort.Top, tag = "baoyan", cursor = "abc", limit = 30,
                )
            }
            val q = requireNotNull(requireNotNull(api.last).query)
            assertTrue("必须带 sort", q.contains("sort=top"))
            assertTrue("必须带 tag", q.contains("tag=baoyan"))
            assertTrue("游标原样回传，不许自己拼", q.contains("cursor=abc"))
            assertTrue("limit 要夹在服务端上限 50 内", q.contains("limit=30"))
        }
    }

    @Test
    fun `limit 超出服务端上限要被夹住`() {
        // parsePublicListLimit 拒绝 >50 的值（400 INVALID_LIMIT），客户端先夹住。
        withLocalApi({ 200 to """{"posts":[],"next_cursor":null}""" }) { api ->
            runBlocking { ForumApiClient(baseUrl = api.baseUrl).posts(limit = 999) }
            assertTrue(
                "limit 应被夹到 50",
                requireNotNull(api.last).query!!.contains("limit=50"),
            )
        }
    }

    @Test
    fun `列表响应能解析成真实模型`() {
        // 用后端 posts/route.ts 的真实响应形状（snake_case + author 子对象 + images/tags）。
        val json = """
            {"posts":[{
              "id":"11111111-1111-4111-8111-111111111111",
              "title":"宿舍门禁工具与边界",
              "content":"面向部分东大宿舍门禁的 Android NFC/BLE 客户端案例",
              "post_type":"normal",
              "created_at":"2026-09-30T08:00:00.123456Z",
              "likes_count":12,"comments_count":3,
              "author":{"id":"22222222-2222-4222-8222-222222222222",
                        "display_name":"Stella","username":"stella","avatar_url":null},
              "images":[{"id":"33333333-3333-4333-8333-333333333333",
                        "asset_url":"/api/media/projects/post-assets/x/y.webp",
                        "mime_type":"image/webp","sort_order":0}],
              "tags":[{"id":"44444444-4444-4444-8444-444444444444","name":"保研","slug":"baoyan"}]
            }],"next_cursor":"bmV4dA"}
        """.trimIndent()
        withLocalApi({ 200 to json }) { api ->
            val page = runBlocking { ForumApiClient(baseUrl = api.baseUrl).posts() }
            val post = page.posts.single()
            assertEquals("宿舍门禁工具与边界", post.title)
            assertEquals(12, post.likesCount)
            assertEquals(3, post.commentsCount)
            assertEquals("Stella", post.author?.nameOrFallback)
            assertEquals("baoyan", post.tags.single().slug)
            assertEquals(
                "图片是相对路径，要拼 baseUrl 才能加载",
                "/api/media/projects/post-assets/x/y.webp",
                post.images.single().assetUrl,
            )
            assertEquals("bmV4dA", page.nextCursor)
        }
    }

    @Test
    fun `作者被软删除时 author 为 null 不能崩`() {
        // posts.ts:425 是 `?? null` —— 作者被软删除就返回 null，不是省略字段。
        withLocalApi({ 200 to """{"posts":[{"id":"x","content":"hi","author":null}],"next_cursor":null}""" }) { api ->
            val post = runBlocking { ForumApiClient(baseUrl = api.baseUrl).posts() }.posts.single()
            assertNull("author 可能是 null", post.author)
        }
    }

    @Test
    fun `发帖 body 是严格白名单且 Content-Type 正确`() {
        // 后端 create-input.mjs 多一个 key 就 400 INVALID_BODY；
        // 非 application/json 直接 415（api/same-origin-json.mjs:26-28）。
        withLocalApi({ 201 to """{"post":{"id":"abc"}}""" }) { api ->
            runBlocking {
                ForumApiClient(baseUrl = api.baseUrl).createPost("标题", "正文", listOf("baoyan"))
            }
            val req = requireNotNull(api.last)
            assertEquals("POST", req.method)
            assertTrue("必须 application/json", req.contentType!!.startsWith("application/json"))
            val body = requireNotNull(req.body)
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(body)
                .let { it as kotlinx.serialization.json.JsonObject }
            val keys = obj.keys.toSet()
            assertEquals(
                "body 只能是 content/title/tags 这三个 key",
                setOf("content", "title", "tags"),
                keys,
            )
            assertEquals("正文", obj["content"].toString().trim('"'))
        }
    }

    @Test
    fun `空标题不发 title 字段`() {
        // 空串会被后端存成 null；客户端干脆不带这个 key，省一次往返。
        withLocalApi({ 201 to """{"post":{"id":"abc"}}""" }) { api ->
            runBlocking { ForumApiClient(baseUrl = api.baseUrl).createPost("   ", "正文") }
            val body = requireNotNull(requireNotNull(api.last).body)
            assertFalse("空标题不应进 body", body.contains("title"))
        }
    }

    @Test
    fun `删除内容必须带精确的 confirmation 串`() {
        // content/[targetType]/[targetId]/route.ts:46-48 要求字面量相等，
        // 少一个字符就 400 INVALID_CONFIRMATION。
        withLocalApi({ 200 to """{"deleted":true}""" }) { api ->
            runBlocking {
                ForumApiClient(baseUrl = api.baseUrl).deleteContent(ForumTargetType.Post, "pid")
            }
            val req = requireNotNull(api.last)
            assertEquals("DELETE", req.method)
            assertEquals("/api/content/post/pid", req.path)
            assertTrue(
                "confirmation 必须是 delete_owned_content",
                requireNotNull(req.body).contains("delete_owned_content"),
            )
        }
    }

    @Test
    fun `管理删除走 admin 路由且 confirmation 是 admin_delete`() {
        // admin/content 路由与作者自删是两个端点，confirmation 字面量也不同；
        // 用错路由会 404/401 而不是「删错东西」，但断言语义能防后续改混。
        withLocalApi({ 200 to """{"deleted":true}""" }) { api ->
            runBlocking {
                ForumApiClient(baseUrl = api.baseUrl)
                    .adminDeleteContent(ForumTargetType.Comment, "cid")
            }
            val req = requireNotNull(api.last)
            assertEquals("DELETE", req.method)
            assertEquals("/api/admin/content/comment/cid", req.path)
            assertTrue(
                "confirmation 必须是 admin_delete",
                requireNotNull(req.body).contains(""""admin_delete""""),
            )
            assertFalse("不能混用作者自删的确认串", req.body!!.contains("delete_owned_content"))
        }
    }

    @Test
    fun `编辑帖子 body 白名单只有 title 与 content`() {
        // posts/[id]/route.ts 的 PATCH：多一个 key 就 400 INVALID_BODY；
        // 标签不可改，客户端永远不带 tags。
        withLocalApi({ 200 to """{"post":{"id":"abc"}}""" }) { api ->
            runBlocking {
                ForumApiClient(baseUrl = api.baseUrl).updatePost("abc", "新标题", "新正文")
            }
            val req = requireNotNull(api.last)
            assertEquals("PATCH", req.method)
            assertEquals("/api/posts/abc", req.path)
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(req.body))
                .let { it as kotlinx.serialization.json.JsonObject }
            assertEquals(setOf("content", "title"), obj.keys.toSet())
        }
        // 空标题不带 key（后端会把 title 存成 null，语义一致）。
        withLocalApi({ 200 to """{"post":{"id":"abc"}}""" }) { api ->
            runBlocking { ForumApiClient(baseUrl = api.baseUrl).updatePost("abc", "  ", "新正文") }
            assertFalse("空标题不应进 body", requireNotNull(api.last?.body).contains("title"))
        }
    }

    @Test
    fun `配图上传走 multipart 且字段齐全`() {
        // upload-handler.mjs 要 purpose=post-image + postId + file 三件套，
        // Content-Type 必须带 boundary（否则 415）；帖子必须已存在（顺序约束）。
        withLocalApi({ 201 to """{"media":{"path":"/api/media/x.jpg"}}""" }) { api ->
            runBlocking {
                ForumApiClient(baseUrl = api.baseUrl, tokenProvider = { "tok" })
                    .uploadPostImage("pid", byteArrayOf(1, 2, 3))
            }
            val req = requireNotNull(api.last)
            assertEquals("POST", req.method)
            assertEquals("/api/media/upload", req.path)
            assertTrue(
                "必须 multipart 且带 boundary",
                req.contentType!!.startsWith("multipart/form-data; boundary="),
            )
            val body = requireNotNull(req.body)
            assertTrue("purpose 字段", body.contains("name=\"purpose\""))
            assertTrue("purpose=post-image", body.contains("post-image"))
            assertTrue("postId 字段", body.contains("name=\"postId\""))
            assertTrue("postId 值", body.contains("pid"))
            assertTrue("file 字段", body.contains("name=\"file\""))
            assertTrue("上传必须带 token", req.authorization == "Bearer tok")
        }
    }

    @Test
    fun `非 2xx 被解析成带错误码的异常`() {
        // 后端两种错误体形状：{error} 与 {error, message}，message 可能缺。
        withLocalApi({ 401 to """{"error":"UNAUTHORIZED"}""" }) { api ->
            val e = runCatching { runBlocking { ForumApiClient(baseUrl = api.baseUrl).toggleLike("p") } }
                .exceptionOrNull()
            val apiErr = e as? ForumApiClient.ForumApiException
            assertNotNull("401 应抛 ForumApiException，实际 $e", apiErr)
            assertEquals(401, apiErr!!.status)
            assertEquals("UNAUTHORIZED", apiErr.errorCode)
            assertNull("错误体没有 message 字段时应为 null", apiErr.detail)
            assertTrue("401 必须能被上层识别成「需要登录」", apiErr.isUnauthorized)
        }
        withLocalApi({ 403 to """{"error":"CROSS_ORIGIN_REQUEST","message":"请求来源不受信任"}""" }) { api ->
            val e = runCatching { runBlocking { ForumApiClient(baseUrl = api.baseUrl).createPost(null, "x") } }
                .exceptionOrNull() as? ForumApiClient.ForumApiException
            assertEquals(403, e?.status)
            assertEquals("CROSS_ORIGIN_REQUEST", e?.errorCode)
            assertEquals("请求来源不受信任", e?.detail)
            assertFalse("403 不是未登录，别误导用户去登录", e!!.isUnauthorized)
        }
    }

    @Test
    fun `me 在未登录时返回 null 而不是崩`() {
        // /api/me 永不 401，未登录回 {isAuthenticated:false, viewer:null}。
        withLocalApi({ 200 to """{"isAuthenticated":false,"viewer":null,"user":null}""" }) { api ->
            assertNull(runBlocking { ForumApiClient(baseUrl = api.baseUrl).me() })
        }
        withLocalApi({
            200 to """{"isAuthenticated":true,"viewer":{"id":"v1","displayName":"我","forumRole":"member",
                      "capabilities":["post:create"]},"user":null}"""
        }) { api ->
            val viewer = runBlocking { ForumApiClient(baseUrl = api.baseUrl).me() }
            assertEquals("我", viewer?.nameOrFallback)
            assertEquals(listOf("post:create"), viewer?.capabilities)
        }
    }

    @Test
    fun `点赞与收藏都是 toggle 不是 setter`() {
        // 语义搞错会导致连点两次只生效一次。
        withLocalApi({ 200 to """{"liked":true}""" }) { api ->
            assertTrue(runBlocking { ForumApiClient(baseUrl = api.baseUrl).toggleLike("p1") })
            assertEquals("/api/post-likes", requireNotNull(api.last).path)
            assertTrue("post_id 走 query", requireNotNull(api.last).query!!.contains("post_id=p1"))
        }
        withLocalApi({ 200 to """{"bookmarked":false}""" }) { api ->
            assertFalse(runBlocking { ForumApiClient(baseUrl = api.baseUrl).toggleBookmark("p1") })
            assertEquals("/api/bookmarks", requireNotNull(api.last).path)
        }
    }

    @Test
    fun `正文摘要过长要截断且全图片帖不显示空摘要`() {
        val long = ForumPost(
            id = "x", title = null, content = "字".repeat(300), postType = "normal",
            createdAt = null, likesCount = 0, commentsCount = 0, author = null,
        )
        assertEquals(111, long.excerpt.length)   // 110 + 省略号
        assertTrue(long.excerpt.endsWith("…"))
        val blank = long.copy(content = "   \n  ")
        assertEquals("normal", blank.excerpt)   // 全图片帖退回 postType，不留一条横线
    }

    @Test
    fun `作者名要有兜底 不能渲染成空白`() {
        assertEquals("匿名", ForumAuthor("i", null, null, null).nameOrFallback)
        assertEquals("@stella", ForumAuthor("i", "  ", "stella", null).nameOrFallback)
        assertEquals("Stella", ForumAuthor("i", "Stella", "stella", null).nameOrFallback)
    }

    @Test
    fun `App 不应再对论坛发第二次登录`() {
        // 源码级回归锁：论坛客户端只能复用 AuthStore 的 accessToken，
        // 不允许出现自己的一套 OIDC 流程（再登一次）。
        val root = File("src/main/java/tech/iflink/seuwiki")
        val forumClient = File(root, "data/ForumApiClient.kt")
        assertTrue("应当找到 ForumApiClient.kt", forumClient.isFile)
        val src = forumClient.readText(Charsets.UTF_8)
        assertFalse(
            "论坛客户端不得自己发起 OIDC 授权（那会让用户登两次）",
            Regex("""authorizationEndpoint|/oidc/auth|buildAuthorizationRequest""").containsMatchIn(src),
        )
        // 只能通过 tokenProvider 拿 token
        assertTrue(
            "token 必须来自注入的 AuthStore 会话",
            src.contains("tokenProvider"),
        )
    }

    @Test
    fun `发帖按钮必须真的挂到论坛列表上`() {
        // 回归锁：ComposeFab 靠 onCompose 非空才渲染（null 就第一行 return）。
        // 这条链路跨三个文件，之前正是在 ExperienceScreen → ForumPostList
        // 这一段漏传，签名接了、参数没转发，App 编得过、按钮就是不出现。
        // 现在的链路是 ExperienceTab.Hot → ForumHotList(onCompose = …)。
        val root = File("src/main/java/tech/iflink/seuwiki")
        val experience = File(root, "ui/experience/ExperienceScreen.kt")
        val forum = File(root, "ui/forum/ForumScreens.kt")
        val rootView = File(root, "ui/RootView.kt")
        assertTrue("应当找到 ExperienceScreen.kt", experience.isFile)
        assertTrue("应当找到 ForumScreens.kt", forum.isFile)
        assertTrue("应当找到 RootView.kt", rootView.isFile)

        // 1) 热榜列表内部确实渲染了 FAB
        assertTrue(
            "ForumHotList 必须渲染 ComposeFab",
            Regex("""ComposeFab\(\s*onCompose\s*=\s*onCompose\s*\)""").containsMatchIn(forum.readText(Charsets.UTF_8)),
        )
        // 2) 「热门」子页把回调转发进 ForumHotList —— 断掉的话按钮整个不出现
        val hotCall = Regex(
            """ExperienceTab\.Hot\s*->\s*ForumHotList\((?:[^()]|\([^()]*\))*\)""",
        ).find(experience.readText(Charsets.UTF_8))
        assertNotNull("应当找到「热门」子页对 ForumHotList 的调用", hotCall)
        assertTrue(
            "「热门」子页必须把 onCompose 转发给 ForumHotList，否则发帖按钮不出现",
            hotCall!!.value.contains("onCompose"),
        )
        // 3) 顶层路由给了真实目标
        assertTrue(
            "RootView 必须把发帖按钮接到 FORUM_COMPOSE 路由",
            Regex("""onCompose\s*=\s*\{\s*navController\.navigate\(Routes\.FORUM_COMPOSE\)""")
                .containsMatchIn(rootView.readText(Charsets.UTF_8)),
        )
    }

    @Test
    fun `论坛的收藏页和话题页都必须有真实入口`() {
        // 回归锁：这两个路由一度「注册了但没人 navigate」—— 页面写得再全，
        // 用户也永远到不了。这里钉住「路由注册」与「存在调用点」两头。
        val root = File("src/main/java/tech/iflink/seuwiki")
        val rootView = File(root, "ui/RootView.kt")
        val profile = File(root, "ui/profile/ProfileScreen.kt")
        val forum = File(root, "ui/forum/ForumScreens.kt")
        assertTrue("应当找到 ProfileScreen.kt", profile.isFile)
        val rv = rootView.readText(Charsets.UTF_8)
        val fs = forum.readText(Charsets.UTF_8)

        // --- 收藏页 ---
        assertTrue("FORUM_BOOKMARKS 路由应当已注册", rv.contains("composable(Routes.FORUM_BOOKMARKS)"))
        assertTrue(
            "必须有人 navigate 到收藏页，否则论坛收藏功能没有入口",
            rv.contains("navController.navigate(Routes.FORUM_BOOKMARKS)"),
        )
        assertTrue(
            "个人页必须渲染收藏入口那一行",
            profile.readText(Charsets.UTF_8).contains("onOpenForumBookmarks"),
        )

        // --- 话题页 ---
        assertTrue("topicDetail 路由应当已注册", rv.contains("route = Routes.TOPIC_DETAIL"))
        assertTrue(
            "必须有人 navigate 到话题页，否则 topicDetail 是死代码",
            Regex("""navController\.navigate\(Routes\.topicDetail\(it\)\)""").containsMatchIn(rv),
        )
        // 标签要真的可点：onClick 必须在 ForumPostRow 内部被用掉
        assertTrue(
            "ForumPostRow 收到 onOpenTopic 却没渲染可点标签，话题页仍然进不去",
            fs.contains("ForumTagPill") && Regex("""onClick = onOpenTopic""").containsMatchIn(fs),
        )
        // slug 为空的标签不许给入口：传空串后端会返回全站帖子，不是用户点的话题
        assertTrue(
            "空 slug 的标签不能给话题页入口",
            Regex("""takeIf\s*\{\s*tag\.slug\.isNotBlank\(\)""").containsMatchIn(fs),
        )

        // --- 未登录的空态必须能照做 ---
        // 收藏页以前带着 onGoLogin 回调却从不使用：空态只摆一句
        // 「登录后才能进行这个操作」，不给任何按钮，用户走不下去。
        val storeSrc = File(root, "data/ForumStore.kt").readText(Charsets.UTF_8)
        assertTrue(
            "BookmarkState 缺 needsLogin，空态分不出「要登录」和「真加载失败」",
            Regex("""data class BookmarkState\((?:[^()]|\([^()]*\))*needsLogin""").containsMatchIn(storeSrc),
        )
        val body = fs.substringAfter("fun ForumBookmarksScreen(")
        assertTrue(
            "onGoLogin 必须真的被用掉，不能是死参数",
            Regex("""onClick\s*=\s*onGoLogin""").containsMatchIn(body),
        )
        assertTrue(
            "需登录时要把登录按钮交给空态",
            Regex("""action\s*=\s*loginAction""").containsMatchIn(body),
        )
    }

    @Test
    fun `配图的相对路径要拼成绝对地址`() {
        // 服务端 `src/lib/media/security.mjs` 刻意只返回 `/api/media/...` 相对路径。
        // 客户端拼错域名，帖子配图就整片加载不出来，而且没有任何报错。
        assertEquals(
            "https://forum.seu.wiki/api/media/x.png",
            ForumApiClient.absoluteUrl("/api/media/x.png"),
        )
        // 没有前导斜杠也要拼对，否则会变成 //api/...
        assertEquals(
            "https://forum.seu.wiki/api/media/x.png",
            ForumApiClient.absoluteUrl("api/media/x.png"),
        )
        // 已经是绝对地址的原样返回，不能被再套一层域名
        assertEquals(
            "https://cdn.example.com/a.png",
            ForumApiClient.absoluteUrl("https://cdn.example.com/a.png"),
        )
        // 自定义 baseUrl（测试服务器用）也要生效
        assertEquals(
            "http://127.0.0.1:8080/api/media/x.png",
            ForumApiClient.absoluteUrl("/api/media/x.png", baseUrl = "http://127.0.0.1:8080/"),
        )
        // 空值不产出占位图 URL —— 否则会给 AsyncImage 塞一个 "/"，渲染出破图
        assertNull("null 不该拼出地址", ForumApiClient.absoluteUrl(null))
        assertNull("空串不该拼出地址", ForumApiClient.absoluteUrl(""))
        assertNull("纯空白不该拼出地址", ForumApiClient.absoluteUrl("   "))
    }

    // MARK: - 热榜（offset 分页 / 浏览数 / 置顶）

    @Test
    fun `热榜用 offset 分页且一个字节都不能带 cursor`() {
        // 后端热榜只认 offset：对 hot 传 cursor 直接 400 INVALID_CURSOR。
        withLocalApi({ 200 to """{"posts":[],"next_offset":40,"total_count":123}""" }) { api ->
            val page = runBlocking {
                ForumApiClient(baseUrl = api.baseUrl).posts(sort = ForumSort.Hot, offset = 40)
            }
            val q = requireNotNull(requireNotNull(api.last).query)
            assertTrue("必须带 sort=hot", q.contains("sort=hot"))
            assertTrue("必须带 offset=40", q.contains("offset=40"))
            assertFalse("hot 绝不能带 cursor", q.contains("cursor="))
            assertEquals(40, page.nextOffset)
            assertEquals(123, page.totalCount)
            assertNull("热榜响应没有 next_cursor", page.nextCursor)
        }
    }

    @Test
    fun `列表解析浏览数与置顶时间`() {
        // views_count 是反范式列；pinned_at 非空即置顶（热榜置顶帖排最前）。
        val json = """{"posts":[{"id":"p1","content":"hi","views_count":45,
            "pinned_at":"2026-10-07T04:47:20.123456+00:00"}],"next_cursor":null}"""
        withLocalApi({ 200 to json }) { api ->
            val post = runBlocking { ForumApiClient(baseUrl = api.baseUrl).posts() }.posts.single()
            assertEquals(45, post.viewsCount)
            assertTrue("pinned_at 非空即置顶", post.isPinned)
            assertNotNull(post.pinnedAt)
        }
    }

    @Test
    fun `日期解析 带 6 位微秒与时区偏移`() {
        // PostgREST 序列化的 timestamptz 形如 2026-10-07T04:47:20.123456+00:00。
        assertNotNull(
            "6 位微秒必须能解析",
            parseIso8601("2026-10-07T04:47:20.123456+00:00"),
        )
    }

    @Test
    fun `帖子详情解析已收录的手册文章`() {
        // 详情接口带 handbook_article（该帖沉淀出的最新一篇），详情页据此给
        // 「已收录进东大生存手册」入口。
        val json = """{"post":{"id":"p1","content":"hi"},"featured":true,
            "handbook_article":{"id":"a1","title":"宿舍门禁全攻略"}}"""
        withLocalApi({ 200 to json }) { api ->
            val post = runBlocking { ForumApiClient(baseUrl = api.baseUrl).postDetail("p1") }
            assertEquals("a1", post.handbookArticle?.id)
            assertEquals("宿舍门禁全攻略", post.handbookArticle?.title)
            assertTrue(post.featured)
        }
    }

    @Test
    fun `浏览计数成功返回权威值 失败静默不抛`() {
        // incrementView 是锦上添花：详情页已经拿着列表给的数字，这一发挂了
        // 必须静默返回 null，绝不能反过来让详情显示错误。
        withLocalApi({ 200 to """{"views":42}""" }) { api ->
            assertEquals(42, runBlocking { ForumApiClient(baseUrl = api.baseUrl).incrementView("x") })
            val req = requireNotNull(api.last)
            assertEquals("POST", req.method)
            assertEquals("/api/posts/x/view", req.path)
        }
        withLocalApi({ 500 to """{"error":"INTERNAL"}""" }) { api ->
            assertNull(
                "500 必须吞掉返回 null",
                runBlocking { ForumApiClient(baseUrl = api.baseUrl).incrementView("x") },
            )
        }
    }

    // MARK: - 关注（follows / 关注流）

    @Test
    fun `关注切换 body 是严格白名单两键`() {
        // lib/follows/create-input.mjs 多一个 key 就 400 INVALID_BODY。
        withLocalApi({ 200 to """{"followed":true}""" }) { api ->
            assertTrue(runBlocking { ForumApiClient(baseUrl = api.baseUrl).toggleFollowTag("baoyan") })
            val req = requireNotNull(api.last)
            assertEquals("POST", req.method)
            assertEquals("/api/follows", req.path)
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(req.body))
                as kotlinx.serialization.json.JsonObject
            assertEquals(
                "body 只能是 target_type/target_id 这两个 key",
                setOf("target_type", "target_id"),
                obj.keys.toSet(),
            )
            assertEquals("tag", obj["target_type"].toString().trim('"'))
            assertEquals("baoyan", obj["target_id"].toString().trim('"'))
        }
    }

    @Test
    fun `关注目录解析成板块列表`() {
        val json = """{"follows":[{"tag_slug":"baoyan","tag_name":"保研","topic_slug":"baoyan"}],
            "next_cursor":null}"""
        withLocalApi({ 200 to json }) { api ->
            val tags = runBlocking { ForumApiClient(baseUrl = api.baseUrl).followedTags() }
            val tag = tags.single()
            assertEquals("baoyan", tag.slug)
            assertEquals("保研", tag.name)
            assertEquals("baoyan", tag.topicSlug)
            assertEquals(
                "target_type=tag 必须进 query",
                "target_type=tag",
                requireNotNull(api.last).query,
            )
        }
    }

    @Test
    fun `关注流 401 必须识别成未登录`() {
        // ForumStore 据此给登录引导而不是网络错误；认错了用户会看到「加载失败」。
        withLocalApi({ 401 to """{"error":"UNAUTHORIZED"}""" }) { api ->
            val e = runCatching {
                runBlocking { ForumApiClient(baseUrl = api.baseUrl).followingFeed() }
            }.exceptionOrNull() as? ForumApiClient.ForumApiException
            assertNotNull("401 应抛 ForumApiException", e)
            assertTrue("必须识别成未登录", e!!.isUnauthorized)
        }
    }

    @Test
    fun `关注流零关注时下发推荐板块`() {
        // 零关注：posts 为空且下发 suggested_tags（对象数组，不是字符串数组）。
        val json = """{"posts":[],"next_cursor":null,
            "suggested_tags":[{"slug":"baoyan","name":"保研","post_count":86}]}"""
        withLocalApi({ 200 to json }) { api ->
            val page = runBlocking { ForumApiClient(baseUrl = api.baseUrl).followingFeed() }
            assertTrue(page.posts.isEmpty())
            val suggested = page.suggestedTags.single()
            assertEquals("baoyan", suggested.slug)
            assertEquals("保研", suggested.name)
            assertEquals(86, suggested.postCount)
        }
    }

    // MARK: - 东大生存手册

    @Test
    fun `手册板块列表解析文章数与子标签`() {
        val json = """{"sections":[{"slug":"baoyan","name":"保研","type":"topic",
            "article_count":12,
            "children":[{"slug":"baoyan-jingyan","name":"经验分享","article_count":5}]}]}"""
        withLocalApi({ 200 to json }) { api ->
            val sections = runBlocking { ForumApiClient(baseUrl = api.baseUrl).handbookSections() }
            val section = sections.single()
            assertEquals("baoyan", section.slug)
            assertEquals("保研", section.name)
            assertEquals(12, section.articleCount)
            assertEquals(5, section.children.single().articleCount)
            assertEquals("/api/handbook/sections", requireNotNull(api.last).path)
        }
    }

    @Test
    fun `手册板块详情解析 子标签没有文章数时兜底 0`() {
        // 板块详情接口的 children 不带 article_count（只有列表接口给），
        // 客户端必须兜底成 0 而不是崩。
        val json = """{"section":{"slug":"baoyan-jingyan","name":"经验分享","type":"tag",
            "parent_slug":"baoyan","children":[{"slug":"c1","name":"子标签"}]},
            "articles":[{"id":"a1","tag_slug":"baoyan-jingyan","title":"宿舍门禁全攻略",
            "author_display":"编辑部","published_at":"2026-10-01",
            "source_post":{"id":"p1","title":"原帖"}}]}"""
        withLocalApi({ 200 to json }) { api ->
            val page = runBlocking { ForumApiClient(baseUrl = api.baseUrl).handbookSection("baoyan-jingyan") }
            assertEquals("经验分享", page.name)
            assertEquals("baoyan", page.parentSlug)
            assertEquals(0, page.children.single().articleCount)
            val article = page.articles.single()
            assertEquals("宿舍门禁全攻略", article.title)
            assertEquals("p1", article.sourcePost?.id)
        }
    }

    @Test
    fun `手册文章详情解析正文与原帖`() {
        val json = """{"article":{"id":"a1","tag_slug":"baoyan","title":"宿舍门禁全攻略",
            "author_display":"编辑部","content_html":"<p>你好</p>",
            "published_at":"2026-10-01T00:00:00Z",
            "source_post":{"id":"p1","title":"门禁原帖","likes_count":3,"comments_count":2,
            "views_count":10,"author":{"id":"u1","display_name":"Stella",
            "username":"stella","avatar_url":null}}}}"""
        withLocalApi({ 200 to json }) { api ->
            val article = runBlocking { ForumApiClient(baseUrl = api.baseUrl).handbookArticle("a1") }
            assertEquals("<p>你好</p>", article.contentHtml)
            assertEquals("编辑部", article.authorDisplay)
            assertNotNull(article.publishedAt)
            val source = requireNotNull(article.sourcePost)
            assertEquals("p1", source.id)
            assertEquals(10, source.viewsCount)
            assertEquals("Stella", source.author?.nameOrFallback)
        }
    }

    // MARK: - 论坛搜索（type=all 两桶同返）

    @Test
    fun `论坛搜索两桶同返且各带游标`() {
        // type=all：posts 与 articles 两组同返，游标带 scope。
        val json = """{"posts":{"items":[{"id":"s1","title":"保研帖","snippet":"…片段…",
            "author":null,"likes_count":1,"comments_count":2,"views_count":3,
            "created_at":"2026-10-01T00:00:00Z"}],"next_cursor":"c1"},
            "articles":{"items":[{"id":"a1","title":"手册文","snippet":"摘",
            "tag_slug":"baoyan","published_at":"2026-10-01","source_post_id":"p1"}],
            "next_cursor":null}}"""
        withLocalApi({ 200 to json }) { api ->
            val results = runBlocking { ForumApiClient(baseUrl = api.baseUrl).search("保研") }
            val post = results.posts.single()
            assertEquals("s1", post.id)
            assertEquals("…片段…", post.snippet)
            assertEquals(3, post.viewsCount)
            assertEquals("c1", results.postsNextCursor)
            val article = results.articles.single()
            assertEquals("baoyan", article.tagSlug)
            assertEquals("p1", article.sourcePostId)
            assertNull(results.articlesNextCursor)
            val q = requireNotNull(requireNotNull(api.last).query)
            assertTrue("必须带 type=all", q.contains("type=all"))
            assertTrue("必须带关键词", q.contains("q="))
            assertEquals("/api/search", requireNotNull(api.last).path)
        }
    }

    // MARK: - 话题目录

    @Test
    fun `话题目录结构与后端 catalog 对齐`() {
        // 目录镜像自论坛后端 src/lib/tags/catalog.mjs：8 主题 + 29 子标签，
        // slug 全集 37 个互不重复，格式与后端 slug 规则一致。
        assertEquals("主题必须恰好 8 个", 8, TopicCatalog.topics.size)
        val subtagCount = TopicCatalog.topics.sumOf { it.subtags.size }
        assertEquals("子标签必须恰好 29 个", 29, subtagCount)
        val slugs = TopicCatalog.topics.map { it.slug } +
            TopicCatalog.topics.flatMap { t -> t.subtags.map { it.slug } }
        assertEquals("slug 全集 37 个", 37, slugs.size)
        assertEquals("slug 不得重复", slugs.size, slugs.toSet().size)
        val slugRule = Regex("""^[a-z0-9][a-z0-9-]{0,39}$""")
        slugs.forEach { slug ->
            assertTrue("slug 不合后端规则：$slug", slugRule.matches(slug))
        }
    }

    @Test
    fun `话题目录 nameForSlug 两级都能查`() {
        assertEquals("保研", TopicCatalog.nameForSlug("baoyan"))
        assertNotNull("子标签也要能查", TopicCatalog.nameForSlug("baoyan-jingyan"))
        assertNull("未知 slug 返回 null 而不是乱兜", TopicCatalog.nameForSlug("不存在的"))
    }

    // MARK: - 账号体系（登录态单一事实源）

    @Test
    fun `登录钩子拉取身份 登出钩子清空用户态`() {
        // v0.3.1 账号重构的核心行为锁。曾经的死循环：UI 拿 store.viewer 当
        // 登录门禁，而 refreshViewer() 全工程零调用，viewer 永为 null。
        // 重构后：登录 → RootView 统一调 onSignedIn()（必须真的去问 /api/me）；
        // 登出 → onSignedOut() 把上一个账号的投影洗干净，防串号。
        val meJson = """{"isAuthenticated":true,"viewer":{"id":"u1","displayName":"梁","username":"liangyufan","forumRole":"owner"}}"""
        val paths = mutableListOf<String>()
        withLocalApi({ req ->
            paths += req.path
            if (req.path == "/api/me") 200 to meJson else 200 to """{"posts":[],"next_cursor":null}"""
        }) { api ->
            val store = ForumStore(
                ForumApiClient(baseUrl = api.baseUrl, tokenProvider = { "app-token" }),
            )
            assertNull("还没触发登录钩子时不该有 viewer", store.viewer)

            store.onSignedIn()
            waitForStore { store.viewerLoaded }

            assertTrue("onSignedIn 必须触发 /api/me，实际请求过：$paths", "/api/me" in paths)
            val viewer = requireNotNull(store.viewer) { "onSignedIn 后 viewer 必须就位" }
            assertEquals("u1", viewer.id)
            assertEquals("owner", viewer.forumRole)

            store.onSignedOut()
            assertNull("onSignedOut 必须清掉 viewer", store.viewer)
            assertFalse("onSignedOut 后 viewerLoaded 要复位", store.viewerLoaded)
            assertFalse("收藏状态要复位成未加载", store.bookmarks.hasLoaded)
            assertTrue("关注的板块必须清空", store.followedTags.isEmpty())
        }
    }

    /** viewModelScope 走 Main → IO → Main，测试里反复榨干主线程队列直到条件满足。 */
    private fun waitForStore(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `论坛写操作门禁只看 isLoggedIn 不看 viewer`() {
        // 源码级回归锁：门禁的事实源只能是 AuthStore.isLoggedIn（本地会话），
        // viewer 是服务端投影，只做展示/自检，绝不能再当门禁用。
        val root = File("src/main/java/tech/iflink/seuwiki")
        val screens = File(root, "ui/forum/ForumScreens.kt").readText(Charsets.UTF_8)
        assertFalse(
            "UI 层不得再拿 store.viewer 当门禁（viewer 可能根本没拉过，这正是死循环根因）",
            screens.contains("store.viewer == null"),
        )
        assertTrue(
            "发帖/点赞/收藏/评论的门禁必须看 isLoggedIn",
            screens.contains("if (!isLoggedIn) onLoginRequired()"),
        )

        val rootView = File(root, "ui/RootView.kt").readText(Charsets.UTF_8)
        assertTrue(
            "登录态变化必须统一驱动论坛 store 的钩子",
            Regex("""LaunchedEffect\(auth\.isLoggedIn\)""").containsMatchIn(rootView),
        )
        assertTrue("登录要调 forumStore.onSignedIn()", rootView.contains("forumStore.onSignedIn()"))
        assertTrue("登出要调 forumStore.onSignedOut()", rootView.contains("forumStore.onSignedOut()"))
        assertTrue(
            "详情页与发帖页都必须拿到真登录态",
            Regex("""isLoggedIn = auth\.isLoggedIn""").findAll(rootView).count() >= 2,
        )

        val storeSrc = File(root, "data/ForumStore.kt").readText(Charsets.UTF_8)
        assertTrue("ForumStore 必须实现 onSignedIn()", storeSrc.contains("fun onSignedIn()"))
        assertTrue("ForumStore 必须实现 onSignedOut()", storeSrc.contains("fun onSignedOut()"))
    }

    // MARK: - 论坛通知

    @Test
    fun `通知列表响应能解析 且 actor 与 post 可为 null`() {
        // src/lib/services/notifications.ts 的真实响应形状：
        // snake_case、actor（动作方被软删）与 post（目标帖已删）都可能为 null，条目仍下发。
        val json = """
            {"notifications":[
              {"id":"n1","type":"comment","target_type":"post","target_id":"p1",
               "read_at":null,"created_at":"2026-10-08T02:00:00.000Z",
               "actor":{"id":"u1","display_name":"Stella","username":"stella","avatar_url":null},
               "post":{"id":"p1","title":"宿舍门禁工具与边界","excerpt":"面向部分东大宿舍…"}},
              {"id":"n2","type":"like","target_type":"post","target_id":"p2",
               "read_at":"2026-10-07T10:00:00.000Z","created_at":"2026-10-07T09:00:00.000Z",
               "actor":null,"post":null}
            ],"next_cursor":"bmV4dA","unread_count":3}
        """.trimIndent()
        withLocalApi({ 200 to json }) { api ->
            val page = runBlocking { ForumApiClient(baseUrl = api.baseUrl).notifications() }
            assertEquals(2, page.items.size)
            assertEquals("bmV4dA", page.nextCursor)
            assertEquals("角标只认服务端下发的 unread_count", 3, page.unreadCount)
            val first = page.items[0]
            assertEquals("comment", first.type)
            assertEquals("p1", first.targetId)
            assertTrue("read_at 为 null 即未读", first.isUnread)
            assertEquals("Stella", first.actor?.nameOrFallback)
            assertEquals("宿舍门禁工具与边界", first.post?.title)
            val second = page.items[1]
            assertNull("actor 可为 null", second.actor)
            assertNull("post 可为 null（帖子已删）", second.post)
            assertFalse(second.isUnread)
        }
    }

    @Test
    fun `通知列表的游标原样回传`() {
        withLocalApi({ 200 to """{"notifications":[],"next_cursor":null,"unread_count":0}""" }) { api ->
            runBlocking { ForumApiClient(baseUrl = api.baseUrl).notifications(cursor = "abc", limit = 30) }
            val q = requireNotNull(requireNotNull(api.last).query)
            assertTrue("游标原样回传，不许自己拼", q.contains("cursor=abc"))
            assertTrue("limit 要夹在服务端上限 50 内", q.contains("limit=30"))
            assertEquals("/api/notifications", requireNotNull(api.last).path)
        }
    }

    @Test
    fun `全部已读的 body 必须是空对象`() {
        // notifications/mark-read/route.ts 的 hasOnlyKeys(parsed.body, [])：
        // 多一个 key 就 400 INVALID_BODY，所以 body 只能恰好是 {}。
        withLocalApi({ 200 to """{"marked":2,"unread_count":0}""" }) { api ->
            val unread = runBlocking {
                ForumApiClient(baseUrl = api.baseUrl).markNotificationsRead()
            }
            assertEquals(0, unread)
            val req = requireNotNull(api.last)
            assertEquals("POST", req.method)
            assertEquals("/api/notifications/mark-read", req.path)
            assertEquals("body 必须恰好是空对象", "{}", req.body)
            assertTrue("必须 application/json", req.contentType!!.startsWith("application/json"))
        }
    }

    @Test
    fun `角标随接口更新 全部已读后清零 登出后复位`() {
        val listJson = """{"notifications":[],"next_cursor":null,"unread_count":2}"""
        withLocalApi({ req ->
            when (req.path) {
                "/api/notifications/mark-read" -> 200 to """{"marked":2,"unread_count":0}"""
                else -> 200 to listJson
            }
        }) { api ->
            val store = ForumStore(
                ForumApiClient(baseUrl = api.baseUrl, tokenProvider = { "app-token" }),
            )
            assertEquals(0, store.unreadCount)

            store.refreshUnreadCount()
            waitForStore { store.unreadCount == 2 }
            assertEquals("角标来自服务端 unread_count", 2, store.unreadCount)

            store.markNotificationsRead()
            waitForStore { store.unreadCount == 0 }
            assertEquals("全部已读后角标立即清零", 0, store.unreadCount)

            store.onSignedOut()
            assertEquals("登出后角标必须复位", 0, store.unreadCount)
            assertFalse("登出后通知列表复位成未加载", store.notifications.hasLoaded)
        }
    }
}
