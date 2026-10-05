package tech.iflink.seuwiki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tech.iflink.seuwiki.data.ReminderScheduler

/**
 * **仅 DEBUG**：把系统广播原样转给 [ReminderReceiver]，用于在模拟器上实测
 * 「到点通知真的弹得出来」——`ReminderReceiver` 在 Release manifest 里是
 * `exported="false"`，adb 广播打不进去，无法端到端验证。
 *
 * Release 构建里这个类和 manifest 里的声明都不存在。
 */
class DebugFireReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val forward = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderScheduler.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, intent.getStringExtra(ReminderReceiver.EXTRA_REMINDER_ID))
        }
        context.sendBroadcast(forward)
    }
}
