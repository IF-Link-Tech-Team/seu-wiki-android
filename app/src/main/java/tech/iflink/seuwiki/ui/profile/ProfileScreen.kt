package tech.iflink.seuwiki.ui.profile

import androidx.compose.ui.res.stringArrayResource
import android.content.Intent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import tech.iflink.seuwiki.data.AuthStore
import tech.iflink.seuwiki.R
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
import tech.iflink.seuwiki.models.CampusReminder
import tech.iflink.seuwiki.models.TopicCatalog
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.ListBottomPadding
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.rows.InitialsAvatar

/**
 * 「我的画像」可选项, ported from the iOS `PersonaOptions`.
 *
 * Colleges, 学段 and 年级 keep the original lists verbatim. The interest list is
 * the eight tags this milestone offers — note that the store's shipped default
 * `机器学习` is not among them, so it shows in the joined value line without a
 * matching chip.
 */
private object PersonaOptions {
    // 这些是 @Composable getter：选项表搬到 strings.xml 之后必须在 composable
    // 上下文里读，但调用点（PersonaSection）本来就是 composable，所以成立。
    val colleges: List<String> @Composable get() = stringArrayResource(R.array.seu_colleges).toList()

    val degrees: List<String> @Composable get() = stringArrayResource(R.array.profile_degrees).toList()

    val grades: List<String>
        @Composable get() = stringArrayResource(R.array.profile_grades_undergrad).toList() +
            stringArrayResource(R.array.profile_grades_grad).toList() +
            stringArrayResource(R.array.profile_grades_doctor).toList()

    val interests: List<String> @Composable get() = stringArrayResource(R.array.profile_interest_tags).toList()
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
            DetailHeader(title = stringResource(R.string.profile_title), onBack = onBack)
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
    val context = LocalContext.current
    // Logto 既没给 name、邮箱又是 @ 开头时，AuthStore 存的是空串（它拿不到 Context，
    // 也没必要把界面文案写进持久化的会话里）。兜底在这里做。
    val displayName = session?.displayName
        ?.ifEmpty { stringResource(R.string.profile_default_display_name) }
    CardColumn(spacing = 12.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(displayName ?: stringResource(R.string.profile_not_logged_in), size = 56.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayName ?: stringResource(R.string.profile_not_logged_in),
                    style = SeuType.Headline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = session?.email ?: stringResource(R.string.profile_login_hint),
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
        auth.lastError?.let { err ->
            Text(
                text = err.format(context),
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
        auth.isBusy -> stringResource(R.string.profile_logging_in)
        configured -> stringResource(R.string.profile_login)
        else -> stringResource(R.string.profile_login_unconfigured)
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
                        // 用户**点**的站外链接交给系统默认处理，而不是被 Custom Tab 自己吃掉。
                        // 注意：它去不掉服务端 302 重定向时 Edge 弹的
                        // 「callback 想要打开外部应用 SEU.wiki」确认框 —— 自定义 scheme 无法做
                        // Digital Asset Links 校验，那一 tap 在 Android 上是去不掉的，
                        // 要彻底消除只能改用已验证的 App Link（https 回调 + assetlinks.json）。
                        .setSendToExternalDefaultHandlerEnabled(true)
                        .build()
                        .apply {
                            // 只用 CLEAR_TOP：它保证回调后回到既有的 MainActivity 而不是新建一个，
                            // 并把压在我们上面的 Custom Tab 一起弹掉（回到 App 后不再是一片黑屏）。
                            //
                            // 这里**不能**再加 FLAG_ACTIVITY_NO_HISTORY：那个 flag 的语义是
                            // 「本 Activity 一旦不可见就 finish 自己」，而用户切出去看一眼
                            // 验证码短信、再切回来时登录页已经被销毁，表现为莫名其妙地回到首页。
                            // 弹 tab 改由 AuthCallbackActivity 显式把 MainActivity 拉回前台完成。
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
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
            Text(stringResource(R.string.profile_sign_out), style = SeuType.Headline, color = colors.red)
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
        SectionHeader(title = stringResource(R.string.profile_section_persona), actionLabel = null)
        CardColumn(padding = 0.dp) {
            PersonaRow(
                label = stringResource(R.string.profile_college),
                value = profile.college,
                options = PersonaOptions.colleges,
                expanded = expanded == "college",
                onToggle = { onToggle("college") },
                onSelect = { profile.updateProfile(college = it) },
            )
            InsetDivider(leading = 16.dp)
            PersonaRow(
                label = stringResource(R.string.profile_degree),
                value = profile.degree,
                options = PersonaOptions.degrees,
                expanded = expanded == "degree",
                onToggle = { onToggle("degree") },
                onSelect = { profile.updateProfile(degree = it) },
            )
            InsetDivider(leading = 16.dp)
            PersonaRow(
                label = stringResource(R.string.profile_grade),
                value = profile.grade,
                options = PersonaOptions.grades,
                expanded = expanded == "grade",
                onToggle = { onToggle("grade") },
                onSelect = { profile.updateProfile(grade = it) },
            )
            InsetDivider(leading = 16.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.profile_interests), style = SeuType.Body, color = colors.label)
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
            text = stringResource(R.string.profile_persona_note),
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
        SectionHeader(title = stringResource(R.string.profile_section_following), actionLabel = null)
        if (followed.isEmpty()) {
            CardColumn {
                Text(
                    text = stringResource(R.string.profile_empty_following),
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
        SectionHeader(title = stringResource(R.string.profile_section_reminders), actionLabel = null)
        if (profile.reminders.isEmpty()) {
            CardColumn {
                Text(
                    text = stringResource(R.string.profile_empty_reminders),
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
    val context = LocalContext.current
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
                text = stringResource(R.string.profile_reminder_row, Format.date(context, reminder.dueDate), reminder.advanceDays),
                style = SeuType.Caption,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // `.foregroundStyle(daysRemaining <= 2 ? .red : .secondary)`
        Text(
            text = if (days > 0) stringResource(R.string.profile_days_left, days) else stringResource(R.string.today),
            style = SeuType.CaptionMedium,
            color = if (days <= 2) colors.red else colors.secondaryLabel,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = SeuIcons.of("xmark"),
            contentDescription = stringResource(R.string.reminder_delete),
            tint = colors.tertiaryLabel,
            modifier = Modifier
                // 48dp 触控目标：图标本身 24dp + padding 4dp 只有 32dp，低于无障碍下限，
                // 手指很难点准，而且这还是「删除」这种不可逆操作。
                .size(48.dp)
                .clip(ContinuousRoundedShape(24.dp))
                .clickable(onClick = onRemove),
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
            Text(stringResource(R.string.profile_reset_defaults), style = SeuType.Body, color = colors.accent)
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
        SectionHeader(title = stringResource(R.string.profile_section_about), actionLabel = null)
        CardColumn(spacing = 8.dp) {
            Text("SEU.wiki", style = SeuType.Title3, color = colors.label)
            Text(
                text = stringResource(R.string.profile_about_tagline),
                style = SeuType.Footnote,
                color = colors.secondaryLabel,
            )
            AboutLine(stringResource(R.string.profile_about_version_label), stringResource(R.string.profile_about_version))
            AboutLine(stringResource(R.string.profile_about_build_label), stringResource(R.string.profile_about_build))
            AboutLine(stringResource(R.string.profile_about_ecosystem_label), stringResource(R.string.profile_about_ecosystem))
            Text(
                text = stringResource(R.string.profile_about_logto),
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
