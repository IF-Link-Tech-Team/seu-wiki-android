package tech.iflink.seuwiki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.design.GroupedBackground
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType

/**
 * The large-title screen header.
 *
 * Reproduces a SwiftUI `NavigationStack` + `.navigationTitle(...)` large title
 * with the iOS `profileEntry` toolbar button. The two do not share a row on
 * iOS: the avatar is a trailing item of the navigation bar, which sits *above*
 * the large title, and only collapses into it once the page scrolls. The static
 * Compose equivalent keeps both, so the top of every tab reads the same.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onProfileClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        if (onProfileClick != null || trailing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f))
                if (trailing != null) {
                    trailing()
                    Box(Modifier.size(8.dp))
                }
                if (onProfileClick != null) {
                    ProfileButton(onClick = onProfileClick)
                }
            }
        }
        Text(
            text = title,
            style = SeuType.LargeTitle,
            color = colors.label,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}

/**
 * The circular avatar entry pushed from the iOS `profileEntry` modifier.
 *
 * `Button("个人主页", systemImage: "person.crop.circle")` renders a solid person
 * silhouette inside a glass disc, measured at 41pt with a hairline ring — not
 * the outlined glyph in a plain circle.
 */
@Composable
fun ProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(colors.secondaryGroupedBackground)
            .border(1.dp, colors.label.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = stringResource(R.string.cd_profile),
            tint = colors.label,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Centered spinner, matching SwiftUI's bare `ProgressView()`. */
@Composable
fun LoadingView(modifier: Modifier = Modifier, topPadding: Dp = 80.dp) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        CircularProgressIndicator(
            color = SeuTheme.colors.secondaryLabel,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * Empty state.
 *
 * Stands in for `ContentUnavailableView`, matching its centered icon + title +
 * optional description stack.
 */
@Composable
fun EmptyStateView(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: @Composable () -> Unit = {},
    topPadding: Dp = 80.dp,
) {
    val colors = SeuTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(
            text = title,
            style = SeuType.Title3,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = SeuType.Footnote,
                color = colors.tertiaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Page background + status-bar handling shared by every tab. */
@Composable
fun TabPage(content: @Composable () -> Unit) {
    GroupedBackground { content() }
}

/**
 * Bottom content padding that clears the floating tab bar **and** the IME.
 *
 * 之前这里是写死的 96.dp，只在「手势导航 + 约 24dp 导航栏」这一种设备上够用：
 * 三键导航的导航栏 inset 约 48dp，实际需要 74 + 48 = 122dp，写死的值会让
 * 最后一个列表项有约 26dp 压在 tab bar 下面点不到。改成从真实的
 * `WindowInsets.navigationBars` 算，随系统导航模式变化自适应。
 *
 * 键盘弹出时（`WindowInsets.ime`）再额外让出键盘高度：A-16 原来只有提醒编辑
 * 一处加了 `imePadding`，搜索与绩点页没有，填学分时会被键盘盖住一半还看不出
 * 有没有滚到位。放在这里统一算，所有用 `ListBottomPadding` 的列表一次到位。
 */
val ListBottomPadding: Dp
    @Composable get() {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        // TabBarClearance(58 栏体 + 8×2 外边距) + 22dp 富余 + 导航栏 inset。
        // 键盘弹出时键盘本身就盖住了 tab bar，用 ime 与常规值的较大者即可。
        return maxOf(TabBarClearance + 22.dp + navBottom, imeBottom + 16.dp)
    }

/** 悬浮 tab 栏在正常字体下的栏体高度。 */
val TabBarBaseHeight: Dp = 58.dp

/**
 * 大字体（≥1.5×）时栏体的上限。
 *
 * 栏体内是「图标 + 文字」两行，固定高度会在大字体下裁掉标签，所以给一个上限让它
 * 长高。**底部固定元素必须跟着这个值让位**，否则会像实测那样被压住 22dp。
 */
val TabBarMaxHeight: Dp = 96.dp

/** 栏体在 `WindowInsets.navigationBars` 之外实际占的高度，供底部元素避让。 */
val tabBarHeight: Dp
    @Composable get() {
        val scale = LocalDensity.current.fontScale
        // 1.0× ~ 1.5× 之间线性增长，之后封顶。
        val growth = ((scale - 1f) / 0.5f).coerceIn(0f, 1f)
        return TabBarBaseHeight + (TabBarMaxHeight - TabBarBaseHeight) * growth
    }

/**
 * Room the floating tab bar claims at the bottom edge, excluding insets.
 *
 * Screens that pin something to the bottom — the feed detail's action row — sit
 * *above* the bar rather than under it, the way an iOS pinned bar and the glass
 * tab bar stack; they add this plus `navigationBarsPadding()`. Scrollable content
 * keeps using [ListBottomPadding] instead, which already includes the inset.
 *
 * 必须是 composable：栏体高度随系统字体缩放变化，写死会在大字体下少让位。
 */
val TabBarClearance: Dp
    @Composable get() = tabBarHeight + 16.dp

/**
 * Header for a pushed detail screen.
 *
 * The iOS equivalent is a `NavigationStack` push with
 * `.navigationBarTitleDisplayMode(.inline)`: the title is centred on the bar
 * while the back control stays pinned to the leading edge, and the back control
 * itself is a chevron in a hairline-ringed disc rather than a bare arrow.
 * `IntrinsicSize` is not needed here — the bar is a fixed 44pt tall, matching
 * the navigation bar iOS keeps on top of every pushed screen.
 */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.secondaryGroupedBackground)
                    .border(1.dp, colors.label.copy(alpha = 0.12f), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SeuIcons.of("chevron.left"),
                    contentDescription = stringResource(R.string.cd_back),
                    tint = colors.label,
                    modifier = Modifier.size(17.dp),
                )
            }
            if (trailing != null) {
                Box(Modifier.weight(1f))
                trailing()
            }
        }
        Text(
            text = title,
            style = SeuType.Headline,
            color = colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp),
        )
    }
}

@Composable
fun VSpace(height: Dp) = Box(Modifier.height(height))
