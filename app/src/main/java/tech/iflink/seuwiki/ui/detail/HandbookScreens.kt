package tech.iflink.seuwiki.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.iflink.seuwiki.design.InsetDivider
import tech.iflink.seuwiki.design.SeuTheme
import tech.iflink.seuwiki.design.SeuType
import tech.iflink.seuwiki.design.cardStyle
import tech.iflink.seuwiki.models.HandbookEntry
import tech.iflink.seuwiki.models.HandbookSection
import tech.iflink.seuwiki.models.MockData
import tech.iflink.seuwiki.ui.DetailHeader
import tech.iflink.seuwiki.ui.EmptyStateView
import tech.iflink.seuwiki.ui.Format
import tech.iflink.seuwiki.ui.TabPage

/**
 * 手册分类.
 *
 * Port of `HandbookSectionView`: one grouped card listing the section's
 * documents as `NavigationLink` rows, with the `Section` footer 「共 N 篇条目」
 * below it. The inline title is the section name.
 *
 * iOS wraps this in a grouped `List`; here the same content is a single card
 * plus a footer, which is how every other grouped list in this app is drawn.
 */
@Composable
fun HandbookSectionScreen(
    sectionId: String,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val section = remember(sectionId) {
        MockData.handbookSections.firstOrNull { it.id == sectionId }
    }
    if (section == null) {
        HandbookEmptyPage(onBack = onBack, title = "手册分类不存在", description = "该分类可能已被移除")
        return
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = section.name, onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            ) {
                Column(Modifier.cardStyle(padding = 0.dp)) {
                    section.entries.forEachIndexed { index, entry ->
                        EntryRow(entry) { onOpenEntry(entry.id) }
                        if (index < section.entries.size - 1) {
                            // Swift's grouped `List` insets its separators to the
                            // row's leading content edge; there is no leading icon
                            // here, so the rule lines up with the 16pt text inset.
                            InsetDivider(leading = 16.dp)
                        }
                    }
                }
                // The iOS `Section` footer, rendered under the card rather than
                // pinned to the list bottom.
                Text(
                    text = "共 ${section.entries.size} 篇条目",
                    style = SeuType.Footnote,
                    color = SeuTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }
    }
}

/** A single document row: medium subheadline title over a secondary caption. */
@Composable
private fun EntryRow(entry: HandbookEntry, onClick: () -> Unit) {
    val colors = SeuTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 10dp vertical puts the two-line row at roughly the height of an
            // iOS `InsetGrouped` list row, which reserves 11pt plus the label's
            // own `.padding(.vertical, 2)`.
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(entry.title, style = SeuType.SubheadlineMedium, color = colors.label)
        Text(entry.subtitle, style = SeuType.Caption, color = colors.secondaryLabel)
    }
}

/**
 * 手册条目.
 *
 * Port of `HandbookEntryView`: the title block with its update date, then the
 * body text on a card with the extra 5pt leading SwiftUI asks for.
 */
@Composable
fun HandbookEntryScreen(
    entryId: String,
    onBack: () -> Unit,
) {
    val entry = remember(entryId) { MockData.handbookSections.findEntry(entryId) }
    if (entry == null) {
        HandbookEmptyPage(onBack = onBack, title = "手册条目不存在", description = "该条目可能已被移除")
        return
    }

    val colors = SeuTheme.colors
    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = entry.title, onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(entry.title, style = SeuType.Title2, color = colors.label)
                    Text(
                        text = "更新于 ${Format.date(entry.updatedAt)}",
                        style = SeuType.Caption,
                        color = colors.secondaryLabel,
                    )
                }
                Text(
                    text = entry.body,
                    // SwiftUI's `.lineSpacing(5)` adds 5pt on top of the style's
                    // own leading, so the token's line height grows by the same.
                    style = SeuType.Body.copy(
                        lineHeight = (SeuType.Body.lineHeight.value + 5f).sp,
                    ),
                    color = colors.label,
                    modifier = Modifier.cardStyle(),
                )
            }
        }
    }
}

/** The lookup `HandbookEntryScreen` needs: the entry's owning section is not on the entry. */
private fun List<HandbookSection>.findEntry(entryId: String): HandbookEntry? =
    firstOrNull { section -> section.entries.any { it.id == entryId } }
        ?.entries
        ?.firstOrNull { it.id == entryId }

/** Detail chrome shared by both "not found" states. */
@Composable
private fun HandbookEmptyPage(onBack: () -> Unit, title: String, description: String) {
    TabPage {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(title = "手册", onBack = onBack)
            EmptyStateView(title = title, description = description)
        }
    }
}
