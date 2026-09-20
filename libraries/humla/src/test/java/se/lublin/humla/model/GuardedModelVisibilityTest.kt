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
package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.protocol.ModelHandler
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The scalar half of the guarded model: the lists are covered by [ChannelTest], these are the
 * fields beside them.
 *
 * Two kinds of test, doing two different jobs. The first two show that a missing `volatile` has a
 * consequence a test can see - a reader that never observes the write at all, because the JIT
 * hoists a non-volatile field read out of a loop. They cover one field per class, and each spins
 * inline rather than through a shared helper so that the field read stays monomorphic and really
 * is hoisted. [everyMutableFieldOfTheGuardedModelIsVolatile] then covers the rest as a set rather
 * than repeating that loop forty times: it is the reflection form of spec 4.04's "pin the set, not
 * the member", so a field a later task adds fails here until someone decides which thread may see
 * it.
 *
 * Measured on this loop shape: 3 runs out of 3 spin to the limit without `volatile`.
 */
class GuardedModelVisibilityTest {

    @Test
    fun aChannelNameWrittenOnOneThreadBecomesVisibleToAnother() {
        // Written by ModelHandler.messageChannelState on the protocol thread, read by
        // ChannelListAdapter.onBindViewHolder (:152) on the main thread.
        val channel = Channel(1, false)
        val sawIt = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val reader = thread(name = "channel-name-reader", isDaemon = true) {
            started.set(true)
            var spins = 0L
            while (channel.getName() == null && spins < SPIN_LIMIT) spins++
            sawIt.set(spins < SPIN_LIMIT)
        }

        awaitCompiledLoop(started)
        channel.setName("named")
        reader.join(JOIN_TIMEOUT_MILLIS)

        assertThat(sawIt.get()).isTrue()
    }

    @Test
    fun aUserTalkStateWrittenOnOneThreadBecomesVisibleToAnother() {
        // Written from the audio path, read by ChannelListAdapter to draw the talk icon.
        val user = User(1, "u")
        val sawIt = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val reader = thread(name = "user-talkstate-reader", isDaemon = true) {
            started.set(true)
            var spins = 0L
            while (user.getTalkState() == TalkState.PASSIVE && spins < SPIN_LIMIT) spins++
            sawIt.set(spins < SPIN_LIMIT)
        }

        awaitCompiledLoop(started)
        user.setTalkState(TalkState.TALKING)
        reader.join(JOIN_TIMEOUT_MILLIS)

        assertThat(sawIt.get()).isTrue()
    }

    @Test
    fun everyMutableFieldOfTheGuardedModelIsVolatile() {
        val unguarded = listOf(Channel::class.java, User::class.java, ModelHandler::class.java)
            .flatMap { type -> type.declaredFields.map { type to it } }
            .filterNot { (_, field) -> field.isSynthetic }
            .filterNot { (_, field) -> Modifier.isStatic(field.modifiers) }
            // A final field is published safely by the constructor, and the three lists Channel
            // owns are final and guarded by its monitor instead (see ChannelTest).
            .filterNot { (_, field) -> Modifier.isFinal(field.modifiers) }
            .filterNot { (_, field) -> Modifier.isVolatile(field.modifiers) }
            .map { (type, field) -> "${type.simpleName}.${field.name}" }

        assertThat(unguarded).isEmpty()
    }

    /**
     * The write has to land *after* the reader's loop has been compiled, which is the whole point:
     * an interpreted loop re-reads the field either way and would pass with or without `volatile`.
     * There is nothing to wait on, because publishing the spin count through anything the writer
     * could observe would itself be the barrier under test - hence a delay rather than `awaitUntil`.
     */
    private fun awaitCompiledLoop(started: AtomicBoolean) {
        while (!started.get()) Thread.yield()
        Thread.sleep(WRITE_DELAY_MILLIS)
    }

    private companion object {
        /**
         * Far beyond what a loop that actually re-reads the field needs, so the limit never decides
         * the outcome for a volatile field even on a machine busy with the race tests next door - a
         * lower bound made this test fail as collateral while an unrelated mutation was being
         * checked. A hoisted loop still reaches it within the join timeout below.
         */
        const val SPIN_LIMIT = 20_000_000_000L
        const val WRITE_DELAY_MILLIS = 500L
        const val JOIN_TIMEOUT_MILLIS = 30_000L
    }
}
