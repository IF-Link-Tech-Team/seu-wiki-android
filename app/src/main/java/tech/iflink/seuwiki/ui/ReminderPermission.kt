package tech.iflink.seuwiki.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 通知权限状态。
 *
 * `POST_NOTIFICATIONS` 是 **API 33+ 的运行时权限**：用户在第一次用到时才弹框，
 * 而且随时可以去系统设置里撤掉。撤掉之后 [tech.iflink.seuwiki.ReminderReceiver]
 * 发通知会被系统静默丢弃 —— 闹钟照样触发，但通知栏里什么都不会出现。
 *
 * 所以这条状态必须**显式查、显式讲**，不能假设「设了提醒就等于会有通知」。
 */
object ReminderPermission {

    /**
     * 本机当前是否允许发通知。
     *
     * API 33 以下不需要运行时权限，一律当作已授权。
     */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 精确闹钟是否被授予。
     *
     * API 31+ 的 `SCHEDULE_EXACT_ALARM` 是**可撤销**权限，新装默认不授予；
     * 没授予时排程只能退回窗口式（见 [tech.iflink.seuwiki.data.ReminderScheduler]）。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        return manager?.canScheduleExactAlarms() ?: false
    }

    /** 打开系统「通知」设置页，让用户自己开权限。 */
    fun openNotificationSettings(context: Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
    }

    /** 打开系统「闹钟和提醒」设置页，让用户给精确闹钟权限。 */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        runCatching { context.startActivity(intent) }
    }
}