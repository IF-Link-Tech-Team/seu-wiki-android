package tech.iflink.seuwiki.ui

import android.content.Context
import androidx.annotation.StringRes
import tech.iflink.seuwiki.R
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 相对时间与日期的格式化。
 *
 * iOS 侧用的是 `Text(date, style: .relative)`，它跟着设备语言走 —— 在英文模拟器上
 * 会渲染成 "5 days, 10 hr"，两端读数就不一致了。所以这里显式输出中文，刻意
 * **不**跟随设备 locale，保证同一时刻两端显示同一句话。
 *
 * 词条本身放在 `strings.xml`（`time_*` / `date_*`），本类只负责挑选量级并拼参数。
 *
 * 真要支持多语言时，这里的正确做法是改走 `DateUtils` / `DateFormat` 并跟随设备
 * locale，同时把 iOS 侧的 `.relative` 换成同样的量级策略，两端一起改。
 *
 * 需要 [Context] 是因为 `stringResource` 只能在 composable 里调用，而量级判断
 * （该显示「分钟」还是「天」）是纯逻辑、还要被非 composable 的地方复用。
 */
object Format {

    /** e.g. `8 天后`, `今天`, `3 小时前`。 */
    fun relative(context: Context, ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null) return ""
        val delta = ms - now
        val future = delta > 0
        val abs = kotlin.math.abs(delta)

        val minutes = TimeUnit.MILLISECONDS.toMinutes(abs)
        val hours = TimeUnit.MILLISECONDS.toHours(abs)
        val days = TimeUnit.MILLISECONDS.toDays(abs)

        if (!future && minutes < 1) return context.getString(R.string.time_just_now)

        @StringRes val bodyRes: Int
        val bodyArgs: List<Any>
        when {
            minutes < 60 -> { bodyRes = R.string.time_minutes; bodyArgs = listOf(minutes) }
            hours < 24 -> { bodyRes = R.string.time_hours; bodyArgs = listOf(hours) }
            days < 30 -> { bodyRes = R.string.time_days; bodyArgs = listOf(days) }
            days < 365 -> { bodyRes = R.string.time_months; bodyArgs = listOf(days / 30) }
            else -> { bodyRes = R.string.time_years; bodyArgs = listOf(days / 365) }
        }
        val body = context.resources.getString(bodyRes, *bodyArgs.toTypedArray())
        return context.getString(
            if (future) R.string.time_after else R.string.time_before,
            body,
        )
    }

    /** `2026年10月5日` */
    fun date(context: Context, ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return context.getString(
            R.string.date_full,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** `10月5日 14:30` —— 小时不补零、分钟补两位，与 iOS 的 `timeText(_:)` 一致。 */
    fun dateTime(context: Context, ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return context.getString(
            R.string.date_time_short,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    /** `9:05` —— 小时不补零、分钟补两位，与 iOS 的 `timeText(_:)` 一致。 */
    fun clock(hour: Int, minute: Int): String =
        "$hour:${minute.toString().padStart(2, '0')}"
}
