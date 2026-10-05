package tech.iflink.seuwiki.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import tech.iflink.seuwiki.data.GpaPersistence
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SectionHeader
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.Course
import tech.iflink.seuwiki.models.ToolCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.VSpace
import java.util.Calendar
import java.util.Locale
import java.util.UUID

// --- 课表 -----------------------------------------------------------------

/** 周一 … 周日 — index 0 is 1 = 周一, matching the iOS `Weekday.all` order. */
private val WeekdayTitles = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 今天是周几.
 *
 * The iOS `Weekday.today` rule: `Calendar.component(.weekday)` counts Sunday as
 * 1, which is remapped onto the 1 = 周一 … 7 = 周日 index that `Course.weekday`
 * uses — the same mapping `UserProfileStore` applies.
 */
private fun todayWeekdayIndex(): Int {
    val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return if (dow == Calendar.SUNDAY) 7 else dow - 1
}

/**
 * 「10月5日 · 周日」.
 *
 * Mirrors the Swift `selectedDateText`: today shifted by
 * `selectedWeekday - Weekday.today` days, formatted by hand so the caption stays
 * Chinese on every device locale, as the original comment requires.
 */
private fun selectedDateText(selected: Int, today: Int): String {
    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, selected - today) }
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    return "${month}月${day}日 · ${WeekdayTitles[selected - 1]}"
}

/** `10:00` — the iOS `timeText(_:)`, hour unpadded and minute zero-padded. */
private fun clockText(hour: Int, minute: Int): String = "$hour:${minute.toString().padStart(2, '0')}"

/**
 * 课表.
 *
 * Port of `TimetableView`: a weekday capsule console that opens on today, the
 * selected day's courses stacked by start time, and a 「回到今天」 toolbar item
 * that only exists while another day is selected. Courses come from the same
 * `profile.courses` the Home bento reads.
 */
@Composable
fun TimetableScreen(
    profile: UserProfileStore,
    onBack: () -> Unit,
) {
    val colors = SeuTheme.colors
    val today = remember { todayWeekdayIndex() }
    var selected by rememberSaveable { mutableStateOf(today) }
    val dayCourses = profile.courses
        .filter { it.weekday == selected }
        .sortedBy { it.startMinutes }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = "课表",
                onBack = onBack,
                trailing = {
                    // `.toolbar` shows the item only when the selection has moved
                    // away from today, so it carries no state of its own.
                    if (selected != today) {
                        Text(
                            text = "回到今天",
                            style = SeuType.Subheadline,
                            color = colors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selected = today }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                },
            )

            ConsoleBar(
                items = (1..7).toList(),
                selection = selected,
                onSelect = { selected = it },
                title = { WeekdayTitles[it - 1] },
            )

            if (dayCourses.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    EmptyStateView(
                        title = if (selected == today) "今天没课" else "这天没课",
                        description = "好好休息，或切换到其他日期查看",
                        icon = {
                            Icon(
                                imageVector = SeuIcons.of("calendar.day.timeline.left"),
                                contentDescription = null,
                                tint = colors.tertiaryLabel,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = ListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "date") { DaySummaryRow(selected, today, dayCourses.size) }
                    items(dayCourses, key = { it.id }) { course -> CourseCard(course) }
                }
            }
        }
    }
}

/** The 「10月5日 · 周日 / N 节课」 header above the day's cards. */
@Composable
private fun DaySummaryRow(selected: Int, today: Int, count: Int) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selectedDateText(selected, today),
            style = SeuType.SubheadlineMedium,
            color = colors.label,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count 节课",
            style = SeuType.Caption,
            color = colors.secondaryLabel,
        )
    }
}

/**
 * One course: a fixed-width start / rule / end column beside the name, teacher
 * and room — the iOS `CourseCard` layout.
 */
@Composable
private fun CourseCard(course: Course) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier.cardStyle(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.width(44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = clockText(course.startHour, course.startMinute),
                style = SeuType.SubheadlineSemibold,
                color = colors.label,
            )
            // `RoundedRectangle(cornerRadius: 1).frame(width: 2, height: 14)`
            // filled with `.quaternary`, the faintest role this scheme carries.
            Box(
                Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .clip(ContinuousRoundedShape(1.dp))
                    .background(colors.tertiaryFill),
            )
            Text(
                text = clockText(course.endHour, course.endMinute),
                style = SeuType.Caption,
                color = colors.secondaryLabel,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = course.name,
                style = SeuType.Headline,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `Label(_:systemImage:)` used the `person` and `mappin` symbols;
                // neither has a SeuIcons entry, so the Material equivalents are
                // referenced directly, as FeedScreen does for its check glyph.
                // 两个 Label 都要能被压缩，否则教师名长时会把地点挤成 0 宽。
                MetaLabel(Icons.Outlined.Person, course.teacher, Modifier.weight(1f))
                MetaLabel(Icons.Outlined.Place, course.location, Modifier.weight(1f))
            }
        }
    }
}

/** The caption `Label` pair under a course name. */
@Composable
private fun MetaLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = text,
            style = SeuType.Caption,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- 绩点计算 -------------------------------------------------------------

/**
 * One row of the calculator.
 *
 * Credits and score are kept as the raw text the user typed, exactly like the
 * iOS `GPACourse`; anything that fails to parse is simply left out of the
 * summary so a half-typed row never interrupts entry.
 */
private data class GpaCourse(
    val id: String,
    val name: String,
    val credits: String,
    val score: String,
) {
    /** Parsed credits — only a value above 0 counts. */
    val creditsValue: Double?
        get() = credits.trim().toDoubleOrNull()?.takeIf { it > 0.0 }

    /** Parsed 百分制 score — only 0–100 counts. */
    val scoreValue: Double?
        get() = score.trim().toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

    val gradePoint: Double? get() = scoreValue?.let { gpaPointFor(it) }

    /** A parseable but out-of-range score, e.g. 108 — shown inline. */
    val hasScoreError: Boolean
        get() {
            val value = score.trim().toDoubleOrNull() ?: return false
            return value !in 0.0..100.0
        }

    /** A parseable but non-positive credit value, e.g. 0 — shown inline. */
    val hasCreditsError: Boolean
        get() {
            val value = credits.trim().toDoubleOrNull() ?: return false
            return value <= 0.0
        }
}

/**
 * 五分制绩点换算.
 *
 * Verbatim port of the iOS `GPAGradingScale.point(for:)` ladder: 90–100 is 5.0
 * and every further 5-point band drops 0.5, with anything under 60 scoring 0.
 * The rule mirrors the in-app handbook entry 「绩点计算规则」 (MockData h2e2); the
 * footnote on the screen discloses that assumption to the user.
 */
private fun gpaPointFor(score: Double): Double = when {
    score >= 90.0 -> 5.0
    score >= 85.0 -> 4.5
    score >= 80.0 -> 4.0
    score >= 75.0 -> 3.5
    score >= 70.0 -> 3.0
    score >= 65.0 -> 2.5
    score >= 60.0 -> 2.0
    else -> 0.0
}

/** The weighted totals shown in the summary card; all zero when no row is valid. */
private data class GpaSummary(
    val totalCredits: Double,
    val averageScore: Double,
    val gradePoint: Double,
    val countedCourses: Int,
) {
    val gradePointText: String get() = String.format(Locale.CHINA, "%.2f", gradePoint)
    val averageScoreText: String get() = String.format(Locale.CHINA, "%.1f", averageScore)

    /** `%g` so 3 credits print as `3` and 6.5 as `6.5`, as on iOS. */
    val totalCreditsText: String get() = String.format(Locale.CHINA, "%g", totalCredits)
}

/** Σ学分×绩点 / Σ学分 over the rows whose credits and score both parse. */
private fun gpaSummaryOf(courses: List<GpaCourse>): GpaSummary {
    var totalCredits = 0.0
    var pointSum = 0.0
    var scoreSum = 0.0
    var counted = 0
    courses.forEach { course ->
        val credits = course.creditsValue ?: return@forEach
        val score = course.scoreValue ?: return@forEach
        totalCredits += credits
        pointSum += credits * gpaPointFor(score)
        scoreSum += credits * score
        counted++
    }
    return GpaSummary(
        totalCredits = totalCredits,
        averageScore = if (totalCredits > 0) scoreSum / totalCredits else 0.0,
        gradePoint = if (totalCredits > 0) pointSum / totalCredits else 0.0,
        countedCourses = counted,
    )
}

/** A fresh, empty row — the iOS `GPACourse()` default initialiser. */
private fun newGpaCourse(): GpaCourse =
    GpaCourse(id = UUID.randomUUID().toString(), name = "", credits = "", score = "")

/**
 * 绩点计算.
 *
 * Port of `GPACalculatorView`: a large weighted grade point over the 总学分 /
 * 加权平均分 / 计入课程 trio, an add-and-remove course list, and the 五分制换算
 * reference with the assumption footnote. Android has no form `EditButton`, so
 * every row carries an explicit trailing ✕ instead of a swipe-to-delete.
 */
@Composable
fun GPACalculatorScreen(onBack: () -> Unit) {
    val colors = SeuTheme.colors
    val context = LocalContext.current

    // 原来只有 rememberSaveable：退到后台被回收、或用户划掉 App，输入的十几个
    // 课程全没了。iOS 端是持久化的（A-12），两端必须一致 —— 首次从磁盘读，
    // 之后每次变更都写回。
    var courses by remember {
        mutableStateOf(
            GpaPersistence.read(context).map {
                GpaCourse(id = it.id, name = it.name, credits = it.credits, score = it.score)
            }
        )
    }
    LaunchedEffect(courses) {
        GpaPersistence.write(
            context,
            courses.map { GpaPersistence.StoredCourse(it.id, it.name, it.credits, it.score) },
        )
    }
    val summary = gpaSummaryOf(courses)

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "绩点计算", onBack = onBack)

            if (courses.isEmpty()) {
                // The SwiftUI empty state puts the action inside the
                // `ContentUnavailableView` actions block, so the button shares
                // the centred stack rather than sitting at the leading edge.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyStateView(
                        title = "还没有课程",
                        description = "添加课程的成绩与学分，自动按五分制换算绩点",
                        icon = {
                            Icon(
                                imageVector = SeuIcons.of("percent"),
                                contentDescription = null,
                                tint = colors.tertiaryLabel,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                    )
                    Button(
                        onClick = { courses = listOf(newGpaCourse()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .padding(top = 20.dp),
                    ) {
                        Text("添加课程", style = SeuType.Headline)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = ListBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item(key = "summary") { SummaryCard(summary) }

                    item(key = "courses") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeader(title = "课程", actionLabel = null)
                            CardColumn(padding = 14.dp, spacing = 12.dp) {
                                courses.forEachIndexed { index, course ->
                                    if (index > 0) InsetDivider(leading = 0.dp)
                                    GpaCourseRow(
                                        course = course,
                                        onChange = { updated ->
                                            courses = courses.map { if (it.id == updated.id) updated else it }
                                        },
                                        onRemove = { courses = courses.filterNot { it.id == course.id } },
                                    )
                                }
                                AddCourseAction { courses = courses + newGpaCourse() }
                            }
                        }
                    }

                    item(key = "rules") { RulesCard() }
                }
            }
        }
    }
}

/**
 * The summary card: 48pt bold green grade point over 加权平均绩点, then the
 * three-column stat row with 28pt hairline dividers — the iOS `SummaryCard`.
 */
@Composable
private fun SummaryCard(summary: GpaSummary) {
    val colors = SeuTheme.colors
    CardColumn(spacing = 14.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // `.system(size: 48, weight: .bold)`; CountdownLarge is the app's
            // 34pt bold token, widened to the size the summary needs.
            Text(
                text = summary.gradePointText,
                style = SeuType.CountdownLarge.copy(fontSize = 48.sp),
                color = colors.green,
            )
            Text(
                text = "加权平均绩点",
                style = SeuType.Caption,
                color = colors.secondaryLabel,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryStat(summary.totalCreditsText, "总学分", Modifier.weight(1f))
            StatDivider()
            SummaryStat(summary.averageScoreText, "加权平均分", Modifier.weight(1f))
            StatDivider()
            SummaryStat("${summary.countedCourses} 门", "计入课程", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryStat(value: String, title: String, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = SeuType.Title3, color = colors.label, maxLines = 1)
        Text(title, style = SeuType.Caption2, color = colors.secondaryLabel, maxLines = 1)
    }
}

/** `Divider().frame(height: 28)` between the summary stats. */
@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(SeuTheme.colors.separator),
    )
}

/** One course row: optional name, 学分 / 成绩, and the live 绩点 readout. */
@Composable
private fun GpaCourseRow(
    course: GpaCourse,
    onChange: (GpaCourse) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = SeuTheme.colors
    val gradePointText = course.gradePoint?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "—"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GpaField(
                placeholder = "课程名称（选填）",
                value = course.name,
                onValueChange = { onChange(course.copy(name = it)) },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = SeuIcons.of("xmark"),
                contentDescription = "删除课程",
                tint = colors.tertiaryLabel,
                modifier = Modifier
                    .size(28.dp)
                    .clip(ContinuousRoundedShape(14.dp))
                    .clickable(onClick = onRemove)
                    .padding(6.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GpaField(
                label = "学分",
                placeholder = "如 3.0",
                value = course.credits,
                onValueChange = { onChange(course.copy(credits = it)) },
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            GpaField(
                label = "成绩",
                placeholder = "0 – 100",
                value = course.score,
                onValueChange = { onChange(course.copy(score = it)) },
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("绩点", style = SeuType.Caption2, color = colors.secondaryLabel)
                Text(
                    text = gradePointText,
                    style = SeuType.Headline,
                    // Green once the score converts, secondary while it is still
                    // unparseable — the iOS `Color.secondary : Color.green` swap.
                    color = if (course.gradePoint == null) colors.secondaryLabel else colors.green,
                )
            }
        }

        val errorText = when {
            course.hasScoreError -> "成绩需在 0–100 之间"
            course.hasCreditsError -> "学分需大于 0"
            else -> null
        }
        if (errorText != null) {
            Text(errorText, style = SeuType.Caption2, color = colors.red)
        }
    }
}

/** A labelled single-line field, the iOS `TextField` row. */
@Composable
private fun GpaField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = SeuTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) {
            Text(label, style = SeuType.Caption2, color = colors.secondaryLabel)
        }
        Box {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = SeuType.Body,
                    color = colors.tertiaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(SeuType.Body.copy(color = colors.label)),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The full-width 「添加课程」 button at the foot of the course list. */
@Composable
private fun AddCourseAction(onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("添加课程", style = SeuType.Body, color = colors.accent)
    }
}

/** The 五分制换算 reference table and the source footnote. */
@Composable
private fun RulesCard() {
    val colors = SeuTheme.colors
    // The iOS `rulesGrid` fills each GridRow left to right, so the pairs read
    // 90–100 / 70–74, then 85–89 / 65–69, and so on down the two columns.
    val rules = listOf(
        "90–100" to "5.0",
        "70–74" to "3.0",
        "85–89" to "4.5",
        "65–69" to "2.5",
        "80–84" to "4.0",
        "60–64" to "2.0",
        "75–79" to "3.5",
        "60 以下" to "0",
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "五分制换算", actionLabel = null)
        CardColumn(padding = 14.dp, spacing = 8.dp) {
            rules.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { (range, point) ->
                        Rule(range, point, Modifier.weight(1f))
                    }
                }
            }
        }
        Text(
            text = "换算规则依据 app 内手册「绩点计算规则」：90–100 为 5.0，此后每 5 分一档递减 0.5，" +
                "60 以下为 0。未检索到东南大学官方公开的换算文件，此处为假设规则，实际以教务处最新规定为准。",
            style = SeuType.Footnote,
            color = colors.secondaryLabel,
        )
    }
}

/** One `rule(_:_:)` cell: range in secondary on the left, point semibold right. */
@Composable
private fun Rule(range: String, point: String, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Row(modifier = modifier) {
        Text(range, style = SeuType.Subheadline, color = colors.secondaryLabel, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(
            text = point,
            style = SeuType.SubheadlineSemibold,
            color = colors.label,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/**
 * Remembers the entered courses across tab switches by encoding them as one
 * string, mirroring `FeedFilterSaver`. `|` separates courses and `,` their
 * fields, so both separators are folded to spaces in the free-text name before
 * saving — a typed comma can then never shift a field on restore.
 */
private val GpaCourseListSaver = Saver<List<GpaCourse>, String>(
    save = { courses ->
        courses.joinToString("|") { course ->
            listOf(
                course.id,
                course.name.replace(',', ' ').replace('|', ' '),
                course.credits,
                course.score,
            ).joinToString(",")
        }
    },
    restore = { raw ->
        if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split("|").map { record ->
                val parts = record.split(",")
                GpaCourse(
                    id = parts.getOrNull(0).orEmpty(),
                    name = parts.getOrNull(1).orEmpty(),
                    credits = parts.getOrNull(2).orEmpty(),
                    score = parts.getOrNull(3).orEmpty(),
                )
            }
        }
    },
)

// --- 占位工具 -------------------------------------------------------------

/**
 * 尚未实现工具的占位详情页.
 *
 * Port of `ToolPlaceholderView`: a large tool glyph, the tool name, and the
 * 「功能开发中」 line the iOS view renders as its `ContentUnavailableView`
 * description. Swift's 88pt tile is 72dp here, matching the Android detail
 * scale used across the other tool screens.
 */
@Composable
fun ToolPlaceholderScreen(
    toolId: String,
    onBack: () -> Unit,
) {
    val tool = remember(toolId) { ToolCatalog.tools.firstOrNull { it.id == toolId } }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = tool?.name.orEmpty(), onBack = onBack)
            if (tool == null) {
                EmptyStateView(title = "找不到该工具", description = "请返回工具页重新选择")
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ToolIconSquare(tool = tool, size = 72.dp)
                    VSpace(18.dp)
                    Text(
                        text = tool.name,
                        style = SeuType.Title2,
                        color = SeuTheme.colors.label,
                        textAlign = TextAlign.Center,
                    )
                    VSpace(6.dp)
                    Text(
                        text = "${tool.subtitle} · 功能开发中，敬请期待",
                        style = SeuType.Footnote,
                        color = SeuTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
