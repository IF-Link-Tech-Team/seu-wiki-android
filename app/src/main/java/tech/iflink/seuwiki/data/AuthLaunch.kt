package tech.iflink.seuwiki.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * 打开 Logto 托管登录页（Custom Tabs），全程 PKCE。
 *
 * 从 [tech.iflink.seuwiki.ui.profile.ProfileScreen] 里抽出来的 —— 论坛侧
 * （点赞、评论、发帖）也要求登录，如果各写一份就会有两套「点登录」的时序，
 * 其中一套迟早漏掉 `rememberPending` 那步，于是回调回来永远换不到 token。
 * **PKCE verifier 必须先落盘再开浏览器**：回调是另一个进程/Activity，
 * 顺序反了 verifier 还没写进去就跳走了。
 *
 * 登录成功后 App 存下的那一个 access token，会同时喂给
 * [FeedApiClient] 与 [ForumApiClient] —— 所以用户始终只登一次。
 */
fun AuthStore.launchSignIn(context: Context) {
    if (!isConfigured || isBusy) return
    val request = buildAuthorizationRequest() ?: return
    rememberPending(request)
    runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            // 用户**点**的站外链接交给系统默认处理，而不是被 Custom Tab 自己吃掉。
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
        dismissError()
    }
}