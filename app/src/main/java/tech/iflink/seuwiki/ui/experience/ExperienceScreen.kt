package tech.iflink.seuwiki.ui.experience

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import tech.iflink.seuwiki.R
import tech.iflink.seuwiki.data.ForumStore
import tech.iflink.seuwiki.design.ConsoleBar
import tech.iflink.seuwiki.ui.ScreenHeader
import tech.iflink.seuwiki.ui.TabPage
import tech.iflink.seuwiki.ui.forum.ForumFollowingList
import tech.iflink.seuwiki.ui.forum.ForumHotList

/**
 * 经验 tab 的三个子页，按 console 胶囊顺序。
 *
 * 与 iOS `ForumFeedTab` 对齐：热门（热榜，置顶优先）/ 关注（关注流）/ 东大生存手册。
 * 数据全部来自论坛后端 `https://forum.seu.wiki`；早期的「热门」是
 * `/api/site/docs/experience` 经验长文、「话题」是其分面筛选，两者已随经验长文
 * 信源一并移除。
 */
enum class ExperienceTab(val key: String, @StringRes val labelRes: Int) {
    /** 论坛热榜（`GET /api/posts?sort=hot`，置顶优先，offset 分页）。 */
    Hot("hot", R.string.experience_tab_hot),

    /** 关注流（`GET /api/feed/following`，需登录；401 给登录引导）。 */
    Following("following", R.string.experience_tab_following),

    /** 东大生存手册板块列表（`GET /api/handbook/sections`）。 */
    Handbook("handbook", R.string.experience_tab_handbook);

    companion object {
        val all: List<ExperienceTab> get() = entries.toList()
    }
}

/**
 * 经验 tab。
 *
 * 三个子页全部来自**论坛后端的真实数据**：
 * - 热门：热榜帖子流（[ForumHotList]），右下角发帖按钮挂在这一页
 * - 关注：关注流（[ForumFollowingList]），未登录给登录引导、零关注给推荐板块
 * - 东大生存手册：板块列表（[HandbookHomeTab]）
 *
 * console 胶囊与 pager 双向绑定：点胶囊滚动 pager，滑动 pager 跟随胶囊，
 * 与 iOS 的 `ExperienceHomeView` 一致。
 */
@Composable
fun ExperienceScreen(
    forumStore: ForumStore,
    isLoggedIn: Boolean,
    onOpenProfile: () -> Unit,
    onLogin: () -> Unit,
    onOpenPost: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenTopic: (String) -> Unit,
    onOpenHandbookSection: (slug: String, name: String) -> Unit,
) {
    val tabs = ExperienceTab.all
    var tabKey by rememberSaveable { mutableStateOf(ExperienceTab.Hot.key) }
    val tab = tabs.firstOrNull { it.key == tabKey } ?: ExperienceTab.Hot
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // Console → pager。
    LaunchedEffect(tab) {
        val index = tabs.indexOf(tab)
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }
    // Pager → console。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            tabs.getOrNull(page)?.let { tabKey = it.key }
        }
    }

    TabPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.experience_title), onProfileClick = onOpenProfile)
            ConsoleBar(
                items = tabs,
                selection = tab,
                onSelect = {
                    tabKey = it.key
                    scope.launch { pagerState.animateScrollToPage(tabs.indexOf(it)) }
                },
                title = { stringResource(it.labelRes) },
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (tabs[page]) {
                    // 发帖按钮（FAB）挂在这一页：ForumHotList 内部靠 onCompose 非空
                    // 才渲染 ComposeFab，不传的话按钮整个不会出现。
                    ExperienceTab.Hot -> ForumHotList(
                        store = forumStore,
                        onOpenPost = onOpenPost,
                        onCompose = onCompose,
                        onOpenTopic = onOpenTopic,
                    )

                    ExperienceTab.Following -> ForumFollowingList(
                        store = forumStore,
                        isLoggedIn = isLoggedIn,
                        onLogin = onLogin,
                        onOpenPost = onOpenPost,
                        onOpenTopic = onOpenTopic,
                    )

                    ExperienceTab.Handbook -> HandbookHomeTab(
                        store = forumStore,
                        onOpenSection = onOpenHandbookSection,
                    )
                }
            }
        }
    }
}
