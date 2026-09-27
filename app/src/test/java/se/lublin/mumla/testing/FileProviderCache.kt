package se.lublin.mumla.chat

import androidx.core.content.FileProvider

/**
 * Drops `FileProvider`'s process-wide `sCache` (authority -> roots parsed once). Robolectric gives
 * every test method its own data directory, so a cached root from an earlier test makes
 * `getUriForFile` fail with "Failed to find configured root".
 */
object FileProviderCache {
    fun clear() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
