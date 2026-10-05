package tech.iflink.seuwiki.models

import kotlinx.serialization.Serializable

/** A course in the timetable; shared by the Tools grid and the Home bento. */
@Serializable
data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    /** 1 = Monday … 7 = Sunday, matching the iOS `weekday` convention. */
    val weekday: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
) {
    /** e.g. `10:00–11:40`. */
    val timeRangeText: String
        get() = "${fmt(startHour, startMinute)}–${fmt(endHour, endMinute)}"

    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute

    private fun fmt(h: Int, m: Int) = "$h:${m.toString().padStart(2, '0')}"
}

/** A deadline reminder the user set from a notice detail page. */
@Serializable
data class CampusReminder(
    val id: String,
    val title: String,
    val dueDate: Long,
    val advanceDays: Int = 1,
    val note: String = "",
    val relatedItemId: String? = null,
) {
    val daysRemaining: Int
        get() {
            val dayMs = 24L * 60 * 60 * 1000
            val todayStart = startOfDay(nowMs())
            val dueStart = startOfDay(dueDate)
            return ((dueStart - todayStart) / dayMs).toInt()
        }

    companion object {
        fun nowMs(): Long = System.currentTimeMillis()

        fun startOfDay(ms: Long): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = ms
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

/** A Tools-grid entry. [iconKey] is an SF Symbol name, resolved by `SeuIcons`. */
data class ToolItem(
    val id: String,
    val name: String,
    val iconKey: String,
    val tintKey: String,
    val subtitle: String,
)

/** A handbook document node: section → entries. */
data class HandbookSection(
    val id: String,
    val name: String,
    val iconKey: String,
    val entries: List<HandbookEntry>,
)

data class HandbookEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val updatedAt: Long,
)
