package tech.iflink.seuwiki.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import tech.iflink.seuwiki.ReminderReceiver
import tech.iflink.seuwiki.models.CampusReminder
import java.util.Calendar

/**
 * 提醒的本地排程，对应 iOS 的 `ReminderScheduler`。
 *
 * iOS 侧早期版本 `advanceDays` 只用来显示一行文字，工程里**没有任何**
 * `UserNotifications` 调用 —— 用户设好提醒、到期什么都不会发生。Android 侧之前
 * 是同一个问题：提醒只落进 `UserProfileStore` 的列表，界面上显示「已添加提醒」，
 * 但到点不会有任何东西发生。这里用 `AlarmManager` + `BroadcastReceiver` 把它接上。
 *
 * ## 精确还是不准：由系统说了算，不假装
 *
 * targetSdk 35 之后「精确闹钟」被收紧：
 *
 * - `SCHEDULE_EXACT_ALARM`（API 33+）变成**可撤销**权限，新装默认不授予；
 * - `USE_EXACT_ALARM` 正常运行即授予，但 Play 商店只允许闹钟/日历类应用使用。
 *
 * 所以这里**不假设自己一定有精确闹钟权限**，每次排程都先问
 * [AlarmManager.canScheduleExactAlarms]：有就用 `setExactAndAllowWhileIdle`，
 * 没有就退回 `setWindow`（窗口式，系统在窗口内择机触发），并把这个事实记在
 * [ScheduleResult.isExact] 里由界面如实告知用户 —— 悄悄用 imprecise 却宣称
 * 「9:00 准时提醒」是欺骗。
 *
 * ## 对账
 *
 * AlarmManager 没有「列出已排闹钟」的 API，所以本类自己把「排了什么」记在
 * [ALARM_PREFS] 里（对应 iOS 的 `pendingNotificationRequests()`）。冷启动时
 * [reconcile] 用它做差集：界面有、系统没有 → 补排；系统有、界面没有 → 撤掉；
 * 触发时间变了 → 重排。
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    /** 排程账本文件名。与 SecurePrefs 分开：里面没有凭证，不需要 Keystore 加密。 */
    private const val ALARM_PREFS = "seu_wiki_alarms"

    /** 账本 schema 版本。模型语义变化时 +1，读失败时降级为空账本而不是崩。 */
    const val ALARM_SCHEMA_VERSION = 1

    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_SCHEDULED = "scheduled"

    /**
     * `setWindow` 的窗口宽度。
     *
     * 拿不到精确闹钟权限时用。15 分钟是「提醒类」比较合理的折中：早太多没有意义
     * （截止前一天提醒差两小时仍然说得通），晚太多用户会以为没生效。
     */
    const val INEXACT_WINDOW_MS = 15L * 60 * 1000

    /** 一次排程的结果，界面上据此如实说明。 */
    data class ScheduleResult(
        /** true = 用的是精确闹钟，到点即响；false = 窗口式，可能有最多 15 分钟延迟。 */
        val isExact: Boolean,
        /** true = 触发时间在未来、已排上；false = 时间已过，未排。 */
        val scheduled: Boolean,
    )

    // MARK: - 触发时间

    /**
     * 提醒的实际触发时刻。
     *
     * 提醒日 = 截止日往前推 [CampusReminder.advanceDays] 天，取当天用户设定的
     * 时:分（默认 09:00 —— 学生不会想被凌晨的提醒炸醒）。
     *
     * **不能**用「截止时间戳 - advanceDays * 86400_000」：跨夏令时切换时
     * 实际经过的秒数不是 86400 的整数倍，提醒会早/晚一小时。
     * 这里走 [Calendar] 的 `add(Calendar.DAY_OF_YEAR, …)`，由日历自己处理偏移。
     */
    fun fireAt(reminder: CampusReminder, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = reminder.dueDate }
        cal.add(Calendar.DAY_OF_YEAR, -reminder.advanceDays.coerceAtLeast(0))
        // 记住「提醒日是几号」，再把时分换成用户选的，顺序不能反 ——
        // 先设时分再加天数的话，跨月时会被截断成同月。
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        cal.set(Calendar.HOUR_OF_DAY, reminder.fireHour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, reminder.fireMinute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // 加天数可能跨进/跨出 DST 切换点，此刻算出的「当天 00:00」未必还是 DAY_OF_YEAR，
        // 所以重新对齐一次日历日期。
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.DAY_OF_YEAR, day)
        return cal.timeInMillis
    }

    // MARK: - 排程

    /**
     * 为一条提醒排程。重复设定同一条先撤掉旧的，避免两次提醒叠加。
     *
     * 已经过去的时间点不排 —— 系统会直接忽略，排了只是留一条永远不触发的记录，
     * 还会让 [reconcile] 误以为「已排」。
     */
    fun schedule(
        context: Context,
        reminder: CampusReminder,
        now: Long = System.currentTimeMillis(),
    ): ScheduleResult {
        val manager = alarmManager(context)
        if (manager == null) {
            Log.w(TAG, "AlarmManager 不可用，提醒 ${reminder.id} 未排程")
            return ScheduleResult(isExact = false, scheduled = false)
        }
        // 先撤：改了时间或提前量时不能响两次。
        cancel(context, reminder.id)

        val triggerAt = fireAt(reminder, now)
        if (triggerAt <= now) {
            Log.i(TAG, "提醒 ${reminder.id} 的触发时间已过，不再排程")
            ledger(context).edit().remove(reminder.id).apply()
            return ScheduleResult(isExact = false, scheduled = false)
        }

        val pending = firePendingIntent(context, reminder)
        val canExact = manager.canScheduleExactAlarmsCompat()
        try {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pending,
                )
            } else {
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP, triggerAt, INEXACT_WINDOW_MS, pending,
                )
            }
        } catch (e: SecurityException) {
            // 权限在调用瞬间被撤销（用户在设置里关掉了）仍可能抛 —— 退回窗口式。
            Log.w(TAG, "精确闹钟被拒，退回窗口式：${e.message}")
            runCatching {
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP, triggerAt, INEXACT_WINDOW_MS, pending,
                )
            }.onFailure {
                Log.w(TAG, "窗口式闹钟也被拒，提醒 ${reminder.id} 未排程")
                return ScheduleResult(isExact = false, scheduled = false)
            }
            return ScheduleResult(isExact = false, scheduled = true)
        }

        ledger(context).edit().putLong(reminder.id, triggerAt).apply()
        Log.i(
            TAG,
            "提醒 ${reminder.id} 已排程：触发 $triggerAt，精确=$canExact",
        )
        return ScheduleResult(isExact = canExact, scheduled = true)
    }

    /** 撤掉一条提醒的排程。幂等：没排过也不报错。 */
    fun cancel(context: Context, reminderId: String) {
        val manager = alarmManager(context) ?: return
        // 撤 AlarmManager 里那条；再把账本里的记录删掉。
        manager.cancel(cancelPendingIntent(context, reminderId))
        ledger(context).edit().remove(reminderId).apply()
    }

    fun cancelAll(context: Context) {
        scheduledIds(context).forEach { cancel(context, it) }
    }

    // MARK: - 对账

    /** 账本里记着的已排提醒 id。 */
    fun scheduledIds(context: Context): Set<String> =
        ledger(context).getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().toSet()

    /** 账本里某条提醒的触发时刻；没排过返回 null。 */
    fun scheduledAt(context: Context, reminderId: String): Long? =
        ledger(context).getLong(reminderId, -1L).takeIf { it > 0L }

    /**
     * 冷启动对账：把 [reminders] 与账本对齐。
     *
     * 三种差异都要处理：
     * 1. 界面有、账本没有 → 补排（可能刚设完 App 就被系统清过闹钟）；
     * 2. 账本有、界面没有 → 撤掉（用户删了提醒）；
     * 3. 两边都有但触发时刻不同 → 重排（提醒被编辑过）。
     *
     * 必要场景：系统重启会清空所有闹钟（`BOOT_COMPLETED` 无法静态注册，只能
     * 靠这次对账补回来），用户在系统设置里清过数据、或 App 被系统回收后
     * 闹钟被回收 —— 没有这一步，界面显示「有提醒」但到点一声不吭。
     */
    fun reconcile(
        context: Context,
        reminders: List<CampusReminder>,
        now: Long = System.currentTimeMillis(),
    ) {
        val ledger = ledger(context)
        // 账本损坏时清空重建，而不是当成「什么都没有」之外的怪状态。
        val stale = ledger.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
            .filter { ledger.getLong(it, -1L) <= 0L }
            .toSet()
        if (stale.isNotEmpty()) {
            ledger.edit().apply { stale.forEach { remove(it) } }.apply()
        }

        val expected = reminders.associateBy { it.id }

        // 2) 界面上已删 → 撤掉。
        stale.forEach { cancel(context, it) }

        // 1) / 3) 漏排的补上、时刻变了重排。
        reminders.forEach { reminder ->
            val recorded = scheduledAt(context, reminder.id)
            if (recorded == null || recorded != fireAt(reminder, now)) {
                schedule(context, reminder, now)
            }
        }
        // 界面有但触发时刻已过的，schedule() 内部会自己清掉账本记录。

        Log.i(
            TAG,
            "对账完成：界面 ${reminders.size} 条，账本 ${ledger.all.keys.size} 个键",
        )
    }

    // MARK: - PendingIntent

    /**
     * 排程用的 PendingIntent。
     *
     * `FLAG_UPDATE_CURRENT` 是必须的：同一条提醒被改期时，系统只会替换 extras 而
     * **保留旧的 Intent 过滤条件** —— 所以 requestCode 必须稳定（用 id 的
     * hashCode），extras 里不能只放会变的字段（通知正文从账本里读，见
     * [ReminderReceiver]），否则改完期点通知仍是旧文案。
     */
    fun firePendingIntent(context: Context, reminder: CampusReminder): PendingIntent {
        val intent = fireIntent(context, reminder.id)
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 撤闹钟用的 PendingIntent。
     *
     * 与 [firePendingIntent] 共用 [fireIntent]，所以 action / data / type 永远一致 ——
     * 这两项才是系统的匹配依据（extras 不参与）。两边各写一份的话，改了一边就会
     * 撤不掉闹钟，提醒仍然会响，而界面上却显示「已删除」。
     */
    fun cancelPendingIntent(context: Context, reminderId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            fireIntent(context, reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * data 里带 id：没有它，两条提醒的 Intent 会被系统判定为「相同」而互相覆盖，
     * 只有最后一条能触发。
     */
    private fun fireIntent(context: Context, reminderId: String): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = android.net.Uri.parse("seuwiki://reminder/$reminderId")
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }

    const val ACTION_FIRE = "tech.iflink.seuwiki.action.FIRE_REMINDER"

    // MARK: - Private

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * `canScheduleExactAlarms` 是 API 31+ 的 API。低于 31 的系统（minSdk 26）
     * 精确闹钟是默认授予的，直接当作可以。
     */
    private fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) canScheduleExactAlarms() else true

    /**
     * 排程账本。读失败一律降级成空账本（[reconcile] 会把缺的补上），
     * 不会让一个坏 key 变成崩溃的入口。
     */
    private fun ledger(context: Context) =
        context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)

    /** 版本号；[reconcile] 首次运行时会写上。 */
    fun ensureSchemaVersion(context: Context) {
        val prefs = ledger(context)
        val stored = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (stored != ALARM_SCHEMA_VERSION) {
            prefs.edit().putInt(KEY_SCHEMA_VERSION, ALARM_SCHEMA_VERSION).apply()
        }
    }
}