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

package se.lublin.mumla.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server

/**
 * Round trips through every query of the database, written against the Java class before its
 * conversion to Kotlin: whatever these read back, the conversion has to read back too.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaSQLiteDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = MumlaSQLiteDatabase(context, "test.db")

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("test.db")
    }

    private fun server(name: String) = Server(-1, name, "$name.example", 64738, "me", "pw")

    @Test
    fun serversRoundTripAndUpdateAndRemove() {
        assertThat(db.getServers()).isEmpty()
        val a = server("a").also { db.addServer(it) }
        val b = server("b").also { db.addServer(it) }
        assertThat(a.id).isNotEqualTo(b.id)

        a.name = "renamed"
        db.updateServer(a)

        val read = db.getServers().associateBy { it.id }
        assertThat(read.keys).containsExactly(a.id, b.id)
        with(read.getValue(a.id)) {
            assertThat(name).isEqualTo("renamed")
            assertThat(host).isEqualTo("a.example")
            assertThat(port).isEqualTo(64738)
            assertThat(username).isEqualTo("me")
            assertThat(password).isEqualTo("pw")
        }

        db.removeServer(b)
        assertThat(db.getServers().map { it.id }).containsExactly(a.id)
    }

    @Test
    fun removingAServerRemovesItsPinsTokensMutesAndIgnores() {
        val s = server("s").also { db.addServer(it) }
        db.addPinnedChannel(s.id, 4)
        db.addAccessToken(s.id, "tok")
        db.addLocalMutedUser(s.id, 7)
        db.addLocalIgnoredUser(s.id, 8)

        db.removeServer(s)

        assertThat(db.getPinnedChannels(s.id)).isEmpty()
        assertThat(db.getAccessTokens(s.id)).isEmpty()
        assertThat(db.getLocalMutedUsers(s.id)).isEmpty()
        assertThat(db.getLocalIgnoredUsers(s.id)).isEmpty()
    }

    @Test
    fun pinnedChannelsArePerServer() {
        db.addPinnedChannel(1, 10)
        db.addPinnedChannel(1, 11)
        db.addPinnedChannel(2, 10)

        assertThat(db.getPinnedChannels(1)).containsExactly(10, 11)
        assertThat(db.isChannelPinned(1, 11)).isTrue()
        assertThat(db.isChannelPinned(2, 11)).isFalse()

        db.removePinnedChannel(1, 11)
        assertThat(db.isChannelPinned(1, 11)).isFalse()
        assertThat(db.getPinnedChannels(1)).containsExactly(10)
    }

    @Test
    fun accessTokensArePerServer() {
        db.addAccessToken(1, "x")
        db.addAccessToken(1, "y")
        db.addAccessToken(2, "z")

        assertThat(db.getAccessTokens(1)).containsExactly("x", "y")
        db.removeAccessToken(1, "x")
        assertThat(db.getAccessTokens(1)).containsExactly("y")
        assertThat(db.getAccessTokens(2)).containsExactly("z")
    }

    @Test
    fun localMutesAndIgnoresArePerServer() {
        db.addLocalMutedUser(1, 5)
        db.addLocalMutedUser(1, 6)
        db.addLocalIgnoredUser(1, 7)
        db.addLocalIgnoredUser(2, 8)

        assertThat(db.getLocalMutedUsers(1)).containsExactly(5, 6)
        assertThat(db.getLocalIgnoredUsers(1)).containsExactly(7)

        db.removeLocalMutedUser(1, 5)
        db.removeLocalIgnoredUser(1, 7)
        assertThat(db.getLocalMutedUsers(1)).containsExactly(6)
        assertThat(db.getLocalIgnoredUsers(1)).isEmpty()
        assertThat(db.getLocalIgnoredUsers(2)).containsExactly(8)
    }

    @Test
    fun certificatesRoundTripWithTheirData() {
        val c = db.addCertificate("alice.p12", byteArrayOf(1, 2, 3))

        assertThat(db.getCertificates().map { it.id to it.name }).containsExactly(c.id to "alice.p12")
        assertThat(db.getCertificateData(c.id)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(db.getCertificateData(c.id + 1)).isNull()

        db.removeCertificate(c.id)
        assertThat(db.getCertificates()).isEmpty()
    }

    /**
     * Pre-existing defect, characterized rather than fixed: markCommentSeen stores the hash as a
     * BLOB and isCommentSeen looks it up as TEXT (`new String(commentHash)`), and in SQLite a BLOB
     * never equals a TEXT. A marked comment therefore never reads as seen. Nothing in the app calls
     * either method, so no user sees it.
     */
    @Test
    fun aMarkedCommentNeverReadsAsSeen() {
        val hash = byteArrayOf(0x41, 0x42)
        assertThat(db.isCommentSeen("user", hash)).isFalse()

        db.markCommentSeen("user", hash)

        assertThat(db.isCommentSeen("user", hash)).isFalse()
    }

    private fun tables(): Set<String> =
        db.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Each upgrade step creates exactly the table its version introduced, and nothing older. */
    @Test
    fun anUpgradeCreatesTheTablesNewerThanTheOldVersion() {
        val w = db.writableDatabase
        val added = listOf("favourites", "tokens", "comments", "local_mute", "local_ignore", "certificates")
        for (t in added) w.execSQL("DROP TABLE $t")

        db.onUpgrade(w, 5, 8)
        assertThat(tables()).containsAtLeast("local_mute", "local_ignore", "certificates")
        assertThat(tables()).containsNoneOf("favourites", "tokens", "comments")

        db.onUpgrade(w, 2, 8)
        assertThat(tables()).containsAtLeastElementsIn(added)
    }
}
