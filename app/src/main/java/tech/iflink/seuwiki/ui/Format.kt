package tech.iflink.seuwiki.ui

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Chinese relative-date formatting.
 *
 * The iOS build uses `Text(date, style: .relative)`, which localizes from the
 * device locale — on the simulator that rendered "5 days, 10 hr". Since every
 * string in this app is Chinese, the same formatting is done explicitly here so
 * the two platforms read the same regardless of device language.
 */
object Format {

    /** e.g. `8 天后`, `今天`, `3 小时前`. */
    fun relative(ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null) return ""
        val delta = ms - now
        val future = delta > 0
        val abs = kotlin.math.abs(delta)

        val minutes = TimeUnit.MILLISECONDS.toMinutes(abs)
        val hours = TimeUnit.MILLISECONDS.toHours(abs)
        val days = TimeUnit.MILLISECONDS.toDays(abs)

        val body = when {
            minutes < 1 -> return "刚刚"
            minutes < 60 -> "$minutes 分钟"
            hours < 24 -> "$hours 小时"
            days < 30 -> "$days 天"
            days < 365 -> "${days / 30} 个月"
            else -> "${days / 365} 年"
        }
        return when {
            !future && minutes < 1 -> "刚刚"
            !future -> "${body}前"
            else -> "${body}后"
        }
    }

    /** `2026年10月5日` */
    fun date(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** `10月5日 14:30` */
    fun dateTime(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 " +
            "${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE).toString().padStart(2, '0')}"
    }

    /** `1,893` — the grouped form the iOS `Text("\(count)")` shows under a locale. */
    fun count(n: Int): String {
        val s = n.toString()
        if (s.length <= 3) return s
        return s.reversed().chunked(3).joinToString(",").reversed()
    }

    /** `9:05` — 小时不补零、分钟补两位，与 iOS 的 `timeText(_:)` 一致。 */
    fun clock(hour: Int, minute: Int): String =
        "$hour:${minute.toString().padStart(2, '0')}"
}
