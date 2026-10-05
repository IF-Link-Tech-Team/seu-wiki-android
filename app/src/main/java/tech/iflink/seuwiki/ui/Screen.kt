package tech.iflink.seuwiki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.unit.dp
import tech.iflink.seuwiki.design.GroupedBackground
import tech.iflink.seuwiki.design.SeuIcons
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType

/**
 * The large-title screen header.
 *
 * Reproduces a SwiftUI `NavigationStack` + `.navigationTitle(...)` large title
 * inside a `NavigationStack`, plus the iOS `profileEntry` toolbar button: the
 * title is 34pt bold at the leading edge and a circular avatar sits at the
 * trailing edge.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onProfileClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = SeuType.LargeTitle,
            color = colors.label,
        )
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

/** The circular avatar entry pushed from the iOS `profileEntry` modifier. */
@Composable
fun ProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SeuTheme.colors
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.secondaryGroupedBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = "个人页",
            tint = colors.label,
            modifier = Modifier.size(20.dp),
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
 * Bottom content padding that clears the floating tab bar.
 *
 * The bar is a 58dp capsule inside an 8dp vertical inset plus the navigation
 * inset, so 96dp keeps the last row fully tappable without a scroll hack.
 */
val ListBottomPadding: Dp = 96.dp

/**
 * Header for a pushed detail screen.
 *
 * The iOS equivalent is a `NavigationStack` push with
 * `.navigationBarTitleDisplayMode(.inline)`: a centred inline title with a
 * chevron-left back button. The tab bar is hidden on these screens, so the
 * header only has to clear the status bar.
 */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = SeuTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SeuIcons.of("chevron.left"),
                contentDescription = "返回",
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = SeuType.Headline,
            color = colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        if (trailing != null) {
            trailing()
            Box(Modifier.size(8.dp))
        }
    }
}

@Composable
fun VSpace(height: Dp) = Box(Modifier.height(height))
