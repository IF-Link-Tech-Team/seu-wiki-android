package tech.iflink.seuwiki.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persist sessions only when Android Keystore-backed encryption is available. */
internal object SecurePrefs {
    private const val FILE_NAME = "seu_wiki_auth"
    private val LEGACY_KEYS = setOf(
        "access_token", "refresh_token", "expires_at", "subject", "display_name",
        "email", "avatar_url", "pkce_verifier", "pkce_state", "redirect_uri",
        "offline_access_granted", "force_reauth", "secure_prefs_migrated_v1",
    )

    fun open(
        context: Context,
        factory: (Context) -> SharedPreferences = ::createEncrypted,
    ): SharedPreferences = runCatching {
        // Old versions left plaintext entries beside encrypted keys. Remove those
        // known keys before opening encrypted prefs; legacy sessions sign in again.
        val raw = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val plaintextKeys = LEGACY_KEYS.filter(raw::contains)
        if (plaintextKeys.isNotEmpty()) {
            val editor = raw.edit()
            plaintextKeys.forEach(editor::remove)
            check(editor.commit()) { "Unable to remove legacy credentials" }
        }
        factory(context)
    }.getOrElse { error ->
        Log.w("SecurePrefs", "Encrypted storage unavailable; using an in-memory session: ${error::class.java.simpleName}")
        context.deleteSharedPreferences(FILE_NAME)
        InMemorySharedPreferences()
    }

    private fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
