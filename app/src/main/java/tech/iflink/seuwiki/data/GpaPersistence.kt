package tech.iflink.seuwiki.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 绩点计算器里已录入课程的落盘。
 *
 * 为什么需要它：原来这些课程只存在 `rememberSaveable` 里，那只在**进程内存 +
 * 配置变更**范围内有效 —— 退到后台被系统回收、或用户手动划掉 App，输入的
 * 十几个课程全部消失。iOS 端是持久化的（A-12），两端行为必须一致。
 *
 * 与 [ProfilePersistence] 用同一套约定：`schema_version` + 损坏时留
 * `.corruptBackup`，解码失败回落到空列表而不是把原始数据覆盖掉。
 */
internal object GpaPersistence {

    private const val TAG = "GpaPersistence"
    private const val PREFS_NAME = "seu_wiki_gpa"
    private const val KEY_COURSES = "courses"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val CORRUPT_SUFFIX = ".corruptBackup"

    /** 模型语义变化时 +1。v1 = 首版。 */
    const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    internal data class StoredCourse(
        val id: String = "",
        val name: String = "",
        val credits: String = "",
        val score: String = "",
    )

    @Serializable
    private data class StoredList(val courses: List<StoredCourse> = emptyList())

    fun read(context: Context): List<StoredCourse> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_COURSES, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(StoredList.serializer(), raw).courses
        } catch (e: Exception) {
            // 备份原始数据再回落空列表：直接覆盖会让用户再也回不来。
            Log.w(TAG, "绩点数据解码失败，已备份原始数据", e)
            prefs.edit()
                .putString(KEY_COURSES + CORRUPT_SUFFIX, raw)
                .remove(KEY_COURSES)
                .apply()
            emptyList()
        }
    }

    fun write(context: Context, courses: List<StoredCourse>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .putString(KEY_COURSES, json.encodeToString(StoredList.serializer(), StoredList(courses)))
            .apply()
    }

    fun schemaVersion(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCHEMA_VERSION, 0)
}
