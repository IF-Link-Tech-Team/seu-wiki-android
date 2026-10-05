package tech.iflink.seuwiki.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * 打开站外链接，优先走 Custom Tabs。
 *
 * 用 Custom Tabs 而不是裸 `ACTION_VIEW`：地址栏内嵌在 App 上方，点返回即回到
 * 原文，体验最接近 iOS 的 `SFSafariViewController`，也不会把用户丢进一个冷启动
 * 的浏览器、把我们的返回栈冲掉。
 *
 * **全程不抛异常**。设备上没有任何能处理 http(s) 的处理器时（部分定制 ROM、
 * 平板上浏览器被禁用）`startActivity` 会抛 [ActivityNotFoundException]，直接
 * 崩掉整个 App。两条路径都试一遍，都失败就返回 false，由调用方决定是否提示。
 *
 * [toolbarColor] 传品牌色，与详情页页头保持一致；取不到就用主题默认。
 *
 * @return true 表示链接已被某个处理器接手。
 */
fun Context.openExternalUrl(url: String, toolbarColor: Int? = null): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != null && scheme != "http" && scheme != "https") return false

    // 先 Custom Tab：它是绝大多数设备上的正确选择，也是我们想要的产品体验。
    // 只用 1.8.0 里就有的 Builder API；toolbar 品牌色通过 setToolbarColor 传
    // extras（1.9+ 才有 setDefaultColorSchemeParams，本工程锁的是 1.8.0）。
    val customTab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .apply {
            if (toolbarColor != null) {
                // 部分 ROM 没有实现 Custom Tabs 协议，setToolbarColor 只是往 extras 里
                // 塞一个 key、不校验，浏览器端不支持就会忽略，不会崩。
                runCatching { setToolbarColor(toolbarColor) }
            }
        }
        .build()

    if (launchSafely(customTab.intent)) return true
    return launchSafely(Intent(Intent.ACTION_VIEW, uri))
}

private fun Context.launchSafely(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    // 设备上没有能处理该链接的 App / 浏览器：返回 false，调用方提示用户。
    false
} catch (_: SecurityException) {
    false
}
