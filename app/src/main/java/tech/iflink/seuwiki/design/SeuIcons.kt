package tech.iflink.seuwiki.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tram
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Book
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * SF Symbol → Material icon mapping.
 *
 * The iOS build addresses every glyph by its SF Symbol name. Rather than
 * re-picking icons per platform, the shared name is the source of truth and this
 * table resolves it — so a category keeps the same visual meaning on both.
 *
 * The `material-icons-extended` artifact backs the less common ones (gift, tram,
 * eco, briefcase-equivalent) that the core set lacks.
 */
object SeuIcons {

    private val bySymbol: Map<String, ImageVector> = mapOf(
        // Tab bar
        "house" to Icons.Outlined.Home,
        "newspaper" to Icons.Outlined.Newspaper,
        "bubble.left.and.text.bubble.right" to Icons.Outlined.ChatBubbleOutline,
        "square.grid.2x2" to Icons.Outlined.GridView,
        "magnifyingglass" to Icons.Filled.Search,

        // Feed categories
        "building.columns" to Icons.Outlined.AccountBalance,
        "gift" to Icons.Outlined.CardGiftcard,
        "trophy" to Icons.Outlined.EmojiEvents,
        "airplane" to Icons.Outlined.Flight,
        "briefcase" to Icons.Outlined.Work,
        "person.3" to Icons.Outlined.People,
        "fork.knife" to Icons.Outlined.Restaurant,

        // Home bento
        "bell.fill" to Icons.Filled.Notifications,
        "calendar.day.timeline.left" to Icons.Outlined.CalendarToday,

        // Forum / topics
        "graduationcap" to Icons.Outlined.School,
        "graduationcap.circle" to Icons.Outlined.School,
        "book" to Icons.Outlined.Book,
        "book.closed" to Icons.Outlined.MenuBook,
        "flask" to Icons.Outlined.Science,
        "arrow.triangle.branch" to Icons.Outlined.AccountTree,
        "leaf" to Icons.Outlined.Eco,

        // Handbook
        "books.vertical" to Icons.Outlined.LibraryBooks,
        "figure.walk.arrival" to Icons.Outlined.DirectionsWalk,
        "yensign.circle" to Icons.Outlined.AccountBalanceWallet,
        "building.2" to Icons.Outlined.Apartment,
        "tram" to Icons.Outlined.Tram,

        // Tools
        "percent" to Icons.Outlined.Percent,
        "pencil.and.list.clipboard" to Icons.Outlined.Assignment,
        "creditcard" to Icons.Outlined.CreditCard,
        "bus" to Icons.Outlined.DirectionsBus,
        "map" to Icons.Outlined.Map,
        "checklist" to Icons.Outlined.Checklist,

        // Shared affordances
        "heart" to Icons.Filled.Favorite,
        "star.fill" to Icons.Filled.Star,
        "bubble.right" to Icons.Outlined.Comment,
        "line.3.horizontal.decrease.circle" to Icons.Outlined.FilterList,
        "line.3.horizontal.decrease.circle.fill" to Icons.Outlined.FilterList,
        "wifi.slash" to Icons.Outlined.Home,
        "square.and.arrow.up" to Icons.Outlined.OpenInNew,
        "chevron.right" to Icons.Filled.ArrowDropDown,
        "checkmark" to Icons.Filled.Check,
        "ellipsis.circle" to Icons.Outlined.Apps,
    )

    /** Resolves an SF Symbol name, falling back to a neutral glyph. */
    fun of(symbol: String?): ImageVector =
        (symbol?.let { bySymbol[it] }) ?: Icons.Outlined.Apps

    /** Tab-bar and category glyphs resolve at a fixed weight. */
    val Home: ImageVector get() = of("house")
    val Feed: ImageVector get() = of("newspaper")
    val Experience: ImageVector get() = of("bubble.left.and.text.bubble.right")
    val Tools: ImageVector get() = of("square.grid.2x2")
    val Search: ImageVector get() = of("magnifyingglass")
}
