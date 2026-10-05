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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.data.FeedStore
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
import tech.iflink.seuwiki.models.MockData
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
    val listed = remember(itemId) { store.findItem(itemId) ?: MockData.feedItems.firstOrNull { it.id == itemId } }
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
                DetailHeader(title = "资讯", onBack = onBack)
                if (isLoadingDetail) {
                    LoadingView(topPadding = 80.dp)
                } else {
                    EmptyStateView(title = "资讯不存在", description = "这条资讯可能已被移除")
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
            scope.launch { snackbarHostState.showSnackbar("这台设备上没有可打开链接的浏览器") }
        }
    }
    var showsReminderEditor by rememberSaveable { mutableStateOf(false) }
    // 列表响应不含 links.original，原文链接优先取详情接口下发的。
    val originalUrl = detail?.originalUrl ?: item?.originalUrl

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = detail?.originalTitle?.takeIf { it != item.title } ?: item.category.label,
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
                        text = Format.dateTime(item.publishedAt ?: System.currentTimeMillis()),
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
                profile.addReminder(reminder)
                showsReminderEditor = false
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
            text = item.category.label,
            style = SeuType.CaptionMedium,
            color = colors.accent,
        )
    }
}

/** Orange deadline banner — only present when the API described a deadline. */
@Composable
private fun DeadlineBanner(deadline: Long) {
    val colors = SeuTheme.colors
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
            Text("截止时间", style = SeuType.Caption, color = colors.secondaryLabel)
            Text(
                text = Format.dateTime(deadline),
                style = SeuType.SubheadlineSemibold,
                color = colors.label,
            )
        }
        Text(
            text = Format.relative(deadline),
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
                text = "在网页中打开",
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
                text = "设定提醒",
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
 * Modelled on Apple Reminders: a title, a due date, how far ahead to fire, and
 * a free-form note. Saving appends to `profile.reminders`, which is what drives
 * the Home countdown card.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderEditSheet(
    item: FeedItem,
    onSave: (CampusReminder) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SeuTheme.colors
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var note by rememberSaveable(item.id) { mutableStateOf("") }
    var advanceDays by rememberSaveable(item.id) { mutableIntStateOf(1) }
    // The audience deadline when the API supplied one, otherwise tomorrow.
    var dueDate by rememberSaveable(item.id) {
        mutableLongStateOf(item.audience.deadline ?: (System.currentTimeMillis() + DAY_MS))
    }
    var showsDatePicker by rememberSaveable { mutableStateOf(false) }

    val advanceOptions = listOf(
        0 to "当天",
        1 to "提前 1 天",
        2 to "提前 2 天",
        3 to "提前 3 天",
        7 to "提前 1 周",
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("设定提醒", style = SeuType.Title3, color = colors.label)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题", style = SeuType.Caption) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = Format.dateTime(dueDate),
                onValueChange = {},
                readOnly = true,
                label = { Text("截止时间", style = SeuType.Caption) },
                trailingIcon = {
                    Icon(
                        imageVector = SeuIcons.of("clock.badge.exclamationmark"),
                        contentDescription = null,
                        tint = colors.secondaryLabel,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { showsDatePicker = true },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showsDatePicker = true },
            )

            Text("提前提醒", style = SeuType.Footnote, color = colors.secondaryLabel)
            // Wraps rather than scrolls: iOS uses a `Picker`, which always shows
            // all five segments, and a plain Row clipped the last chip off the
            // right edge on a 1080px-wide screen.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                advanceOptions.forEach { (days, label) ->
                    val selected = days == advanceDays
                    Text(
                        text = label,
                        style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
                        color = if (selected) Color.White else colors.label,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.accent else colors.tertiaryFill)
                            .clickable { advanceDays = days }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注", style = SeuType.Caption) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("取消", style = SeuType.SubheadlineMedium) }
                Button(
                    onClick = {
                        onSave(
                            CampusReminder(
                                id = "r-${item.id}-${System.currentTimeMillis()}",
                                title = title.trim(),
                                dueDate = dueDate,
                                advanceDays = advanceDays,
                                note = note.trim(),
                                relatedItemId = item.id,
                            ),
                        )
                    },
                    enabled = title.isNotBlank(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("添加", style = SeuType.SubheadlineMedium) }
            }
        }
    }

    if (showsDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dueDate)
        DatePickerDialog(
            onDismissRequest = { showsDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dueDate = it }
                    showsDatePicker = false
                }) { Text("完成") }
            },
            dismissButton = {
                TextButton(onClick = { showsDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

