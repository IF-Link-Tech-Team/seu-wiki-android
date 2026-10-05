package tech.iflink.seuwiki.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.wrapContentSize
import tech.iflink.seuwiki.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.FeedStore
import tech.iflink.seuwiki.data.ReminderScheduler
import tech.iflink.seuwiki.ui.ReminderPermission
import tech.iflink.seuwiki.data.RemoteFeedDetail
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.data.campusHtmlToAnnotatedString
import tech.iflink.seuwiki.ui.openExternalUrl
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.FeedCategory
import tech.iflink.seuwiki.models.FeedItem
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.LoadingView
import tech.iflink.seuwiki.ui.TabBarClearance
import tech.iflink.seuwiki.ui.TabPage

/**
 * 资讯详情.
 *
 * Port of `FeedItemDetailView`: source line, headline, an optional deadline
 * banner, the body, and tag chips — with a bottom action bar carrying
 * 「在网页中打开」 on the leading edge and 「设定提醒」 on the trailing edge.
 *
 * The live build swaps the summary for `detail.body` once
 * `GET /api/site/pool?q=…` resolves; until the store lands we show the summary,
 * which is the same silent fallback the SwiftUI view performs.
 */
@Composable
fun FeedItemDetailScreen(
    profile: UserProfileStore,
    store: FeedStore,
    itemId: String,
    onBack: () -> Unit,
) {
    // 路由只带 id；条目从 Store 已加载的列表状态里反查，查不到时下面的 detail
    // 请求会补出标题/摘要，够冷启动或深链进入时渲染。
    // 路由只带 id；条目从 Store 已加载的列表状态里反查。
    // 原来查不到时会回退 MockData 里那条假数据，于是深链进一个不存在的 id
    // 也能渲染出一篇看起来很真的通知 —— 那是在骗用户。
    // 现在查不到就是 null，由下面的 detail 请求补出真实内容；再查不到就显示未找到。
    val listed = remember(itemId) { store.findItem(itemId) }
    var detail by remember(itemId) { mutableStateOf<RemoteFeedDetail?>(null) }
    var isLoadingDetail by remember(itemId) { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        if (detail == null) {
            isLoadingDetail = true
            try {
                detail = store.detail(
                    listed ?: FeedItem(
                        id = itemId,
                        title = "",
                        summary = "",
                        sourceName = "",
                        category = FeedCategory.News,
                    ),
                )
            } catch (_: Exception) {
                // 静默回退：继续展示列表里那条的摘要，与 SwiftUI 的 `try?` 一致。
            } finally {
                isLoadingDetail = false
            }
        }
    }

    // listed 为空（冷启动深链 / 列表未加载）时，等 detail 到达后用它的字段拼一条
    // 能渲染的条目；连 detail 都没有才真的没这条资讯。
    val item: FeedItem? = listed ?: detail?.let { d ->
        FeedItem(
            id = itemId,
            title = d.originalTitle?.takeIf { it.isNotBlank() } ?: d.summary.orEmpty(),
            summary = d.summary ?: d.reason.orEmpty(),
            sourceName = "",
            category = FeedCategory.News,
            originalUrl = d.originalUrl,
        )
    }
    if (item == null) {
        TabPage {
            Column {
                DetailHeader(title = stringResource(R.string.feed_title), onBack = onBack)
                if (isLoadingDetail) {
                    LoadingView(topPadding = 80.dp)
                } else {
                    EmptyStateView(title = stringResource(R.string.feed_detail_missing), description = stringResource(R.string.feed_detail_missing_desc))
                }
            }
        }
        return
    }

    val colors = SeuTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 正文与「查看原文」都走同一个不崩的打开器：Custom Tabs 优先，
    // 设备上实在没有浏览器时给一句话提示，而不是把 App 带走。
    val openExternal: (String) -> Unit = { url ->
        if (!context.openExternalUrl(url, colors.groupedBackground.toArgb())) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.feed_no_browser)) }
        }
    }
    var showsReminderEditor by rememberSaveable { mutableStateOf(false) }
    // 列表响应不含 links.original，原文链接优先取详情接口下发的。
    val originalUrl = detail?.originalUrl ?: item?.originalUrl

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = detail?.originalTitle?.takeIf { it != item.title } ?: stringResource(item.category.labelRes),
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // `.padding()` around the VStack in the SwiftUI body.
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintPillCategory(item)
                    Text(item.sourceName, style = SeuType.Subheadline, color = colors.secondaryLabel)
                    Text("·", style = SeuType.Subheadline, color = colors.secondaryLabel)
                    Text(
                        text = Format.dateTime(context, item.publishedAt ?: System.currentTimeMillis()),
                        style = SeuType.Subheadline,
                        color = colors.secondaryLabel,
                    )
                }

                Text(item.title, style = SeuType.Title2, color = colors.label)

                item.audience.deadline?.let { DeadlineBanner(it) }

                // 详情接口有 body 就渲染白名单 HTML 正文，否则回退列表摘要。
                val bodyHtml = detail?.bodyHtml
                if (bodyHtml != null) {
                    val parsed = remember(bodyHtml, colors.accent) {
                        campusHtmlToAnnotatedString(bodyHtml, colors.accent)
                    }
                    Text(
                        text = parsed.text,
                        style = SeuType.Body,
                        color = colors.label,
                        lineHeight = SeuType.Body.fontSize * 1.35f,
                    )
                } else {
                    Text(
                        text = detail?.summary ?: item.summary,
                        style = SeuType.Body,
                        color = colors.label,
                        lineHeight = SeuType.Body.fontSize * 1.35f,
                    )
                }

                if (isLoadingDetail) {
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = colors.secondaryLabel,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (item.tags.isNotEmpty()) {
                    TagChips(item.tags)
                }
            }

            ActionBar(
                canOpenWeb = originalUrl != null,
                onOpenWeb = { originalUrl?.let(openExternal) },
                onSetReminder = { showsReminderEditor = true },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    if (showsReminderEditor) {
        ReminderEditSheet(
            item = item,
            onSave = { reminder ->
                // addReminder 负责落盘 + 排闹钟，返回排程结果给 sheet 如实回显。
                val result = profile.addReminder(reminder)
                showsReminderEditor = false
                result
            },
            onDismiss = { showsReminderEditor = false },
        )
    }
}

/** The accent capsule naming the notice's category. */
@Composable
private fun TintPillCategory(item: FeedItem) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = SeuIcons.of(item.category.iconKey),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = stringResource(item.category.labelRes),
            style = SeuType.CaptionMedium,
            color = colors.accent,
        )
    }
}

/** Orange deadline banner — only present when the API described a deadline. */
@Composable
private fun DeadlineBanner(deadline: Long) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedShape(12.dp))
            .background(colors.orange.copy(alpha = 0.1f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = SeuIcons.of("clock.badge.exclamationmark"),
            contentDescription = null,
            tint = colors.orange,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.feed_deadline_label), style = SeuType.Caption, color = colors.secondaryLabel)
            Text(
                text = Format.dateTime(context, deadline),
                style = SeuType.SubheadlineSemibold,
                color = colors.label,
            )
        }
        Text(
            text = Format.relative(context, deadline),
            style = SeuType.CaptionMedium,
            color = colors.orange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<String>) {
    val colors = SeuTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            Text(
                text = "#$tag",
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.tertiaryFill)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * The bottom bar: `.bordered` 「在网页中打开」 leading, `.borderedProminent`
 * 「设定提醒」 trailing, each taking half the width.
 *
 * Both sit above the floating tab bar rather than at the window edge, matching
 * the iOS stack where the action row is pinned and the tab bar floats under it.
 */
@Composable
private fun ActionBar(
    canOpenWeb: Boolean,
    onOpenWeb: () -> Unit,
    onSetReminder: () -> Unit,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (colors.isDark) Color(0xF21C1C1E) else Color(0xF2FFFFFF))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = TabBarClearance),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // `.bordered` keeps the accent tint; the stock Material outline button
        // would pick up the neutral scheme outline instead.
        Button(
            onClick = onOpenWeb,
            enabled = canOpenWeb,
            shape = CircleShape,
            border = BorderStroke(1.dp, if (canOpenWeb) colors.accent else colors.separator),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = colors.accent,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = colors.tertiaryLabel,
            ),
            elevation = null,
            // 窄屏（如 360dp）下每个按钮只有约 158dp，装不下「在网页中打开」6 个字，
            // Text 会在固定 50dp 高度里折行并被裁掉。收掉默认内边距并强制单行省略。
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 50.dp),
        ) {
            Icon(
                imageVector = SeuIcons.of("safari"),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.cd_open_in_browser),
                style = SeuType.SubheadlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onSetReminder,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 50.dp),
        ) {
            Icon(
                imageVector = SeuIcons.of("bell.badge"),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.feed_set_reminder),
                style = SeuType.SubheadlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 设定提醒.
 *
 * Modelled on Apple Reminders: a title, a due date, how far ahead to fire, a
 * time of day, and a free-form note.
 *
 * 日期与时间都用 **Material 3** 的 [DatePickerDialog] / [TimePickerDialog] ——
 * 这正是 Android 平台规范的做法（iOS 是滚轮 `Picker`，那是平台差异，不该照抄）。
 *
 * 保存时除了落盘（[UserProfileStore.addReminder]）还会真的排上闹钟
 * （[ReminderScheduler.schedule]），并把「精确 / 窗口式 / 没排上」的结果如实回显 ——
 * 静默失败过一次：界面上写着「已添加提醒」，到点却什么都不会发生。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderEditSheet(
    item: FeedItem,
    onSave: (CampusReminder) -> ReminderScheduler.ScheduleResult?,
    onDismiss: () -> Unit,
) {
    val colors = SeuTheme.colors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var note by rememberSaveable(item.id) { mutableStateOf("") }
    var advanceDays by rememberSaveable(item.id) { mutableIntStateOf(1) }
    // The audience deadline when the API supplied one, otherwise tomorrow.
    var dueDate by rememberSaveable(item.id) {
        mutableLongStateOf(item.audience.deadline ?: (System.currentTimeMillis() + DAY_MS))
    }
    // 提醒当天的触发时刻。与 iOS 的默认 9:00 一致，但允许用户改。
    var fireHour by rememberSaveable(item.id) { mutableIntStateOf(CampusReminder.DEFAULT_FIRE_HOUR) }
    var fireMinute by rememberSaveable(item.id) { mutableIntStateOf(0) }
    var showsDatePicker by rememberSaveable { mutableStateOf(false) }
    var showsTimePicker by rememberSaveable { mutableStateOf(false) }

    // POST_NOTIFICATIONS 是运行时权限（API 33+），没授权时排了闹钟也不会弹通知。
    // 这里显式查一次并把状态摆到界面上，而不是让用户等一整天才发现白设了。
    var hasNotificationPermission by remember {
        mutableStateOf(ReminderPermission.hasPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.reminder_needs_permission))
            }
        }
    }

    val advanceOptions = listOf(
        0 to stringResource(R.string.feed_advance_same_day),
        1 to stringResource(R.string.feed_advance_1),
        2 to stringResource(R.string.feed_advance_2),
        3 to stringResource(R.string.feed_advance_3),
        7 to stringResource(R.string.feed_advance_7),
    )

    // 提醒日 = 截止日往前推 advanceDays 天。把「提前量」直接并进截止时间展示，
    // 用户才不会在截止 3 天前、提前量又是 3 天时算出「提醒日在今天之前」而困惑。
    val reminderDay = remember(dueDate, advanceDays) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = dueDate }
        cal.add(java.util.Calendar.DAY_OF_YEAR, -advanceDays.coerceAtLeast(0))
        cal.timeInMillis
    }
    val fireText = Format.clock(fireHour, fireMinute)

    // 表单有标题/截止/时刻/提前量/备注五项，默认半高 sheet 装不下，M3 会把它锚在
    // 底部，于是**用户第一眼看到的正好是最次要的备注和按钮**，要设的截止时间得先
    // 往上滚。material3 1.3.1 的 `ModalBottomSheet` 没有 `skipPartiallyExpanded` 参数，
    // 官方做法是用 state 显式跳过 `PartiallyExpanded` 档位。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.reminder_set),
                style = SeuType.Title3,
                color = colors.label,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.reminder_title_label), style = SeuType.Caption) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 截止日期 —— Material 3 DatePicker。
            ReadOnlyField(
                label = stringResource(R.string.reminder_due_label),
                value = Format.dateTime(context, dueDate),
                iconKey = "calendar.day.timeline.left",
                contentDescription = stringResource(R.string.cd_reminder_channel_date),
                onClick = { showsDatePicker = true },
            )

            // 提醒时刻 —— Material 3 TimePicker（时钟面，不是滚轮）。
            ReadOnlyField(
                label = stringResource(R.string.reminder_fire_time_label),
                value = fireText,
                iconKey = "clock.badge.exclamationmark",
                contentDescription = stringResource(R.string.cd_reminder_channel_time),
                onClick = { showsTimePicker = true },
            )

            Text(
                text = stringResource(R.string.reminder_advance_label),
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
            // Wraps rather than scrolls: 5 个 chip 一行放不下时会挤掉最后一个。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                advanceOptions.forEach { (days, label) ->
                    val selected = days == advanceDays
                    Text(
                        text = label,
                        style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
                        // 选中态的底色是 accent，文字必须用 onAccentInverted：
                        // 深色模式下亮绿压白字只有 1.83:1（见 SeuColorScheme）。
                        color = if (selected) colors.onAccentInverted else colors.label,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.accent else colors.tertiaryFill)
                            .clickable { advanceDays = days }
                            // 48dp 触控目标：chip 的视觉高度只有 ~30dp，
                            // 原先靠 padding(vertical=8dp) 只有 ~30dp，低于无障碍下限。
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .wrapContentSize(),
                    )
                }
            }

            Text(
                // 只显示**日期** + 提醒时刻。原来用 dateTime 把截止时间自带的
                // 时分也带上了，出来是「10月7日 1:17 1:25 提醒」这种自相矛盾的东西 ——
                // 1:17 是创建提醒的时刻，跟提醒几点响毫无关系。
                text = stringResource(R.string.feed_reminder_will_fire, Format.date(context, reminderDay), fireText),
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.reminder_note_label), style = SeuType.Caption) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!hasNotificationPermission) {
                // 明确状态，而不是静默失败：用户以为设好了，其实到点不会响。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.reminder_needs_permission),
                        style = SeuType.Footnote,
                        color = colors.orange,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                    ) {
                        Text(stringResource(R.string.reminder_request_permission))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.reminder_cancel), style = SeuType.SubheadlineMedium)
                }
                Button(
                    onClick = {
                        val reminder = CampusReminder(
                            id = "r-${item.id}-${System.currentTimeMillis()}",
                            title = title.trim(),
                            dueDate = dueDate,
                            advanceDays = advanceDays,
                            note = note.trim(),
                            relatedItemId = item.id,
                            fireHour = fireHour,
                            fireMinute = fireMinute,
                        )
                        // 落盘 + 排闹钟都在 addReminder 里，返回值如实反映排程结果。
                        val result = onSave(reminder)
                        // 如实回显，不假装一定准时。
                        scope.launch {
                            val message = when {
                                !hasNotificationPermission -> context.getString(
                                    R.string.reminder_needs_permission,
                                )
                                result == null || !result.scheduled ->
                                    context.getString(R.string.reminder_schedule_failed)
                                !result.isExact -> context.getString(R.string.reminder_scheduled_inexact)
                                else -> context.getString(R.string.reminder_scheduled_exact)
                            }
                            snackbarHostState.showSnackbar(message)
                        }
                    },
                    enabled = title.isNotBlank(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccentInverted,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.reminder_add), style = SeuType.SubheadlineMedium)
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState)
    }

    // Material 3 的日期选择：对话框 + DatePicker，平台规范的做法。
    if (showsDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dueDate)
        DatePickerDialog(
            onDismissRequest = { showsDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dueDate = it }
                    showsDatePicker = false
                }) { Text(stringResource(R.string.reminder_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showsDatePicker = false }) {
                    Text(stringResource(R.string.reminder_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    // Material 3 的时间选择：时钟面 TimePicker。**不做** iOS 式滚轮 ——
    // 那不是 Android 平台行为，照抄只会既不像 Material 也不像 iOS。
    if (showsTimePicker) {
        val state = rememberTimePickerState(
            initialHour = fireHour,
            initialMinute = fireMinute,
            is24Hour = true,
        )
        // Material3 1.3.x 没有现成的 `TimePickerDialog` —— 它提供的是 `TimePicker`
        // 与 `TimeInput`。官方推荐的做法就是用 `AlertDialog` 包一层时钟面，
        // 这仍然完全是 Material 3 组件，**不是** iOS 式滚轮。
        AlertDialog(
            onDismissRequest = { showsTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    fireHour = state.hour
                    fireMinute = state.minute
                    showsTimePicker = false
                }) { Text(stringResource(R.string.reminder_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showsTimePicker = false }) {
                    Text(stringResource(R.string.reminder_cancel))
                }
            },
            text = { TimePicker(state = state) },
        )
    }
}

/**
 * 只读的可点击文本框，点它拉起 Material 3 选择器。
 *
 * **不能**直接把 `.clickable` 挂在 `OutlinedTextField` 的 modifier 上：
 * TextField 自己带 pointer input，会把点击吃掉去聚焦自己的光标，外层的
 * `clickable` 永远不会触发（实测：框会高亮，但日期/时间选择器根本没弹出来 ——
 * 这个 bug 之前就存在，只是没人点那两下）。
 *
 * 做法是：把 TextField 置为 `enabled = false`（不再吃事件、也不再显示光标），
 * 手动把 disabled 的配色改回正常态，再用一层 `matchParentSize` 的透明
 * `clickable` 盖在上面。这样整个框都是点击区，也顺带满足了 48dp 触控目标。
 */
@Composable
private fun ReadOnlyField(
    label: String,
    value: String,
    iconKey: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = SeuTheme.colors
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label, style = SeuType.Caption) },
            trailingIcon = {
                Icon(
                    imageVector = SeuIcons.of(iconKey),
                    contentDescription = contentDescription,
                    tint = colors.secondaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            },
            // enabled=false 默认会整体调暗，看起来像禁用态；这里改回与可输入字段
            // 一致的配色，否则用户会以为这个框坏了点不动。
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = colors.label,
                disabledBorderColor = colors.separator,
                disabledLabelColor = colors.secondaryLabel,
                disabledTrailingIconColor = colors.secondaryLabel,
                disabledContainerColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
        )
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

