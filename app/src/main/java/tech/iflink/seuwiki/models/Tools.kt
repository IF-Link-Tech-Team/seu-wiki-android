package tech.iflink.seuwiki.models

import kotlinx.serialization.Serializable

/** A course in the timetable; shared by the Tools grid and the Home bento. */
@Serializable
data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    /** 1 = Monday … 7 = Sunday, matching the iOS `weekday` convention. */
    val weekday: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
) {
    /** e.g. `10:00–11:40`. */
    val timeRangeText: String
        get() = "${fmt(startHour, startMinute)}–${fmt(endHour, endMinute)}"

    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute

    private fun fmt(h: Int, m: Int) = "$h:${m.toString().padStart(2, '0')}"
}

/** A deadline reminder the user set from a notice detail page. */
@Serializable
data class CampusReminder(
    val id: String,
    val title: String,
    val dueDate: Long,
    val advanceDays: Int = 1,
    val note: String = "",
    val relatedItemId: String? = null,
    /**
     * 提醒当天的触发时刻（本地时区的小时 / 分钟）。
     *
     * iOS 的 `ReminderScheduler` 把这个值写死成 9:00（「学生不会想被凌晨的提醒
     * 炸醒」）。Android 侧保留了同样的默认值，但**让用户可改** —— 提醒走的是
     * Material 3 的时间选择器，界面上得看得到、也改得动，否则存的是什么值和
     * 什么时候响完全对不上号。
     */
    val fireHour: Int = DEFAULT_FIRE_HOUR,
    val fireMinute: Int = 0,
) {
    val daysRemaining: Int
        get() {
            val dayMs = 24L * 60 * 60 * 1000
            val todayStart = startOfDay(nowMs())
            val dueStart = startOfDay(dueDate)
            return ((dueStart - todayStart) / dayMs).toInt()
        }

    companion object {
        /** 与 iOS 一致的默认提醒时刻：当天 09:00。 */
        const val DEFAULT_FIRE_HOUR = 9

        fun nowMs(): Long = System.currentTimeMillis()

        fun startOfDay(ms: Long): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = ms
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

/** A Tools-grid entry. [iconKey] is an SF Symbol name, resolved by `SeuIcons`. */
data class ToolItem(
    val id: String,
    val name: String,
    val iconKey: String,
    val tintKey: String,
    val subtitle: String,
    /**
     * 真的能打开吗？
     *
     * 8 个工具里目前只有「课表」和「绩点计算」有实现，其余 6 个点了只会进
     * 一个占位页。原来副标题写得像都能用（「借阅与研讨间」「余额与流水」…），
     * 属于用文案假装功能已上线。置 false 后界面上会明确标「即将推出」，
     * 并且点击不再进入占位页 —— 没有接通的功能不该假装能用。
     */
    val isAvailable: Boolean = false,
)

/** A handbook document node: section → entries. */
data class HandbookSection(
    val id: String,
    val name: String,
    val iconKey: String,
    val entries: List<HandbookEntry>,
)

data class HandbookEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val updatedAt: Long,
)

/**
 * 工具清单。
 *
 * 这是**应用自身的功能目录**，不是用户数据、也不是示例内容，所以从 MockData
 * 移出来单列。移出来的原因是：MockData 里混着编造的帖子、评论和互动数，
 * 那些必须彻底删掉；而工具清单本身是真实的配置，不该跟着一起删。
 */
object ToolCatalog {

    val tools: List<ToolItem> = listOf(
        ToolItem("timetable", "课表", "calendar.day.timeline.left", "blue", "今日课程与周视图", isAvailable = true),
        ToolItem("gpa", "绩点计算", "percent", "green", "五分制换算", isAvailable = true),
        ToolItem("exam", "考试安排", "pencil.and.list.clipboard", "orange", "期末倒计时"),
        ToolItem("library", "图书馆", "books.vertical", "purple", "借阅与研讨间"),
        ToolItem("card", "校园卡", "creditcard", "pink", "余额与流水"),
        ToolItem("bus", "班车查询", "bus", "teal", "三校区通勤"),
        ToolItem("map", "校园地图", "map", "mint", "楼宇导航"),
        ToolItem("elective", "选课助手", "checklist", "indigo", "避雷与推荐"),
    )

    fun tool(id: String): ToolItem? = tools.firstOrNull { it.id == id }
}
