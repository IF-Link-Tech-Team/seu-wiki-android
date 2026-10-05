package tech.iflink.seuwiki.models

/**
 * Development sample data, shaped to match the real seu-wiki-v2 and seu-forum
 * responses.
 *
 * The content is a 1:1 port of the iOS `MockData` so that, while the app runs
 * against mock data, Android and iOS show byte-identical copy — which is what
 * makes screenshot comparison between the two platforms meaningful.
 */
object MockData {

    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR

    private fun now() = System.currentTimeMillis()

    val feedItems: List<FeedItem> = listOf(
        FeedItem(
            id = "f1",
            title = "2026 年推荐优秀应届本科毕业生免试攻读研究生工作启动",
            summary = "各学院推免名额已下达，10 月 12 日前完成个人申请与材料提交，教务处将组织校级审核与公示。",
            sourceName = "教务处",
            category = FeedCategory.Academic,
            tags = listOf("保研", "推免"),
            publishedAt = now() - 3 * HOUR,
            originalUrl = "https://jwc.seu.edu.cn",
            score = 92,
            isSelected = true,
            audience = CampusAudience(
                identities = listOf("本科生"),
                colleges = listOf("全校"),
                grades = listOf("大四"),
                deadline = now() + 8 * DAY,
                valueTier = ValueTier.Action,
            ),
            matchReasons = listOf("你的学段", "即将截止"),
        ),
        FeedItem(
            id = "f2",
            title = "信息科学与工程学院 SRTP 项目中期检查安排",
            summary = "2025 年立项的校级以上 SRTP 项目须于 10 月 20 日前提交中期报告，学院将组织分组答辩。",
            sourceName = "信息科学与工程学院",
            category = FeedCategory.Competition,
            tags = listOf("SRTP", "科研训练"),
            publishedAt = now() - 7 * HOUR,
            originalUrl = "https://radio.seu.edu.cn",
            score = 85,
            isSelected = true,
            audience = CampusAudience(
                identities = listOf("本科生"),
                colleges = listOf("信息科学与工程学院"),
                grades = listOf("大二", "大三"),
                deadline = now() + 16 * DAY,
                valueTier = ValueTier.Action,
            ),
            matchReasons = listOf("你的学院", "你的兴趣"),
        ),
        FeedItem(
            id = "f3",
            title = "2026 年春季学期校级交换生项目（第一批）报名通知",
            summary = "覆盖加州伯克利、慕尼黑工大等 23 所合作院校，雅思 6.5 / 托福 90 以上可申报，11 月 3 日截止。",
            sourceName = "国际合作处",
            category = FeedCategory.Exchange,
            tags = listOf("交换", "留学"),
            publishedAt = now() - DAY,
            originalUrl = "https://cie.seu.edu.cn",
            score = 78,
            isSelected = true,
            audience = CampusAudience(
                identities = listOf("本科生", "硕士生"),
                colleges = listOf("全校"),
                deadline = now() + 30 * DAY,
                valueTier = ValueTier.Opportunity,
            ),
            matchReasons = listOf("你的兴趣"),
        ),
        FeedItem(
            id = "f4",
            title = "国家奖学金、校长奖学金评审结果公示",
            summary = "2025—2026 学年国家奖学金拟获奖名单公示，公示期 5 个工作日，异议请实名向学生处反映。",
            sourceName = "学生处",
            category = FeedCategory.Aid,
            tags = listOf("奖学金", "公示"),
            publishedAt = now() - (DAY * 3 / 2),
            originalUrl = "https://xsc.seu.edu.cn",
            score = 70,
            isSelected = false,
            audience = CampusAudience(colleges = listOf("全校")),
        ),
        FeedItem(
            id = "f5",
            title = "九龙湖校区体育馆羽毛球场地预约规则调整",
            summary = "10 月 9 日起预约开放时间由提前 3 天调整为提前 7 天，每日 12:00 统一放号。",
            sourceName = "体育系",
            category = FeedCategory.Life,
            tags = listOf("场馆预约"),
            publishedAt = now() - 2 * DAY,
            originalUrl = "https://tyx.seu.edu.cn",
            score = 55,
            isSelected = false,
            audience = CampusAudience(colleges = listOf("全校")),
        ),
        FeedItem(
            id = "f6",
            title = "「挑战杯」全国大学生课外学术科技作品竞赛校内选拔赛启动",
            summary = "校赛设自然科学类、科技发明制作类、哲学社会科学类三条赛道，10 月 30 日前完成院级推荐。",
            sourceName = "校团委",
            category = FeedCategory.Competition,
            tags = listOf("挑战杯", "竞赛"),
            publishedAt = now() - (5 * DAY / 2),
            originalUrl = "https://tw.seu.edu.cn",
            score = 80,
            isSelected = true,
            audience = CampusAudience(
                identities = listOf("本科生"),
                colleges = listOf("全校"),
                deadline = now() + 26 * DAY,
                valueTier = ValueTier.Opportunity,
            ),
            matchReasons = listOf("你的兴趣"),
        ),
        FeedItem(
            id = "f7",
            title = "图书馆研讨间系统升级，新增静音舱预约",
            summary = "李文正图书馆 4 层新增 12 个静音舱，支持线上面试场景，每次最长可约 2 小时。",
            sourceName = "图书馆",
            category = FeedCategory.Life,
            tags = listOf("图书馆"),
            publishedAt = now() - 3 * DAY,
            originalUrl = "https://lib.seu.edu.cn",
            score = 62,
            isSelected = false,
            audience = CampusAudience(colleges = listOf("全校")),
        ),
        FeedItem(
            id = "f8",
            title = "2026 届毕业生秋季校园招聘会（信息技术专场）参会单位名单",
            summary = "华为、中兴、中国电科等 86 家单位参会，10 月 15 日九龙湖校区焦廷标馆，需提前报名入场。",
            sourceName = "就业指导中心",
            category = FeedCategory.Career,
            tags = listOf("秋招", "招聘会"),
            publishedAt = now() - (7 * DAY / 2),
            originalUrl = "https://job.seu.edu.cn",
            score = 76,
            isSelected = true,
            audience = CampusAudience(
                identities = listOf("本科生", "硕士生"),
                grades = listOf("大四"),
                deadline = now() + 11 * DAY,
                valueTier = ValueTier.Opportunity,
            ),
            matchReasons = listOf("你的学段"),
        ),
        FeedItem(
            id = "f9",
            title = "研究生数学建模竞赛颁奖暨经验分享会",
            summary = "全国一等奖团队「无线通信小分队」将分享选题与论文写作经验，10 月 11 日晚 19:00 四牌楼校区。",
            sourceName = "研究生院",
            category = FeedCategory.Club,
            tags = listOf("数学建模", "讲座"),
            publishedAt = now() - 4 * DAY,
            originalUrl = "https://gs.seu.edu.cn",
            score = 58,
            isSelected = false,
            audience = CampusAudience(colleges = listOf("全校")),
        ),
        FeedItem(
            id = "f10",
            title = "关于 2025 级新生大学英语分级考试成绩查询的通知",
            summary = "分级考试成绩已发布，考生可登录教务系统查询，免修申请同步开放至 10 月 10 日。",
            sourceName = "外国语学院",
            category = FeedCategory.Academic,
            tags = listOf("考试", "成绩查询"),
            publishedAt = now() - 5 * DAY,
            originalUrl = "https://flc.seu.edu.cn",
            score = 66,
            isSelected = false,
            audience = CampusAudience(
                identities = listOf("本科生"),
                colleges = listOf("全校"),
                grades = listOf("大一"),
                deadline = now() + 6 * DAY,
                valueTier = ValueTier.Action,
            ),
        ),
    )

    val forumPosts: List<ForumPost> = listOf(
        ForumPost(
            id = "p1", authorName = "林晚舟", authorHeadline = "20 级 · 保研至清华大学",
            title = "从绩点 3.6 到清华直博：我的保研时间线与信息差复盘",
            excerpt = "三年里我踩过的最大的坑不是绩点，而是信息差。这篇帖子按月份拆解了我从大二下开始做的每一件事：进组、论文、夏令营、套磁……",
            tags = listOf("baoyan"), commentsCount = 342, likesCount = 1893, viewsCount = 24000,
            createdAt = now() - 2 * DAY, isFeatured = true,
        ),
        ForumPost(
            id = "p2", authorName = "陈既明", authorHeadline = "21 级 · SRTP 国家级立项",
            title = "SRTP 国家级立项申请书怎么写？附我们组的完整框架",
            excerpt = "评审老师最关心的其实是研究问题的「颗粒度」。我把我们的立项书逐段拆解，标出了每一处被答辩老师追问的地方……",
            tags = listOf("srtp"), commentsCount = 156, likesCount = 947, viewsCount = 11000,
            createdAt = now() - 9 * HOUR, isFeatured = true,
        ),
        ForumPost(
            id = "p3", authorName = "苏一苇", authorHeadline = "19 级 · ETH Zurich 全奖硕士",
            title = "欧陆留学申请完全指南：为什么可以不只盯着英美",
            excerpt = "ETH、EPFL、TUM 这一档学校对东大学生的认可度其实非常高。从课程匹配度、APS 审核到动机信写作，这篇尽量一次讲清……",
            tags = listOf("liuxue"), commentsCount = 289, likesCount = 1672, viewsCount = 19000,
            createdAt = now() - 5 * DAY, isFeatured = true,
        ),
        ForumPost(
            id = "p4", authorName = "顾之遥", authorHeadline = "21 级 · 考研上岸本校",
            title = "考研 400+ 的数学一复习路径（含每日时间安排表）",
            excerpt = "数学一 138 分，我的核心策略只有一条：真题三遍法。第一遍按章节、第二遍按年份、第三遍只做错题……",
            tags = listOf("kaoyan"), commentsCount = 198, likesCount = 1204, viewsCount = 15000,
            createdAt = now() - DAY, isFeatured = false,
        ),
        ForumPost(
            id = "p5", authorName = "江浸月", authorHeadline = "22 级 · 数模国一",
            title = "数学建模国赛三人分工与 72 小时节奏管理",
            excerpt = "建模手、编程手、写作手的边界在哪里？什么时候睡觉？我们把三次比赛的复盘浓缩成一张时间表……",
            tags = listOf("jingsai"), commentsCount = 121, likesCount = 786, viewsCount = 8900,
            createdAt = now() - 20 * HOUR, isFeatured = false,
        ),
        ForumPost(
            id = "p6", authorName = "陆停云", authorHeadline = "20 级 · 转至计算机学院",
            title = "信息学院转计算机：机考 + 面试全流程回忆",
            excerpt = "机考四道题的两道原题来自往年，面试重点问了数据结构项目和为什么想转专业。材料准备的时间节点很重要……",
            tags = listOf("zhuanye"), commentsCount = 87, likesCount = 502, viewsCount = 6700,
            createdAt = now() - 3 * DAY, isFeatured = false,
        ),
        ForumPost(
            id = "p7", authorName = "沈照野", authorHeadline = "22 级 · 字节跳动暑期实习",
            title = "大二暑假拿到大厂开发实习：简历、笔试、三面复盘",
            excerpt = "低年级的核心竞争力是项目而不是绩点。我把简历改了 17 版，每一版的改动理由都记下来了……",
            tags = listOf("shixi"), commentsCount = 203, likesCount = 1134, viewsCount = 13000,
            createdAt = now() - 30 * HOUR, isFeatured = true,
        ),
        ForumPost(
            id = "p8", authorName = "温知夏", authorHeadline = "23 级 · 梅园食堂品鉴大师",
            title = "九龙湖三大食堂 47 个窗口测评（持续更新）",
            excerpt = "历时一学期，按价格、分量、口味、排队时长四个维度打分。桃园二楼的瓦香鸡依然是版本答案……",
            tags = listOf("shenghuo"), commentsCount = 445, likesCount = 2108, viewsCount = 28000,
            createdAt = now() - 4 * DAY, isFeatured = false,
        ),
    )

    val handbookSections: List<HandbookSection> = listOf(
        HandbookSection(
            id = "h1", name = "新生入学", iconKey = "figure.walk.arrival",
            entries = listOf(
                HandbookEntry(
                    "h1e1", "报到流程与材料清单", "录取通知书、档案、户口迁移",
                    "报到当天先到学院迎新点领取校园卡与宿舍钥匙，再到体育馆完成资格审查……", now() - 20 * DAY,
                ),
                HandbookEntry(
                    "h1e2", "宿舍入住与水电网开通", "床位分配、校园网认证、电费充值",
                    "校园网使用统一身份认证登录，宿舍电费通过「东大信息化」公众号充值……", now() - 18 * DAY,
                ),
            ),
        ),
        HandbookSection(
            id = "h2", name = "学习与选课", iconKey = "books.vertical",
            entries = listOf(
                HandbookEntry(
                    "h2e1", "选课系统使用指南", "志愿式选课与抢课策略",
                    "第一轮为志愿选课不分先后，第二轮起为先到先得。热门通识课建议提前收藏……", now() - 10 * DAY,
                ),
                HandbookEntry(
                    "h2e2", "绩点计算规则", "五分制与百分制换算",
                    "东大采用五分制绩点，90 分以上为 5.0，85–89 为 4.5，依此类推……", now() - 8 * DAY,
                ),
                HandbookEntry(
                    "h2e3", "SRTP 科研训练入门", "立项、中期、结题全流程",
                    "SRTP 分为校级、省级、国家级，每年 4 月与 10 月两批立项……", now() - 5 * DAY,
                ),
            ),
        ),
        HandbookSection(
            id = "h3", name = "奖助学金", iconKey = "yensign.circle",
            entries = listOf(
                HandbookEntry(
                    "h3e1", "奖学金体系一览", "国奖、校奖、社会类奖学金",
                    "国家奖学金 8000 元/年，校长奖学金 10000 元/年，社会类奖学金金额不等……", now() - 12 * DAY,
                ),
            ),
        ),
        HandbookSection(
            id = "h4", name = "校园生活", iconKey = "building.2",
            entries = listOf(
                HandbookEntry(
                    "h4e1", "校园卡与移动支付", "挂失、补办、NFC 校园卡",
                    "校园卡可在「东大信息化」App 开通 NFC，手机即校园卡……", now() - 6 * DAY,
                ),
                HandbookEntry(
                    "h4e2", "校医院就诊指南", "挂号、转诊、医保报销",
                    "校医院支持线上挂号，转诊至中大医院需开具转诊单方可报销……", now() - 4 * DAY,
                ),
            ),
        ),
        HandbookSection(
            id = "h5", name = "出行交通", iconKey = "tram",
            entries = listOf(
                HandbookEntry(
                    "h5e1", "三校区通勤班车时刻", "四牌楼 ↔ 九龙湖 ↔ 丁家桥",
                    "工作日班车每 30 分钟一班，节假日班次减半，需刷校园卡乘坐……", now() - 3 * DAY,
                ),
            ),
        ),
        HandbookSection(
            id = "h6", name = "毕业与升学", iconKey = "graduationcap.circle",
            entries = listOf(
                HandbookEntry(
                    "h6e1", "推免政策解读", "综合成绩构成与名额分配",
                    "推免综合成绩 = 学业成绩 × 85% + 素质发展 × 15%，各学院细则略有不同……", now() - 2 * DAY,
                ),
            ),
        ),
    )

    val reminders: List<CampusReminder> = listOf(
        CampusReminder(
            id = "r1", title = "推免申请材料提交截止", dueDate = now() + 8 * DAY,
            advanceDays = 2, note = "成绩单、排名证明、科研成果复印件各两份", relatedItemId = "f1",
        ),
        CampusReminder(
            id = "r2", title = "SRTP 中期报告提交", dueDate = now() + 16 * DAY,
            advanceDays = 3, relatedItemId = "f2",
        ),
    )

    val courses: List<Course> = listOf(
        Course("c1", "信号与系统", "张教授", "教二-203", 7, 10, 0, 11, 40),
        Course("c2", "数字信号处理", "李教授", "教四-105", 7, 14, 0, 15, 40),
        Course("c3", "机器学习导论", "王教授", "计软楼-301", 1, 8, 0, 9, 40),
    )

    val tools: List<ToolItem> = listOf(
        ToolItem("timetable", "课表", "calendar.day.timeline.left", "blue", "今日 2 节课"),
        ToolItem("gpa", "绩点计算", "percent", "green", "五分制换算"),
        ToolItem("exam", "考试安排", "pencil.and.list.clipboard", "orange", "期末倒计时"),
        ToolItem("library", "图书馆", "books.vertical", "purple", "借阅与研讨间"),
        ToolItem("card", "校园卡", "creditcard", "pink", "余额与流水"),
        ToolItem("bus", "班车查询", "bus", "teal", "三校区通勤"),
        ToolItem("map", "校园地图", "map", "mint", "楼宇导航"),
        ToolItem("elective", "选课助手", "checklist", "indigo", "避雷与推荐"),
    )
}
