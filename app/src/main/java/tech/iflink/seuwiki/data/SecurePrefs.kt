package tech.iflink.seuwiki.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * token 的落盘位置。
 *
 * 之前 access token / refresh token 是**明文**存在 `seu_wiki_auth.xml` 里的，
 * 而 manifest 又开着 `allowBackup="true"` —— 于是 token 会被打包进 Google 云备份、
 * 换机时被原样克隆到新设备。refresh token 有效期最长 14 天，拿到即等同账号失守。
 *
 * 现在改用 [EncryptedSharedPreferences]：主密钥由 Android Keystore 持有（硬件密钥、
 * 不可导出、随应用沙箱隔离），SharedPreferences 文件里只剩密文。备份规则另在
 * `res/xml/backup_rules.xml` 里显式排除本文件，双保险。
 */
internal object SecurePrefs {

    private const val FILE_NAME = "seu_wiki_auth"

    /** 迁移已完成的标记，防止每次启动都去翻一遍老数据。 */
    private const val KEY_MIGRATED = "secure_prefs_migrated_v1"

    /**
     * 打开（必要时先迁移）加密存储，返回可直接读写的 [SharedPreferences]。
     *
     * 迁移顺序不能反：**先**用普通 prefs 把老明文原样读进内存（此时文件还是明文），
     * **再**用同一个文件名创建加密 prefs —— `EncryptedSharedPreferences.create`
     * 会用密文重写这个文件，老明文就此被覆盖，不留残留。
     *
     * 值的类型按读出来时的实际类型原样写回：`expires_at` 必须是 Long、
     * 各种 granted 标志必须是 Boolean，若统统转成 String，`getLong` / `getBoolean`
     * 会一律抛 `ClassCastException`，登录态读不出来。
     */
    fun open(context: Context): SharedPreferences {
        // 第一步：从可能还是明文的旧文件里把内容捞出来。
        val legacy = readLegacyPlaintext(context)

        // 第二步：建加密存储（同名文件，会被密文覆盖）。
        val encrypted = createEncrypted(context)

        // 第三步：回填并标记。
        if (legacy != null && !encrypted.getBoolean(KEY_MIGRATED, false)) {
            val editor = encrypted.edit()
            legacy.forEach { (k, v) ->
                when (v) {
                    is String -> editor.putString(k, v)
                    is Long -> editor.putLong(k, v)
                    is Int -> editor.putInt(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    else -> Log.w("SecurePrefs", "跳过无法识别的旧值类型：${k}")
                }
            }
            editor.putBoolean(KEY_MIGRATED, true)
            editor.apply()
            Log.i("SecurePrefs", "已把明文会话迁移进加密存储，共 ${legacy.size} 个键")
        } else if (encrypted.getBoolean(KEY_MIGRATED, false)) {
            Log.d("SecurePrefs", "加密存储已初始化，无需迁移")
        }

        return encrypted
    }

    /**
     * 读旧明文 prefs 的全部内容；文件不存在 / 已是密文 / 读失败都返回 null。
     *
     * 已经迁移过的设备上，这里会拿到密文内容，`all` 里的值全是 Base64 字符串。
     * 所以必须靠 [KEY_MIGRATED] 再挡一道：只有「还没标记迁移」时才回填，
     * 绝不会把密文当明文再写一遍。
     */
    private fun readLegacyPlaintext(context: Context): Map<String, Any?>? = runCatching {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        // 探针：读一个必然存在的布尔键。若抛异常说明文件已是密文（getBoolean
        // 在密文上会因 Base64 解析成 String 而 ClassCastException），无需迁移。
        val alreadyEncrypted = runCatching { prefs.getBoolean(KEY_MIGRATED, false) }.isFailure
        if (alreadyEncrypted) null else prefs.all.toMap()
    }.onFailure {
        Log.w("SecurePrefs", "读取旧明文存储失败：${it::class.java.simpleName}")
    }.getOrNull()

    private fun createEncrypted(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        // Keystore 不可用（少数 ROM 的已知问题：Keystore 被重置、厂商文件系统
        // provider 异常）。此时绝不能把用户锁在门外，退回普通 prefs 仍能登录，
        // 只是失去静态加密 —— 好在备份规则仍然生效，token 不会被克隆到新设备。
        Log.w("SecurePrefs", "创建加密存储失败，退回普通存储：${error.message}")
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }
}
