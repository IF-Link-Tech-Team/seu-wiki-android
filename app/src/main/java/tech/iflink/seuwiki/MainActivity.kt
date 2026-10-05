package tech.iflink.seuwiki

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.LaunchedEffect
import tech.iflink.seuwiki.design.SEUWikiTheme
import tech.iflink.seuwiki.ui.RootView

class MainActivity : ComponentActivity() {

    /**
     * 点提醒通知要打开的资讯 id。
     *
     * 存在 Activity 的可变状态里而不是 `savedInstanceState` 的 Bundle：它是
     * **一次性**的导航意图 —— 消费掉就该置空，否则进程被回收重建（通知点击、
     * 配置变更都可能）时会拿同一个 id 再跳一次，把用户硬拽回一个他早已退出的详情页。
     *
     * 冷启动（通知点开时 Activity 还不存在）与热启动（`onNewIntent`）两条路径
     * 都会写这里，RootView 统一消费。
     */
    private var pendingItemId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingItemId = intent?.getStringExtra(ReminderReceiver.EXTRA_OPEN_ITEM_ID)
        setContent {
            SEUWikiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootView(
                        pendingFeedItemId = pendingItemId,
                        onPendingFeedItemConsumed = { pendingItemId = null },
                    )
                }
            }
        }
    }

    /**
     * launchMode=singleTask：通知点开会把 intent 派发到**既有**实例，
     * 只走 onNewIntent 而不重建 Activity，所以必须在这里也取一次。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ReminderReceiver.EXTRA_OPEN_ITEM_ID)?.let { pendingItemId = it }
    }
}
