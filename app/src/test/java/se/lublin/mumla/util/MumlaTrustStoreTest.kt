/*
 * Copyright (C) 2026 The Mumla authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore

/**
 * The app's own trust store file is read and written through streams that were closed only on the
 * happy path: a store that failed to load or to save left its descriptor to the finalizer. The
 * check counts this process's open descriptors for the file in /proc/self/fd.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaTrustStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.filesDir, "mumla-store.bks")

    @After
    fun tearDown() {
        MumlaTrustStore.clearTrustStore(context)
    }

    private fun openDescriptorsFor(file: File): Int {
        val target = file.canonicalPath
        return File("/proc/self/fd").listFiles().orEmpty().count {
            try {
                Files.readSymbolicLink(it.toPath()).toString() == target
            } catch (e: IOException) {
                false
            }
        }
    }

    @Test
    fun aStoreThatRoundTripsLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val store = MumlaTrustStore.getTrustStore(context) // no file yet: an empty store

        MumlaTrustStore.saveTrustStore(context, store)
        assertThat(MumlaTrustStore.getTrustStore(context).size()).isEqualTo(0)

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }

    @Test
    fun aCorruptStoreThatFailsToLoadLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        runCatching { MumlaTrustStore.getTrustStore(context) }
            .onSuccess { throw AssertionError("a corrupt store must not load") }

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }

    @Test
    fun aStoreThatFailsToSaveLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val uninitialized = KeyStore.getInstance(KeyStore.getDefaultType()) // store() refuses it

        runCatching { MumlaTrustStore.saveTrustStore(context, uninitialized) }
            .onSuccess { throw AssertionError("an uninitialized store must not save") }

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }
}
