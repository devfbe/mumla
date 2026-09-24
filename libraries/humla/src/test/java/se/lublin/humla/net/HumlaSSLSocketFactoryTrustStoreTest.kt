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

package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore

/**
 * Every connection made after the user has trusted a certificate loads the app's trust store from
 * a file. The stream was never closed, so each connection -- every reconnect attempt included --
 * left a file descriptor for the finalizer ("A resource failed to call close").
 *
 * The check counts this process's open descriptors for the store file in /proc/self/fd, which is
 * the one observable that does not depend on how the stream was opened.
 */
class HumlaSSLSocketFactoryTrustStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun openDescriptorsFor(file: File): Int {
        val target = file.canonicalPath
        return File("/proc/self/fd").listFiles().orEmpty().count {
            try {
                Files.readSymbolicLink(it.toPath()).toString() == target
            } catch (e: IOException) {
                false // closed between listing and reading
            }
        }
    }

    private fun emptyStore(password: String): File {
        val file = folder.newFile("store.p12")
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        file.outputStream().use { store.store(it, password.toCharArray()) }
        return file
    }

    @Test
    fun loadingTheTrustStoreLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val file = emptyStore("pw")

        val store = HumlaSSLSocketFactory.loadTrustStore(file.path, "pw", "PKCS12")

        assertThat(store.size()).isEqualTo(0)
        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }

    @Test
    fun aTrustStoreThatFailsToLoadLeavesNoDescriptorOpen() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val file = emptyStore("pw")

        runCatching { HumlaSSLSocketFactory.loadTrustStore(file.path, "wrong", "PKCS12") }
            .onSuccess { throw AssertionError("a wrong password must not load") }

        assertThat(openDescriptorsFor(file)).isEqualTo(0)
    }
}
