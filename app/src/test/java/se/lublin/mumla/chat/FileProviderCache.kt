package se.lublin.mumla.chat

import androidx.core.content.FileProvider

/**
 * Drops `FileProvider`'s process-wide `sCache`, which maps an authority to the roots parsed out of
 * `shared_image_paths.xml` **once**.
 *
 * Robolectric gives every *test method* its own data directory, so `context.cacheDir` differs from
 * one method to the next -- while that static map survives, because it lives in an ordinary library
 * class that nothing resets between tests. The second method in a class that calls `getUriForFile`
 * therefore asks a strategy built for the first method's cache directory and gets
 * `IllegalArgumentException: Failed to find configured root`. Measured: 7 of 13 tests failed that
 * way, and which ones depended on JUnit's method order.
 *
 * This is a test-harness fix, not a production one: on a device the cache directory does not move.
 */
object FileProviderCache {
    fun clear() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
