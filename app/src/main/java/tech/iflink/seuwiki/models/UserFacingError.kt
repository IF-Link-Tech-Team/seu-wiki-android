package tech.iflink.seuwiki.models

import android.content.Context
import androidx.annotation.StringRes

/**
 * 面向用户的错误：**前缀文案是资源 id，细节是运行时才产生的原因**。
 *
 * 为什么不直接存一个 String？ViewModel / Store 不该持有 Context（会泄漏，
 * 而且拿不到资源），所以它们没法自己 `getString`；要是图省事在 store 里把整句
 * 中文拼好，那句中文就又回到代码里，回到 `strings.xml` 只有 `app_name` 的老样子。
 * 于是这里只带一个资源 id 和一段动态细节，由 composable 侧落地成文字。
 *
 * [detail] 为空时按无参格式取（例如「登录服务配置中」），否则按 `%1$s` 取
 * （例如「搜索失败：网络异常」）。
 */
data class UserFacingError(
    @StringRes val res: Int,
    val detail: String = "",
) {
    fun format(context: Context): String =
        if (detail.isEmpty()) context.getString(res) else context.getString(res, detail)
}
