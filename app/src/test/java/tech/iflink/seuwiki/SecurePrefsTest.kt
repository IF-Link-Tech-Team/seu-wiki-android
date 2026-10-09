package tech.iflink.seuwiki

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.iflink.seuwiki.data.InMemorySharedPreferences
import tech.iflink.seuwiki.data.SecurePrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurePrefsTest {
    @Test fun encryptionFailureNeverPersistsSession() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("seu_wiki_auth", Context.MODE_PRIVATE)
            .edit().putString("refresh_token", "legacy-secret").commit()
        val prefs = SecurePrefs.open(context) { error("Keystore unavailable") }
        assertNull(prefs.getString("refresh_token", null))
        prefs.edit().putString("refresh_token", "new-secret").putLong("expires_at", 123L).apply()
        assertEquals("new-secret", prefs.getString("refresh_token", null))
        assertEquals(123L, prefs.getLong("expires_at", 0L))
        assertTrue(context.getSharedPreferences("seu_wiki_auth", Context.MODE_PRIVATE).all.isEmpty())
        val reopened = SecurePrefs.open(context) { error("Keystore unavailable") }
        assertNull(reopened.getString("refresh_token", null))
    }

    @Test fun legacyPlaintextIsRemovedBeforeEncryption() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.getSharedPreferences("seu_wiki_auth", Context.MODE_PRIVATE)
        raw.edit().clear().putString("access_token", "secret").putBoolean("secure_prefs_migrated_v1", true).commit()
        SecurePrefs.open(context) {
            assertTrue(raw.all.isEmpty())
            InMemorySharedPreferences()
        }
        assertTrue(raw.all.isEmpty())
    }

    @Test fun memoryEditorSupportsRemovalClearAndDefensiveSets() {
        val prefs = InMemorySharedPreferences()
        val source = mutableSetOf("a")
        prefs.edit().putStringSet("set", source).putBoolean("flag", true).commit()
        source.add("b")
        prefs.getStringSet("set", null)!!.add("c")
        assertEquals(setOf("a"), prefs.getStringSet("set", null))
        prefs.edit().remove("flag").apply()
        assertFalse(prefs.contains("flag"))
        prefs.edit().clear().putString("token", "value").apply()
        assertEquals(mapOf("token" to "value"), prefs.all)
    }
}
