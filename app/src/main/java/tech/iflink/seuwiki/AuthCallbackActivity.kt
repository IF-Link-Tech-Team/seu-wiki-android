package tech.iflink.seuwiki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import tech.iflink.seuwiki.data.AuthStore

/**
 * OIDC 回调落点。
 *
 * Custom Tabs 完成授权后浏览器会跳回 `tech.iflink.seuwiki://callback`（或已验证的
 * `https://seu.wiki/callback`），由本 Activity 接收（`launchMode=singleTask`，不会在
 * 任务栈里堆叠），处理完立刻 `finish()`。对应 iOS 侧 `ASWebAuthenticationSession`
 * 的回调 URL 处理。
 *
 * **换 token 不在 Activity 的生命周期里跑**（A-5）。这里原先用 `lifecycleScope`：
 * 用户切出去看一眼验证码短信再切回来，Activity 早就被销毁、协程被取消，而 code
 * 是一次性的、PKCE 材料也已清理，用户回来只剩「登录会话已失效」。现在换 token 跑在
 * [AuthStore.callbackScope] 这个**应用级** scope 上（与 [AuthStore] 同生命周期），
 * 本 Activity 无论何时被销毁都只负责「发起 + 结束后回到 App」。
 *
 * 本 Activity 自身不渲染任何 UI（主题透明，见 `Theme.SEUWiki.Transparent`）。
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
        store.callbackScope.launch {
            store.handleCallback(uri)
            // 只有本 Activity 还活着时才抢前台。
            // 用户若已经切去别处继续办事（换 token 又慢），此时强行把 App 拉回来
            // 反而打断他；等他自己切回来，登录态早已就绪。
            if (!isFinishing && !isDestroyed) returnToApp()
        }
    }

    /**
     * 把既有 MainActivity 带到前台。
     *
     * 靠 `getLaunchIntentForPackage` 拿到 MAIN/LAUNCHER 的 intent（带我们自己的
     * extras 与 `singleTask`），再叠 `CLEAR_TOP | SINGLE_TOP`：`CLEAR_TOP` 清掉本任务栈里
     * 压在 MainActivity 之上的东西（浏览器那边弹不掉的 Custom Tab 就靠这一步），
     * `SINGLE_TOP` 保证复用已有实例而不是新建。
     */
    private fun returnToApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }
        finish()
    }
}
