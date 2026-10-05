package tech.iflink.seuwiki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tech.iflink.seuwiki.data.AuthStore

/**
 * OIDC 回调落点。
 *
 * Custom Tabs 完成授权后浏览器会跳回 `tech.iflink.seuwiki://callback`，由本 Activity
 * 接收（`launchMode=singleTask`，不会在任务栈里堆叠），处理完立刻 `finish()`。
 * 对应 iOS 侧 `ASWebAuthenticationSession` 的回调 URL 处理。
 *
 * 本 Activity 自身不渲染任何 UI —— 它只把回调交给 [AuthStore] 处理，然后
 * **显式把 MainActivity 拉回前台**。这步不能省：Custom Tab 活在浏览器自己的任务栈里，
 * 本 Activity 的 `finish()` 只能退回浏览器那一侧，用户会看到授权其实已经成功、
 * 但眼前只剩一片浏览器黑屏。必须由 App 这一侧主动抢回前台。
 */
class AuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent?.data)
    }

    /** singleTask 下二次回调走这里而不是 onCreate。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent.data)
    }

    private fun handleCallback(uri: Uri?) {
        if (uri == null) {
            finish()
            return
        }
        val store = AuthStore.get(applicationContext)
        lifecycleScope.launch {
            store.handleCallback(uri)
            returnToApp()
        }
    }

    /**
     * 把既有 MainActivity 带到前台。
     *
     * 靠 `getLaunchIntentForPackage` 拿到 MAIN/LAUNCHER 的 intent（带我们自己的
     * extras 与 `singleTask`），再叠 `CLEAR_TOP | SINGLE_TOP`：`CLEAR_TOP` 清掉本任务栈里
     * 压在 MainActivity 之上的东西，`SINGLE_TOP` 保证复用已有实例而不是新建。
     */
    private fun returnToApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }
        finish()
    }
}
