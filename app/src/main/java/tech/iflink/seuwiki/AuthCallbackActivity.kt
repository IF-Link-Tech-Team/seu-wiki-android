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
 * 接收（`launchMode=singleTask`，不会在任务栈里堆叠），处理完立刻 `finish()`，
 * 用户看到的仍然是原来的那个任务。对应 iOS 侧 `ASWebAuthenticationSession`
 * 的回调 URL 处理。
 *
 * 本 Activity 自身不渲染任何 UI —— 它只把回调交给 [AuthStore] 处理。
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
            finish()
        }
    }
}
