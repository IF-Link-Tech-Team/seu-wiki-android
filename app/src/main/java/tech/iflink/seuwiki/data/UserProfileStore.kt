package tech.iflink.seuwiki.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import tech.iflink.seuwiki.ReminderReceiver
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.Course

/**
 * User profile and app-level state.
 *
 * The college / degree / grade / interests 画像字段保留给个人页编辑与其他功能；
 * 资讯流自 2026-10 网页端删除 `/for-you` 后不再消费它们。
 *
 * Persistence goes through [ProfilePersistence] — the counterpart of the iOS
 * `ProfileStorage` — so both clients persist the same set of keys, record a
 * schema version, and keep a `.corruptBackup` instead of silently discarding
 * undecodable data.
 *
 * 现在是 [ViewModel]（A-3）。之前 `remember { UserProfileStore(context) }` 每次
 * 重组/配置变更都可能重建一个实例，构造时会重读一遍磁盘并**整体覆盖**字段 ——
 * 旋转屏幕的瞬间，用户刚在 Profile 页改了一半的输入会被默认值冲掉。
 * ViewModel 让实例跨配置变更存活，磁盘只在显式 mutation 时写。
 *
 * 只持有 [SharedPreferences]（进程级单例，本身不泄漏 Activity），不持有 Context。
 */
class UserProfileStore(
    private val prefs: SharedPreferences,
    /**
     * 排程用的 [android.content.Context]。
     *
     * 用 `applicationContext`（见 [factory]），所以不持有 Activity，
     * ViewModelStore 清理时不会连带泄漏。提醒需要 Context 才能碰 AlarmManager，
     * 这也是这里唯一保留它的理由。
     */
    private val appContext: Context? = null,
) : ViewModel() {

    private val persistence = ProfilePersistence

    companion object {
        /**
         * 工厂需要 Context 才能拿到 prefs，但 ViewModel 本身不该持有 Context。
         * 这里在**构造那一刻**取 `applicationContext` 生成 prefs，之后就只拿着
         * prefs 走，ViewModelStore 清理时不会连带泄漏 Activity。
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            val prefs = ProfilePersistence.open(context)
            return viewModelFactory {
                initializer { UserProfileStore(prefs, app) }
            }
        }
        const val DEFAULT_COLLEGE = "信息科学与工程学院"
        const val DEFAULT_DEGREE = "本科"
        const val DEFAULT_GRADE = "大三"
        val DEFAULT_INTERESTS = listOf("保研", "SRTP", "机器学习")

        // SharedPreferences keys — the counterpart of the iOS ProfileStorage keys.
        const val KEY_INITIALIZED = "profile_initialized"
        const val KEY_COLLEGE = "college"
        const val KEY_DEGREE = "degree"
        const val KEY_GRADE = "grade"
        const val KEY_INTERESTS = "interests"
        const val KEY_REMINDERS = "reminders"
        const val KEY_COURSES = "courses"
        const val KEY_FOLLOWED = "followed_topic_ids"
        const val KEY_BOOKMARKS = "bookmarked_slugs"
    }

    // --- profile -----------------------------------------------------------

    var college: String by mutableStateOf(DEFAULT_COLLEGE)
        private set
    var degree: String by mutableStateOf(DEFAULT_DEGREE)
        private set
    var grade: String by mutableStateOf(DEFAULT_GRADE)
        private set
    var interests: List<String> by mutableStateOf(DEFAULT_INTERESTS)
        private set

    // --- local collections -------------------------------------------------

    /**
     * 提醒与课表默认**为空**（S-4）。
     *
     * 原来默认值是 `MockData.reminders` / `MockData.courses`，也就是全新安装
     * 的用户一进主页就看到两条他从没添加过的提醒（「推免申请材料提交截止」）和三门
     * 他从没选过的课（「信号与系统」）。这些假数据还会被 `init` 里的 `saveAll()`
     * **写进磁盘**，从此变成用户数据 —— 卸载重装都还在。
     *
     * 现在默认空集合：没有提醒就显示「还没有提醒」的引导，没有课表就显示
     * 「去教务系统导入」的说明。要测试内容请在 debug 构建里注入。
     */
    var reminders: List<CampusReminder> by mutableStateOf(emptyList())
        private set
    var courses: List<Course> by mutableStateOf(emptyList())
        private set
    var followedTopicIds: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * 收藏的文档 slug（手册 / 经验长文）。
     *
     * 原来这里叫 `bookmarkedPostIds`、键名 `bookmarked_post_ids`，但**整个 UI 从来
     * 没有调用过** [toggleBookmark] —— 也就是安卓端根本没法收藏任何东西，收藏完
     * 也无处可见（审查 A-13）。iOS 那边是通的：`UserProfile.bookmarkedSlugs` +
     * `DocDetailView` 右上角按钮 + 个人页 `BookmarksSection`。这里按 iOS 对齐。
     *
     * 只存 slug、不存标题：标题从手册/经验索引回填，这样内容改名了不会留下
     * 一个对不上的旧标题。
     */
    var bookmarkedSlugs: Set<String> by mutableStateOf(emptySet())
        private set

    init {
        if (!persistence.isInitialized(prefs)) {
            saveAll()
            persistence.markInitialized(prefs)
        } else {
            val (savedCollege, savedDegree, savedGrade) = persistence.readProfileStrings(prefs)
            college = savedCollege ?: DEFAULT_COLLEGE
            degree = savedDegree ?: DEFAULT_DEGREE
            grade = savedGrade ?: DEFAULT_GRADE
            interests = persistence.readInterests(prefs, DEFAULT_INTERESTS)
            reminders = persistence.readReminders(prefs)
            courses = persistence.readCourses(prefs)
            followedTopicIds = persistence.readStringSet(prefs, KEY_FOLLOWED)
            bookmarkedSlugs = persistence.readStringSet(prefs, KEY_BOOKMARKS)
        }
    }

    // --- derived -----------------------------------------------------------

    /**
     * The next course still to be attended today, shared by the Home bento and
     * the Tools timetable.
     */
    val nextCourse: Course?
        get() {
            val now = System.currentTimeMillis()
            return courses
                .filter { it.weekday == todayWeekdayIndex(now) && it.endMinutes > nowMinutes(now) }
                .minByOrNull { it.startMinutes }
        }

    /** Courses scheduled today, used for the timetable card subtitle. */
    fun todayCourseCount(): Int {
        val now = System.currentTimeMillis()
        return courses.count { it.weekday == todayWeekdayIndex(now) }
    }

    /** The soonest upcoming reminder, for the Home countdown card. */
    val nextReminder: CampusReminder?
        get() = reminders.minByOrNull { it.dueDate }

    // --- mutations ---------------------------------------------------------

    fun updateProfile(
        college: String = this.college,
        degree: String = this.degree,
        grade: String = this.grade,
        interests: List<String> = this.interests,
    ) {
        this.college = college
        this.degree = degree
        this.grade = grade
        this.interests = interests
        persistence.writeProfileStrings(prefs, college, degree, grade, interests)
    }

    /**
     * 新增一条提醒：落盘 + 真的排上闹钟。
     *
     * 返回排程结果（`isExact` / `scheduled`），界面据此**如实**告诉用户到点
     * 会不会响、准不准。返回 null 表示没有 Context（构造时没给），只落盘没排 ——
     * 调用方应把这种情况显示成「没能排上」，不能默认当成成功。
     */
    fun addReminder(reminder: CampusReminder): ReminderScheduler.ScheduleResult? {
        reminders = reminders + reminder
        persistence.writeReminders(prefs, reminders)
        // 排上闹钟：提醒不是只落个列表，到点要真的响（S-5）。
        val ctx = appContext ?: return null
        val result = ReminderScheduler.schedule(ctx, reminder)
        if (!result.scheduled) {
            // 没排上（时间已过 / 闹钟服务不可用）就不该留在列表里假装还提醒着。
            reminders = reminders.filterNot { it.id == reminder.id }
            persistence.writeReminders(prefs, reminders)
        }
        return result
    }

    /** 编辑既有提醒（改期 / 改提前量）：先撤旧的再排新的，避免响两次。 */
    fun updateReminder(reminder: CampusReminder): ReminderScheduler.ScheduleResult? {
        reminders = reminders.map { if (it.id == reminder.id) reminder else it }
        persistence.writeReminders(prefs, reminders)
        val ctx = appContext ?: return null
        val result = ReminderScheduler.schedule(ctx, reminder)
        if (!result.scheduled) {
            reminders = reminders.filterNot { it.id == reminder.id }
            persistence.writeReminders(prefs, reminders)
        }
        return result
    }

    fun removeReminder(id: String) {
        reminders = reminders.filterNot { it.id == id }
        persistence.writeReminders(prefs, reminders)
        appContext?.let {
            ReminderScheduler.cancel(it, id)
            ReminderReceiver.cancelNotification(it, id)
        }
    }

    /**
     * 冷启动对账：把落盘的提醒与已排闹钟对齐。
     *
     * 必须在**读到提醒之后**调用。必要场景：系统重启会清空所有闹钟（静态注册的
     * `BOOT_COMPLETED` Receiver 是 Google Play 的白名单特权，普通应用拿不到，
     * 所以只能靠冷启动这一次对账补回来）；用户在系统设置里清掉闹钟、或 App 被
     * 系统回收导致闹钟被回收时同理。没有这一步，界面显示「有提醒」但到点一声不吭。
     */
    fun reconcileReminderAlarms() {
        val ctx = appContext ?: return
        ReminderScheduler.ensureSchemaVersion(ctx)
        ReminderScheduler.reconcile(ctx, reminders)
    }

    /** Named `updateCourses` because the `courses` setter already takes a List. */
    fun updateCourses(newCourses: List<Course>) {
        courses = newCourses
        persistence.writeCourses(prefs, courses)
    }

    fun toggleFollowTopic(slug: String) {
        followedTopicIds = if (slug in followedTopicIds) {
            followedTopicIds - slug
        } else {
            followedTopicIds + slug
        }
        persistence.writeStringSet(prefs, KEY_FOLLOWED, followedTopicIds)
    }

    fun isBookmarked(slug: String): Boolean = slug in bookmarkedSlugs

    fun toggleBookmark(slug: String) {
        bookmarkedSlugs = if (slug in bookmarkedSlugs) {
            bookmarkedSlugs - slug
        } else {
            bookmarkedSlugs + slug
        }
        persistence.writeStringSet(prefs, KEY_BOOKMARKS, bookmarkedSlugs)
    }

    fun resetToDefaults() {
        persistence.reset(prefs)
        college = DEFAULT_COLLEGE
        degree = DEFAULT_DEGREE
        grade = DEFAULT_GRADE
        interests = DEFAULT_INTERESTS
        reminders = emptyList()
        courses = emptyList()
        followedTopicIds = emptySet()
        bookmarkedSlugs = emptySet()
        saveAll()
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Maps `Calendar.DAY_OF_WEEK` (1 = Sunday) onto the 1 = Monday … 7 = Sunday
     * index that `Course.weekday` and the iOS model both use.
     */
    private fun todayWeekdayIndex(nowMs: Long): Int {
        val dow = java.util.Calendar.getInstance()
            .apply { timeInMillis = nowMs }
            .get(java.util.Calendar.DAY_OF_WEEK)
        return if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
    }

    private fun nowMinutes(nowMs: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    // --- persistence -------------------------------------------------------

    private fun saveAll() {
        persistence.writeProfileStrings(prefs, college, degree, grade, interests)
        persistence.writeReminders(prefs, reminders)
        persistence.writeCourses(prefs, courses)
        persistence.writeStringSet(prefs, KEY_FOLLOWED, followedTopicIds)
        persistence.writeStringSet(prefs, KEY_BOOKMARKS, bookmarkedSlugs)
    }
}

/**
 * 收藏条目的兜底显示标题：取 slug 的最后一段（`survival/观点篇/1-认识` → `1-认识`）。
 *
 * 收藏只存 slug。手册/经验长文索引随旧 `/api/site/docs` 信源移除后没有索引可以
 * 回填标题，最后一段是 slug 里信息量最大的一部分，比整串 slug 或「未命名」诚实。
 * 抽成顶层纯函数，方便自检直接打到（见 SelfCheckTest）。
 */
fun bookmarkDisplayTitle(slug: String): String =
    slug.substringAfterLast('/').ifBlank { slug }
