package tech.iflink.seuwiki.ui.profile

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import tech.iflink.seuwiki.data.AuthStore
import tech.iflink.seuwiki.data.UserProfileStore
import tech.iflink.seuwiki.design.CardColumn
import tech.iflink.seuwiki.design.CardCornerRadius
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SectionHeader
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.forumCompactCount
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.ForumAvatar

/**
 * 「我的画像」可选项, ported from the iOS `PersonaOptions`.
 *
 * Colleges, 学段 and 年级 keep the original lists verbatim. The interest list is
 * the eight tags this milestone offers — note that the store's shipped default
 * `机器学习` is not among them, so it shows in the joined value line without a
 * matching chip.
 */
private object PersonaOptions {
    val colleges = listOf(
        "建筑学院", "机械工程学院", "能源与环境学院", "信息科学与工程学院",
        "土木工程学院", "电子科学与工程学院", "数学学院", "自动化学院",
        "计算机科学与工程学院", "软件学院", "集成电路学院", "网络空间安全学院",
        "物理学院", "化学化工学院", "经济管理学院", "电气工程学院",
        "外国语学院", "交通学院", "仪器科学与工程学院", "材料科学与工程学院",
        "生物科学与医学工程学院", "生命科学与技术学院", "医学院", "公共卫生学院",
        "法学院", "人文学院", "艺术学院", "体育系",
    )

    val degrees = listOf("本科", "硕士", "博士")

    val grades = listOf(
        "大一", "大二", "大三", "大四", "大五",
        "研一", "研二", "研三",
        "博一", "博二", "博三", "博四", "博五",
    )

    val interests = listOf(
        "保研", "考研", "留学", "SRTP", "数学建模", "竞赛", "实习", "校园生活",
    )
}

/**
 * 个人页.
 *
 * Port of `ProfileView` + `ProfilePickerViews`: the account header, the 我的画像
 * fields, and the local collections (提醒 / 关注的话题), followed by the local
 * reset and the About copy. The iOS build presents this as a sheet; here it is a
 * pushed detail screen, so the iOS picker pushes become inline option rows and
 * every edit writes through `UserProfileStore` as it happens — the same
 * `@Bindable` write-through the Swift pickers do.
 */
@Composable
fun ProfileScreen(
    profile: UserProfileStore,
    auth: AuthStore,
    onBack: () -> Unit,
) {
    // Which picker row is open; "" closes them all, as `dismiss()` did on iOS.
    var expanded by rememberSaveable { mutableStateOf("") }

    TabPage {
        // 底部留白交给内层列表的 ListBottomPadding（已含 tab bar + 导航栏 inset），
        // 根容器再叠一次 navigationBarsPadding 会在三键导航下多出约 48dp 死空间。
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "个人页", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    // 这一屏是详情页但**保留 tab bar**，底部要按 tab bar + 导航栏 inset 让位；
                    // 原来的 24.dp 让「关注的话题」等最后一块永远压在 tab bar 下面。
                    bottom = ListBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "identity") { IdentityCard(profile, auth) }
                if (auth.isLoggedIn) {
                    item(key = "logout") { LogoutRow(auth) }
                } else {
                    item(key = "login") { LoginRow(auth) }
                }
                item(key = "persona") {
                    PersonaSection(
                        profile = profile,
                        expanded = expanded,
                        onToggle = { field -> expanded = if (expanded == field) "" else field },
                    )
                }
                item(key = "topics") { FollowedTopicsSection(profile) }
                item(key = "reminders") { RemindersSection(profile) }
                item(key = "reset") { ResetRow(profile) }
                item(key = "about") { AboutSection() }
            }
        }
    }
}

/**
 * 身份头：未登录时是提示，已登录时显示 Logto 资料。
 *
 * 已登录分支对应 iOS `AccountHeaderSection`（首字头像 + 昵称 + 邮箱 + IF.Link ID），
 * 未登录分支沿用原措辞。
 */
@Composable
private fun IdentityCard(profile: UserProfileStore, auth: AuthStore) {
    val colors = SeuTheme.colors
    val session = auth.session
    CardColumn(spacing = 12.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForumAvatar(session?.displayName ?: "未登录", size = 56.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = session?.displayName ?: "未登录",
                    style = SeuType.Headline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = session?.email ?: "登录 IF.Link 账号，同步收藏、提醒与关注的话题",
                    style = SeuType.Footnote,
                    color = colors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (session != null && session.subject.isNotEmpty()) {
            AboutLine(label = "IF.Link ID", value = session.subject)
        }
        auth.lastError?.let { message ->
            Text(
                text = message,
                style = SeuType.Footnote,
                color = colors.red,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "${profile.college} · ${profile.degree} · ${profile.grade}",
            style = SeuType.Footnote,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 登录行：打开 Custom Tab 走 Logto 授权码 + PKCE 流程。
 *
 * 对应 iOS `LoginPromptSection` 触发 `LoginView` 的位置。差别在于 iOS 目前还是本地
 * stub 表单（`auth.login(username, password)` 不发网络请求），这里直接接真实的
 * auth.iflink.tech：点一下 → 系统浏览器授权 → `AuthCallbackActivity` 接回调 →
 * 换 token → [AuthStore] 单例的 session 变更驱动本界面重组。
 *
 * 客户端 ID 还没在 Logto 控制台注册时（[AuthStore.isConfigured] 为 false），
 * 这一行**如实禁用并标注**「登录服务配置中」，而不是静默无响应。
 */
@Composable
private fun LoginRow(auth: AuthStore) {
    val context = LocalContext.current
    val colors = SeuTheme.colors
    val configured = auth.isConfigured
    val label = when {
        auth.isBusy -> "正在登录…"
        configured -> "登录 IF.Link 账号"
        else -> "登录服务配置中"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedShape(CardCornerRadius))
            .background(if (configured) colors.accent else colors.tertiaryFill)
            .clickable(enabled = configured && !auth.isBusy) {
                val request = auth.buildAuthorizationRequest() ?: return@clickable
                // PKCE verifier 必须**先**落盘再打开浏览器：回调是另一个进程/Activity。
                auth.rememberPending(request)
                runCatching {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(context, Uri.parse(request.url))
                }.onFailure {
                    auth.dismissError()
                }
            }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = null,
                tint = if (configured) Color.White else colors.tertiaryLabel,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = label,
                style = SeuType.Headline,
                color = if (configured) Color.White else colors.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 已登录时的退出入口，对应 iOS 的 destructive「退出登录」。 */
@Composable
private fun LogoutRow(auth: AuthStore) {
    val colors = SeuTheme.colors
    CardColumn(spacing = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedShape(CardCornerRadius))
                .clickable { auth.logout() }
                .padding(vertical = 15.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("退出登录", style = SeuType.Headline, color = colors.red)
        }
    }
}

/**
 * 我的画像 — 学院 / 学段 / 年级 as inline pickers, 兴趣标签 as always-on chips.
 *
 * Every selection calls `updateProfile` immediately, matching the Swift
 * `$profile.college`-style binding that the pushed pickers wrote through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaSection(
    profile: UserProfileStore,
    expanded: String,
    onToggle: (String) -> Unit,
) {
    val colors = SeuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "我的画像", actionLabel = null)
        CardColumn(padding = 0.dp) {
            PersonaRow(
                label = "学院",
                value = profile.college,
                options = PersonaOptions.colleges,
                expanded = expanded == "college",
                onToggle = { onToggle("college") },
                onSelect = { profile.updateProfile(college = it) },
            )
            InsetDivider(leading = 16.dp)
            PersonaRow(
                label = "学段",
                value = profile.degree,
                options = PersonaOptions.degrees,
                expanded = expanded == "degree",
                onToggle = { onToggle("degree") },
                onSelect = { profile.updateProfile(degree = it) },
            )
            InsetDivider(leading = 16.dp)
            PersonaRow(
                label = "年级",
                value = profile.grade,
                options = PersonaOptions.grades,
                expanded = expanded == "grade",
                onToggle = { onToggle("grade") },
                onSelect = { profile.updateProfile(grade = it) },
            )
            InsetDivider(leading = 16.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("兴趣标签", style = SeuType.Body, color = colors.label)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = profile.interests.joinToString("、"),
                        style = SeuType.Body,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                // The iOS multi-picker toggles without dismissing, so the chips
                // stay on screen and several may be selected at once.
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PersonaOptions.interests.forEach { interest ->
                        val isOn = interest in profile.interests
                        ProfileChip(
                            label = interest,
                            selected = isOn,
                            onClick = {
                                profile.updateProfile(
                                    interests = if (isOn) {
                                        profile.interests - interest
                                    } else {
                                        profile.interests + interest
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        Text(
            text = "画像用于主页「为你推荐」匹配，未登录也可编辑。",
            style = SeuType.Footnote,
            color = colors.secondaryLabel,
        )
    }
}

/** A single-choice field; tapping the row reveals its options inline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaRow(
    label: String,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = SeuTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = SeuType.Body, color = colors.label)
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                style = SeuType.Body,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = SeuIcons.of("chevron.right"),
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    ProfileChip(label = option, selected = option == value) { onSelect(option) }
                }
            }
        }
    }
}

/** Capsule option chip — the app's shared selected/unselected treatment. */
@Composable
private fun ProfileChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Text(
        text = label,
        style = if (selected) SeuType.SubheadlineSemibold else SeuType.Subheadline,
        color = if (selected) Color.White else colors.label,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.tertiaryFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 关注的话题 — the followed slugs resolved against the forum topic catalog, so
 * each row shows the catalog's Chinese name, glyph and post count. Tapping
 * unfollows, which is what the local toggle can express without the forum
 * screen in front of it.
 */
@Composable
private fun FollowedTopicsSection(profile: UserProfileStore) {
    val colors = SeuTheme.colors
    // Only catalog slugs resolve to a name; anything else has nothing to show.
    val followed = TopicCatalog.topics.filter { it.slug in profile.followedTopicIds }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "关注的话题", actionLabel = null)
        if (followed.isEmpty()) {
            CardColumn {
                Text(
                    text = "暂无关注，去论坛话题页看看",
                    style = SeuType.Footnote,
                    color = colors.secondaryLabel,
                )
            }
        } else {
            CardColumn(padding = 0.dp) {
                followed.forEachIndexed { index, topic ->
                    if (index > 0) InsetDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { profile.toggleFollowTopic(topic.slug) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconWell(
                            icon = {
                                Icon(
                                    imageVector = SeuIcons.of(topic.iconKey),
                                    contentDescription = null,
                                    tint = colors.orange,
                                    modifier = Modifier.size(17.dp),
                                )
                            },
                            tint = colors.orange,
                        )
                        Text(topic.name, style = SeuType.Body, color = colors.label)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = forumCompactCount(topic.postCount),
                            style = SeuType.Caption,
                            color = colors.secondaryLabel,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = SeuIcons.of("checkmark"),
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 我的提醒 — deadlines with the days remaining and a delete affordance. */
@Composable
private fun RemindersSection(profile: UserProfileStore) {
    val colors = SeuTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "我的提醒", actionLabel = null)
        if (profile.reminders.isEmpty()) {
            CardColumn {
                Text(
                    text = "暂无提醒，可在资讯详情页设定",
                    style = SeuType.Footnote,
                    color = colors.secondaryLabel,
                )
            }
        } else {
            CardColumn(padding = 0.dp) {
                profile.reminders.forEachIndexed { index, reminder ->
                    if (index > 0) InsetDivider()
                    ReminderRow(
                        reminder = reminder,
                        onRemove = { profile.removeReminder(reminder.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(reminder: CampusReminder, onRemove: () -> Unit) {
    val colors = SeuTheme.colors
    val days = reminder.daysRemaining
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(
            icon = {
                Icon(
                    imageVector = SeuIcons.of("bell.fill"),
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(17.dp),
                )
            },
            tint = colors.orange,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = reminder.title,
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${Format.date(reminder.dueDate)} 截止 · 提前 ${reminder.advanceDays} 天提醒",
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // `.foregroundStyle(daysRemaining <= 2 ? .red : .secondary)`
        Text(
            text = if (days > 0) "剩 $days 天" else "今天",
            style = SeuType.CaptionMedium,
            color = if (days <= 2) colors.red else colors.secondaryLabel,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = SeuIcons.of("xmark"),
            contentDescription = "删除提醒",
            tint = colors.tertiaryLabel,
            modifier = Modifier
                .size(24.dp)
                .clip(ContinuousRoundedShape(12.dp))
                .clickable(onClick = onRemove)
                .padding(4.dp),
        )
    }
}

/** 恢复默认设置 — clears the local store back to its shipped defaults. */
@Composable
private fun ResetRow(profile: UserProfileStore) {
    val colors = SeuTheme.colors
    CardColumn(padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { profile.resetToDefaults() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("恢复默认设置", style = SeuType.Body, color = colors.accent)
        }
    }
}

/**
 * The non-auth half of the iOS `SettingsSection`.
 *
 * iOS pushes an `AboutView` from the 关于 row; the fields are inlined here
 * because a second destination is out of scope for this milestone. The 接收通知
 * toggle and 外观 picker are deliberately absent — neither has a preference
 * store on the Android side yet, and a switch that forgets its state on relaunch
 * would be worse than none.
 */
@Composable
private fun AboutSection() {
    val colors = SeuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "关于 SEU.wiki", actionLabel = null)
        CardColumn(spacing = 8.dp) {
            Text("SEU.wiki", style = SeuType.Title3, color = colors.label)
            Text(
                text = "东南大学校园资讯与经验社区",
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
            AboutLine("版本", "0.1.0")
            AboutLine("构建", "本地开发版")
            AboutLine("生态", "IF.Link")
            Text(
                text = "登录服务由自部署 Logto（auth.iflink.tech）提供。",
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** A `LabeledContent(title:value:)` row. */
@Composable
private fun AboutLine(label: String, value: String) {
    val colors = SeuTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SeuType.Body, color = colors.label)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = SeuType.Body,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
