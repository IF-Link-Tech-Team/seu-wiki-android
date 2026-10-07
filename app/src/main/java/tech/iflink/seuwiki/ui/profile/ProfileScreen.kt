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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import tech.iflink.seuwiki.design.ConfirmDialog
import tech.iflink.seuwiki.design.CardCornerRadius
import tech.iflink.seuwiki.design.ContinuousRoundedShape
import tech.iflink.seuwiki.design.IconWell
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SectionHeader
import tech.iflink.seuwiki.design.SwipeToDeleteBox
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
import tech.iflink.seuwiki.data.DocsStore
import tech.iflink.seuwiki.data.resolveBookmarks
import tech.iflink.seuwiki.models.DocEntry
import androidx.compose.runtime.LaunchedEffect

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
    docs: DocsStore,
    onOpenEntry: (String) -> Unit,
    onOpenForumBookmarks: () -> Unit,
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
                item(key = "bookmarks") {
                    BookmarksSection(profile = profile, docs = docs, onOpenEntry = onOpenEntry)
                }
                item(key = "forum_bookmarks") {
                    ForumBookmarksRow(onClick = onOpenForumBookmarks)
                }
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

/**
 * 已登录时的退出入口，对应 iOS 的 destructive「退出登录」。
 *
 * **必须二次确认。** `auth.logout()` 会连带向 Logto 发 RFC 7009 吊销请求，
 * 服务端的 refresh token 随即作废 —— 误触一次不是"退了个登录"，而是
 * 真的要重新走一遍浏览器授权才能回来（UI/UX 方案 §5「确认对话框：必做」）。
 */
@Composable
private fun LogoutRow(auth: AuthStore) {
    val colors = SeuTheme.colors
    var confirms by remember { mutableStateOf(false) }
    if (confirms) {
        ConfirmDialog(
            title = stringResource(R.string.sign_out_confirm_title),
            message = stringResource(R.string.sign_out_confirm_message),
            confirmLabel = stringResource(R.string.profile_sign_out),
            destructive = true,
            onConfirm = {
                confirms = false
                auth.logout()
            },
            onDismiss = { confirms = false },
        )
    }
    CardColumn(spacing = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedShape(CardCornerRadius))
                .clickable { confirms = true }
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
    val isLong = options.size >= SEARCHABLE_OPTION_COUNT

    // 长列表不再内联展开成一堆胶囊：学院有 28 项，内联展开要在一片胶囊里翻找，
    // 而且展开后把下面几屏内容全顶走。改走 Material 的「带搜索的单选对话框」——
    // 这是 Android 上长单选列表的原生形态，和 iOS 的 `.searchable` 是同一个意图
    // 各自用本平台的控件实现（不跨端模仿控件）。
    if (isLong) {
        var dialogVisible by rememberSaveable { mutableStateOf(false) }
        PersonaRowHeader(label = label, value = value, onClick = { dialogVisible = true })
        if (dialogVisible) {
            SearchableOptionDialog(
                title = label,
                options = options,
                selected = value,
                onSelect = {
                    onSelect(it)
                    dialogVisible = false
                },
                onDismiss = { dialogVisible = false },
            )
        }
        return
    }

    Column(Modifier.fillMaxWidth()) {
        PersonaRowHeader(label = label, value = value, onClick = onToggle)
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

@Composable
private fun PersonaRowHeader(label: String, value: String, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
}

/**
 * 选到多少项就改用「带搜索的对话框」，不再内联展开成胶囊。
 *
 * 学段 3 项、年级 13 项内联胶囊其实更好用 —— 一眼看全、点一下就中；学院 28 项
 * 内联展开要在一片胶囊里翻找，还会把下面几屏全顶走。阈值取 20。
 */
internal const val SEARCHABLE_OPTION_COUNT = 20

/**
 * 按关键词过滤选项。
 *
 * 抽成顶层纯函数是为了让自检能真的打到这里：界面里 `options` 是
 * `stringArrayResource` 得来的，测试没法直接塞。
 *
 * 匹配**忽略大小写且允许子串命中**（「计算机」要能命中「计算机科学与工程学院」）。
 * 空白查询返回全部 —— 用户刚点开还没输入时不该看到空列表。
 */
internal fun filterOptions(options: List<String>, query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return options
    return options.filter { it.contains(q, ignoreCase = true) }
}

/** Material 风格的「带搜索的单选」对话框。 */
@Composable
private fun SearchableOptionDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SeuTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(options, query) { filterOptions(options, query) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        CardColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
        ) {
            Text(
                text = title,
                style = SeuType.SubheadlineSemibold,
                color = colors.label,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.profile_picker_search_hint), style = SeuType.Body) },
                leadingIcon = {
                    Icon(SeuIcons.of("magnifyingglass"), contentDescription = null, tint = colors.secondaryLabel)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_picker_no_result, query),
                    style = SeuType.Body,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(visible) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option, style = SeuType.Body, color = colors.label)
                            Spacer(Modifier.weight(1f))
                            if (option == selected) {
                                Icon(
                                    imageVector = SeuIcons.of("checkmark"),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        InsetDivider()
                    }
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
    var pendingRemoval by remember { mutableStateOf<CampusReminder?>(null) }

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
                    // 左滑删除 + 长按菜单两种入口（UI/UX 方案 §5「列表删除」）。
                    // 原来只有右侧那个 × ：它虽然已经垫到 48dp 触控区，但长得
                    // 像"更多"而不是"删除"，且位置随标题长度漂移，用户很难
                    // 第一眼在列表里找到。
                    SwipeToDeleteBox(
                        onDelete = { pendingRemoval = reminder },
                        onLongClick = { pendingRemoval = reminder },
                    ) {
                        ReminderRow(reminder = reminder)
                    }
                }
            }
        }
    }

    // 三条删除入口（×、左滑、长按）都走这一个确认弹层。删除提醒撤不回来
    // —— 排好的系统通知也会一起撤掉，用户得从头再设一次。
    pendingRemoval?.let { reminder ->
        ConfirmDialog(
            title = stringResource(R.string.reminder_delete_confirm_title),
            message = stringResource(R.string.reminder_delete_confirm_message),
            confirmLabel = stringResource(R.string.reminder_delete),
            destructive = true,
            onConfirm = {
                profile.removeReminder(reminder.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun ReminderRow(reminder: CampusReminder) {
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
    }
}

/**
 * 我的收藏 — 对应 iOS `BookmarksSection`。
 *
 * 收藏**只存 slug**，标题要从索引回填：存一份标题快照的话，内容改名后会
 * 长期显示一个对不上的旧名字。
 *
 * 手册与经验两个索引都拉一次（[DocsStore.findAnyEntry] 跨索引查），
 * **都没命中就如实跳过、不渲染空行** —— 一行没有标题的条目比不显示更糟，
 * 用户只会以为 App 出错了。
 */
@Composable
private fun BookmarksSection(
    profile: UserProfileStore,
    docs: DocsStore,
    onOpenEntry: (String) -> Unit,
) {
    val colors = SeuTheme.colors

    LaunchedEffect(profile.bookmarkedSlugs) {
        if (profile.bookmarkedSlugs.isEmpty()) return@LaunchedEffect
        docs.loadHandbook()
        docs.loadExperience()
    }

    // handbook / experience 是 observable state，把它们当 remember 的 key 就是在
    // 订阅它们 —— 索引加载完这一段会自动重算（DocsStore 的 version 是私有的）。
    val resolved = remember(profile.bookmarkedSlugs, docs.handbook, docs.experience) {
        resolveBookmarks(profile.bookmarkedSlugs, docs::findAnyEntry)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = stringResource(R.string.profile_section_bookmarks), actionLabel = null)
        if (resolved.isEmpty()) {
            CardColumn {
                Text(
                    text = stringResource(R.string.profile_empty_bookmarks),
                    style = SeuType.Footnote,
                    color = colors.secondaryLabel,
                )
            }
        } else {
            CardColumn(padding = 0.dp) {
                resolved.forEachIndexed { index, entry ->
                    if (index > 0) InsetDivider()
                    BookmarkRow(entry = entry, onClick = { onOpenEntry(entry.slug) })
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(entry: DocEntry, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWell(
            icon = {
                Icon(
                    imageVector = SeuIcons.of("bookmark.fill"),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            },
            tint = colors.accent,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.title,
                style = SeuType.SubheadlineMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.description.isNullOrEmpty()) {
                Text(
                    text = entry.description,
                    style = SeuType.Caption,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 「论坛收藏」入口 —— 指向论坛的**帖子**收藏，与上面的 [BookmarksSection]（知识库条目
 * 收藏）不是一回事，所以单开一行而不是塞进那一节。
 *
 * 之前 `Routes.FORUM_BOOKMARKS` 只注册了路由、没有任何地方 navigate 过去，
 * 收藏功能等于没有入口。
 */
@Composable
private fun ForumBookmarksRow(onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = stringResource(R.string.profile_section_forum_bookmarks), actionLabel = null)
        CardColumn(padding = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 48dp 最小触控目标：整行可点，不只是文字那一小块。
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconWell(
                    icon = {
                        Icon(
                            imageVector = SeuIcons.of("bubble.left.and.text.bubble.right"),
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    tint = colors.accent,
                )
                Text(
                    text = stringResource(R.string.profile_forum_bookmarks),
                    style = SeuType.SubheadlineMedium,
                    color = colors.label,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 恢复默认设置 — clears the local store back to its shipped defaults.
 *
 * 同样要二次确认：这一下会把学院、学段、年级、兴趣标签、课表**和已设的提醒**
 * 一起清掉，其中提醒是用户一条条攒出来的，清掉后没有任何地方找得回来。
 */
@Composable
private fun ResetRow(profile: UserProfileStore) {
    val colors = SeuTheme.colors
    var confirms by remember { mutableStateOf(false) }
    if (confirms) {
        ConfirmDialog(
            title = stringResource(R.string.reset_confirm_title),
            message = stringResource(R.string.reset_confirm_message),
            confirmLabel = stringResource(R.string.action_confirm),
            destructive = false,
            onConfirm = {
                confirms = false
                profile.resetToDefaults()
            },
            onDismiss = { confirms = false },
        )
    }
    CardColumn(padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { confirms = true }
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
