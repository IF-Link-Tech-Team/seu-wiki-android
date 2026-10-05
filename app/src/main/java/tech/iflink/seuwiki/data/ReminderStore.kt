package tech.iflink.seuwiki.data

import android.content.Context
import tech.iflink.seuwiki.models.CampusReminder

/**
 * 给**没有 ViewModel** 的地方读提醒用。
 *
 * [ReminderReceiver] 在后台被 `AlarmManager` 唤起时，系统不会帮我们准备
 * `UserProfileStore`（它是 ViewModel，要挂在 Activity 的 ViewModelStore 上）。
 * 但通知正文必须从磁盘读当前值 —— Intent extras 里的标题在改期后会停留在旧值
 * （见 [ReminderScheduler.firePendingIntent] 的注释）。
 *
 * 这里直接走 [ProfilePersistence] 读同一个 prefs 文件，与界面上那个 store 看到
 * 的是同一份数据，但**不复用它的实例**，也不碰任何 Compose 状态。
 */
internal object ReminderStore {

    fun read(context: Context): List<CampusReminder> =
        ProfilePersistence.readReminders(ProfilePersistence.open(context))
}