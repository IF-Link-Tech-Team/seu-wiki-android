package tech.iflink.seuwiki.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 本地 profile / 提醒 / 课表的落盘层，对应 iOS 的 `ProfileStorage`。
 *
 * 之前这些读写散在 `UserProfileStore` 的私有方法里，于是有两个问题：
 *
 * 1. **没有 schema 版本号**。模型一改（比如 `CampusReminder` 加了 `fireHour`），
 *    老用户的 JSON 就解不出来，而解不出来时旧代码直接 `getOrDefault(fallback)` ——
 *    用户的提醒和课表**无声消失**，界面上看起来只是「提醒列表空了」，没有任何
 *    可恢复的痕迹。
 * 2. **读失败就覆盖写**。`decodeList` 失败后返回默认值，下次任意一次 mutation
 *    就会把默认值写回磁盘，坏数据被彻底抹掉，连排查都不剩。
 *
 * 这里照 iOS `ProfileStorage` 的思路补上：
 * - 每个 key 旁边记一个 `<key>.schemaVersion`，当前版本 [SCHEMA_VERSION]；
 * - 解码失败时**先把原始值挪到 `<key>.corruptBackup`**，再返回默认值，
 *   至少还能捞回来；
 * - 版本更高（用户从新版 App 降级回来）时不猜、不覆盖，直接回落默认值并记日志。
 *
 * 这里全是明文 prefs：里面没有凭证，只有画像与用户自己填的提醒/课表。
 * 真正的 token 走 [SecurePrefs]（Keystore 加密 + 备份排除）。
 */
internal object ProfilePersistence {

    const val PREFS_NAME = "seu_wiki_profile"

    /**
     * 落盘格式版本。模型语义变化时 +1。
     *
     * v1 = 裸 JSON，没有版本记录（2026-10 之前的老安装）；
     * v2 = 记录版本号，解码失败留 `.corruptBackup`，不再无声覆盖。
     */
    const val SCHEMA_VERSION = 2

    /** 缺失版本号的历史安装按 v1 处理 —— 那批数据的格式本来就是 v1。 */
    const val LEGACY_VERSION = 1

    private const val TAG = "ProfilePersistence"

    private const val KEY_INITIALIZED = "profile_initialized"
    private const val KEY_COLLEGE = "college"
    private const val KEY_DEGREE = "degree"
    private const val KEY_GRADE = "grade"
    private const val KEY_INTERESTS = "interests"
    private const val KEY_REMINDERS = "reminders"
    private const val KEY_COURSES = "courses"
    private const val KEY_FOLLOWED = "followed_topic_ids"
    private const val KEY_BOOKMARKS = "bookmarked_post_ids"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 打开落盘文件。调用方（`UserProfileStore`）自己持有这个 prefs 实例，
     * 所以这里不再收 Context —— ViewModel 不该抓着 Context 不放。
     */
    fun open(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // MARK: - Keys

    /** 课表 key。绩点计算器另有自己的文件（[GpaPersistence]）。 */
    const val KEY_REMINDERS_LIST = KEY_REMINDERS
    const val KEY_COURSES_LIST = KEY_COURSES

    // MARK: - 读

    fun isInitialized(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_INITIALIZED, false)

    fun readStringList(
        prefs: SharedPreferences,
        key: String,
        fallback: List<String>,
    ): List<String> = decodeList(prefs, key, fallback, String.serializer())

    fun readReminders(prefs: SharedPreferences): List<tech.iflink.seuwiki.models.CampusReminder> =
        decodeList(prefs, KEY_REMINDERS, emptyList(), tech.iflink.seuwiki.models.CampusReminder.serializer())

    fun readCourses(prefs: SharedPreferences): List<tech.iflink.seuwiki.models.Course> =
        decodeList(prefs, KEY_COURSES, emptyList(), tech.iflink.seuwiki.models.Course.serializer())

    fun readProfileStrings(prefs: SharedPreferences): Triple<String?, String?, String?> {
        val p = prefs
        return Triple(
            p.getString(KEY_COLLEGE, null),
            p.getString(KEY_DEGREE, null),
            p.getString(KEY_GRADE, null),
        )
    }

    fun readInterests(prefs: SharedPreferences, fallback: List<String>): List<String> =
        readStringList(prefs, KEY_INTERESTS, fallback)

    fun readStringSet(prefs: SharedPreferences, key: String): Set<String> =
        prefs.getStringSet(key, emptySet()).orEmpty()

    // MARK: - 写

    fun markInitialized(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_INITIALIZED, true)
            .putInt(versionKey(KEY_INITIALIZED), SCHEMA_VERSION)
            .apply()
    }

    fun writeProfileStrings(
        prefs: SharedPreferences,
        college: String,
        degree: String,
        grade: String,
        interests: List<String>,
    ) {
        val editor = prefs.edit()
        editor.putString(KEY_COLLEGE, college)
        editor.putString(KEY_DEGREE, degree)
        editor.putString(KEY_GRADE, grade)
        editor.putString(KEY_INTERESTS, encode(interests, String.serializer()))
        bumpVersion(editor, KEY_COLLEGE)
        editor.apply()
    }

    fun writeStringList(prefs: SharedPreferences, key: String, value: List<String>) =
        persist(prefs, key, value, String.serializer())

    fun writeReminders(prefs: SharedPreferences, value: List<tech.iflink.seuwiki.models.CampusReminder>) =
        persist(prefs, KEY_REMINDERS, value, tech.iflink.seuwiki.models.CampusReminder.serializer())

    fun writeCourses(prefs: SharedPreferences, value: List<tech.iflink.seuwiki.models.Course>) =
        persist(prefs, KEY_COURSES, value, tech.iflink.seuwiki.models.Course.serializer())

    fun writeStringSet(prefs: SharedPreferences, key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    /** 清空全部 profile key（含初始化标记、版本号与损坏备份）。 */
    fun reset(prefs: SharedPreferences) {
        val editor = prefs.edit().clear()
        for (key in listOf(
            KEY_COLLEGE, KEY_DEGREE, KEY_GRADE, KEY_INTERESTS,
            KEY_REMINDERS, KEY_COURSES, KEY_FOLLOWED, KEY_BOOKMARKS,
        )) {
            editor.remove(key).remove(versionKey(key)).remove(corruptKey(key))
        }
        editor.apply()
    }

    // MARK: - 内部

    private fun <T> decodeList(
        prefs: SharedPreferences,
        key: String,
        fallback: List<T>,
        serializer: KSerializer<T>,
    ): List<T> {
        val prefs = prefs
        val raw = prefs.getString(key, null) ?: return fallback
        val version = storedVersion(prefs, key)
        if (version > SCHEMA_VERSION) {
            // 数据来自更新版本的 App，本版本解不了语义。不猜、不覆盖。
            Log.w(
                TAG,
                "$key 的落盘版本 v$version 高于本 App 的 v$SCHEMA_VERSION，" +
                    "已回落默认值（原值保留未动）",
            )
            return fallback
        }
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), raw)
        }.getOrElse { error ->
            backupCorrupt(prefs, key, raw, error)
            fallback
        }
    }

    private fun <T> persist(
        prefs: SharedPreferences,
        key: String,
        value: List<T>,
        serializer: KSerializer<T>,
    ) {
        val editor = prefs.edit()
        editor.putString(key, encode(value, serializer))
        bumpVersion(editor, key)
        editor.apply()
    }

    private fun <T> encode(value: List<T>, serializer: KSerializer<T>): String =
        json.encodeToString(ListSerializer(serializer), value)

    /**
     * 解码失败时保留原始值。
     *
     * **不覆盖原 key**：只写一份 `.corruptBackup`。直接覆盖等于把唯一的现场
     * 抹掉，模型一改用户的提醒就再也回不来了 —— 这正是 iOS 侧记下的问题。
     */
    private fun backupCorrupt(
        prefs: SharedPreferences,
        key: String,
        raw: String,
        error: Throwable,
    ) {
        if (prefs.getString(corruptKey(key), null) == null) {
            prefs.edit().putString(corruptKey(key), raw).apply()
        }
        Log.w(
            TAG,
            "$key 解码失败（${error::class.java.simpleName}），" +
                "原始值已备份到 ${corruptKey(key)}，本次回落默认值",
        )
    }

    private fun storedVersion(prefs: SharedPreferences, key: String): Int =
        prefs.getInt(versionKey(key), LEGACY_VERSION)

    private fun bumpVersion(editor: SharedPreferences.Editor, key: String) {
        editor.putInt(versionKey(key), SCHEMA_VERSION)
    }

    private fun versionKey(key: String) = "$key.schemaVersion"
    private fun corruptKey(key: String) = "$key.corruptBackup"
}