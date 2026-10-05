package tech.iflink.seuwiki.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.Course

/**
 * User profile and app-level state.
 *
 * The college / degree / grade / interests fields are the same `for-you` profile
 * parameters seu-wiki-v2 expects on `/api/site/for-you`.
 *
 * Persistence uses `SharedPreferences`, the direct counterpart of the iOS
 * `UserDefaults` store the SwiftUI `UserProfile` writes to, so the two clients
 * persist the same set of keys and can be reasoned about the same way.
 */
class UserProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("seu_wiki_profile", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
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
        const val KEY_BOOKMARKS = "bookmarked_post_ids"
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
    var bookmarkedPostIds: Set<String> by mutableStateOf(emptySet())
        private set

    init {
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            saveAll()
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        } else {
            college = prefs.getString(KEY_COLLEGE, DEFAULT_COLLEGE) ?: DEFAULT_COLLEGE
            degree = prefs.getString(KEY_DEGREE, DEFAULT_DEGREE) ?: DEFAULT_DEGREE
            grade = prefs.getString(KEY_GRADE, DEFAULT_GRADE) ?: DEFAULT_GRADE
            interests = decodeList(KEY_INTERESTS, DEFAULT_INTERESTS, String.serializer())
            reminders = decodeList(KEY_REMINDERS, emptyList(), CampusReminder.serializer())
            courses = decodeList(KEY_COURSES, emptyList(), Course.serializer())
            followedTopicIds = prefs.getStringSet(KEY_FOLLOWED, emptySet()).orEmpty()
            bookmarkedPostIds = prefs.getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet()
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
        prefs.edit()
            .putString(KEY_COLLEGE, college)
            .putString(KEY_DEGREE, degree)
            .putString(KEY_GRADE, grade)
            .putString(KEY_INTERESTS, encodeList(interests, String.serializer()))
            .apply()
    }

    fun addReminder(reminder: CampusReminder) {
        reminders = reminders + reminder
        persist(KEY_REMINDERS, reminders, CampusReminder.serializer())
    }

    fun removeReminder(id: String) {
        reminders = reminders.filterNot { it.id == id }
        persist(KEY_REMINDERS, reminders, CampusReminder.serializer())
    }

    /** Named `updateCourses` because the `courses` setter already takes a List. */
    fun updateCourses(newCourses: List<Course>) {
        courses = newCourses
        persist(KEY_COURSES, courses, Course.serializer())
    }

    fun toggleFollowTopic(slug: String) {
        followedTopicIds = if (slug in followedTopicIds) {
            followedTopicIds - slug
        } else {
            followedTopicIds + slug
        }
        prefs.edit().putStringSet(KEY_FOLLOWED, followedTopicIds).apply()
    }

    fun toggleBookmark(postId: String) {
        bookmarkedPostIds = if (postId in bookmarkedPostIds) {
            bookmarkedPostIds - postId
        } else {
            bookmarkedPostIds + postId
        }
        prefs.edit().putStringSet(KEY_BOOKMARKS, bookmarkedPostIds).apply()
    }

    fun resetToDefaults() {
        prefs.edit().clear().putBoolean(KEY_INITIALIZED, true).apply()
        college = DEFAULT_COLLEGE
        degree = DEFAULT_DEGREE
        grade = DEFAULT_GRADE
        interests = DEFAULT_INTERESTS
        reminders = emptyList()
        courses = emptyList()
        followedTopicIds = emptySet()
        bookmarkedPostIds = emptySet()
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

    private fun <T> decodeList(
        key: String,
        fallback: List<T>,
        serializer: KSerializer<T>,
    ): List<T> {
        val raw = prefs.getString(key, null) ?: return fallback
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), raw)
        }.getOrDefault(fallback)
    }

    private fun <T> encodeList(value: List<T>, serializer: KSerializer<T>): String =
        json.encodeToString(ListSerializer(serializer), value)

    private fun <T> persist(key: String, value: List<T>, serializer: KSerializer<T>) {
        prefs.edit().putString(key, encodeList(value, serializer)).apply()
    }

    private fun saveAll() {
        persist(KEY_INTERESTS, interests, String.serializer())
        persist(KEY_REMINDERS, reminders, CampusReminder.serializer())
        persist(KEY_COURSES, courses, Course.serializer())
        prefs.edit()
            .putString(KEY_COLLEGE, college)
            .putString(KEY_DEGREE, degree)
            .putString(KEY_GRADE, grade)
            .putStringSet(KEY_FOLLOWED, followedTopicIds)
            .putStringSet(KEY_BOOKMARKS, bookmarkedPostIds)
            .apply()
    }
}
