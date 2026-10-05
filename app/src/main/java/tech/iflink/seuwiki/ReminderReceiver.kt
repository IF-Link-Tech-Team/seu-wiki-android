package tech.iflink.seuwiki

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.iflink.seuwiki.data.ReminderScheduler
import tech.iflink.seuwiki.data.ReminderStore
import java.util.concurrent.Executors

/**
 * 闹钟到点时真正发通知的落点。
 *
 * 对应 iOS 的 `UNUserNotificationCenterDelegate`：提醒被排上只是数据，真正响铃
 * 发生在系统把这条通知拉起来的那一刻。Android 上对应 [AlarmManager] 唤起本
 * Receiver。
 *
 * **正文从磁盘读，不从 Intent extras 读**：同一条提醒被改期时，系统替换
 * PendingIntent 时会保留旧的 Intent extras（见 [ReminderScheduler.firePendingIntent]
 * 的注释），extras 里的标题会停在改期前的样子。id 是稳定的，用它回
 * `UserProfileStore` 取当前值才对得上。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val appContext = context.applicationContext
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
        if (reminderId.isNullOrBlank()) {
            Log.w(TAG, "闹钟没有带 reminder id，丢弃")
            return
        }

        // 通知内容要读磁盘（SharedPreferences 是同步 IO），不能卡在 BroadcastReceiver
        // 的主线程里 —— 广播的 onReceive 只有约 10 秒，且前台广播会直接 ANR。
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.execute {
                try {
                    post(appContext, reminderId)
                } catch (e: Exception) {
                    Log.e(TAG, "发通知失败：${e.message}", e)
                } finally {
                    executor.shutdown()
                }
            }
        } finally {
            // 即使 executor 立刻结束也要保证 onReceive 返回
        }
    }

    private fun post(context: Context, reminderId: String) {
        ensureChannel(context)

        // 用户可能已经删了这条提醒（AlarmManager 里的旧闹钟还没撤干净），
        // 这种就不该再响。
        val reminder = ReminderStore.read(context).firstOrNull { it.id == reminderId }
        if (reminder == null) {
            Log.i(TAG, "提醒 $reminderId 已不在列表里，丢弃这条闹钟")
            return
        }

        // 授权检查。API 33+ 用户可以随时撤掉通知权限；没授权时 post 会抛
        // SecurityException，必须自己挡在前面，否则后台崩溃。
        if (!hasPermission(context)) {
            Log.w(TAG, "没有 POST_NOTIFICATIONS 权限，提醒 $reminderId 不会弹出")
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.reminder_channel_name))
            .setContentText(reminder.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(detailIntent(context, reminder.id, reminder.relatedItemId))
            .build()

        // 已被删掉的提醒不该在通知栏留残影：同一个 id 覆盖即可，
        // 用户删除时我们会主动 cancel。
        NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
        Log.i(TAG, "已发提醒通知：${reminder.title}")
    }

    /** 点通知回到 MainActivity，并带上要打开的资讯 id。 */
    private fun detailIntent(
        context: Context,
        reminderId: String,
        itemId: String?,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_OPEN_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_OPEN_ITEM_ID, itemId)
        }
        return PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val CHANNEL_ID = "seu_wiki_reminders"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_OPEN_ITEM_ID = "open_item_id"
        const val ACTION_OPEN_REMINDER = "tech.iflink.seuwiki.action.OPEN_REMINDER"

        /** 取消一条已发的通知，删除提醒时调用。 */
        fun cancelNotification(context: Context, reminderId: String) {
            NotificationManagerCompat.from(context).cancel(reminderId.hashCode())
        }

        private fun hasPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 建通知渠道。API 26+ 必须先有渠道才能发通知，重复创建是幂等的。
         *
         * IMPORTANCE_DEFAULT：不设全屏、不打断正在进行的通话 —— 提醒是通知，
         * 不是闹钟。用户要静音可以自己去设置里改。
         */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
            manager.createNotificationChannel(channel)
        }

        /**
         * 撤闹钟用的 PendingIntent。
         *
         * **必须与排程时构造出的 Intent 完全一致**（action / data / type）系统才会
         * 命中同一条记录并撤掉它；extras 不参与匹配。这里委托给
         * [ReminderScheduler]，避免两边各写一份、改了一边就撤不掉。
         */
        fun pendingIntent(context: Context, reminderId: String): PendingIntent =
            ReminderScheduler.cancelPendingIntent(context, reminderId)
    }
}