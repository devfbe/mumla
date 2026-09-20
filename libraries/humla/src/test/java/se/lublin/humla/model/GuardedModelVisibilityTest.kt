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
import java.io.File
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
        val unguarded = guardedModel()
            // Up the hierarchy, not just the class itself: ModelHandler extends
            // HumlaTCPMessageListener.Stub, and declaredFields would report nothing about what it
            // inherits. That is the same blind spot declaredMethods had in task 4, where a
            // reflection test looked like it pinned a set and pinned one class of it. Nothing
            // above these three declares a field today, so this changes no result - it changes
            // what happens when someone puts one there.
            .flatMap { type -> type.hierarchy().flatMap { level -> level.declaredFields.map { type to it } } }
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
     * The classes this test demands the property of, derived rather than listed.
     *
     * A hand-written `listOf(Channel, User, ModelHandler)` was the same blindness the field sweep
     * below fixes, one level up: blind to *siblings*. Two of them had been looked at by hand and
     * cleared - but on a different argument (they are written once in a constructor and published
     * safely), which is an argument this test does not make and cannot make. So the membership is
     * derived from the package and the exemption is structural: a field written only in a
     * constructor is `final`, and the filter below already lets `final` through for exactly that
     * reason. A class somebody adds to `se.lublin.humla.model` is in this set from the moment its
     * file exists. Seen red rather than assumed: a plain `private int mProbe` added to
     * [WhisperTargetChannel] - a class the old literal never looked at - fails with
     * "expected to be empty but was: [WhisperTargetChannel.mProbe]".
     *
     * [WhisperTargetList] is the one exclusion, and not because it is cleared. `mTakenIds` is a
     * bitmask updated with `|=` and `&=`, a read-modify-write that `volatile` would not make
     * correct - it would only make this test pass, which is worse than failing. Every caller is
     * `HumlaService` (`:277`, `:318`, `:474`, `:1194`, `:1209`, `:1235`); if it is ever reached
     * from two threads what it needs is the list's own monitor, and saying so here is the point of
     * naming it rather than quietly leaving it out.
     *
     * Note what the exclusion takes out: the **whole class**, not the one field it is argued from.
     * `mActiveTargets` is `final`, so the filter below would have let it through anyway - and that
     * is itself the sweep's blind spot rather than a clearance, because `append()` writes its
     * *elements* with no synchronisation at all, which no modifier can express. Excluded here
     * means "not looked at by this test", not "checked and found safe".
     */
    private fun guardedModel(): List<Class<*>> {
        val model = classesIn("se.lublin.humla.model")
        // A scan that finds nothing, or only the half one compiler wrote, would make this test pass
        // by finding nothing to fail.
        assertThat(model).containsAtLeast(
            Channel::class.java, User::class.java, // Kotlin
            Message::class.java, ServerSettings::class.java, // Java
        )
        return model.filterNot { it == WhisperTargetList::class.java } + ModelHandler::class.java
    }

    /**
     * Every production class of [packageName], found through the places two of its own members were
     * compiled to.
     *
     * Not the whole classpath: that finds the *test* classes of this package as well, and this file
     * is in it. Not one code source either - Kotlin and Java output go to different places, so
     * [Channel] and [Message] are asked separately and whatever holds those two holds the rest.
     */
    private fun classesIn(packageName: String): List<Class<*>> {
        val prefix = packageName.replace('.', '/')
        val roots = listOf(Channel::class.java, Message::class.java)
            .mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(::File) }
            .distinct()
        return roots
            .flatMap { root -> if (root.isDirectory) namesUnder(root, prefix) else namesInJar(root, prefix) }
            .distinct()
            .sorted()
            .map { Class.forName("$packageName.$it") }
    }

    private fun namesUnder(root: File, prefix: String): List<String> =
        File(root, prefix).listFiles().orEmpty()
            .filter { it.name.endsWith(CLASS_SUFFIX) }
            .map { it.name.removeSuffix(CLASS_SUFFIX) }

    private fun namesInJar(jar: File, prefix: String): List<String> =
        java.util.zip.ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("$prefix/") && it.endsWith(CLASS_SUFFIX) }
                .map { it.removePrefix("$prefix/").removeSuffix(CLASS_SUFFIX) }
                .filterNot { it.contains('/') }
                .toList()
        }

    /**
     * The class and everything it inherits from, up to the library boundary. Not up to [Any]:
     * `java.lang.Enum` carries a non-final `hash` field of its own since JDK 21, and the two enums
     * in this package would report it as unguarded state of ours. The boundary is the right place
     * to stop anyway - `ModelHandler` extends `HumlaTCPMessageListener.Stub`, which is inside it,
     * and that is the inheritance this sweep exists to follow.
     */
    private fun Class<*>.hierarchy(): List<Class<*>> =
        generateSequence(this) { it.superclass }.takeWhile { it.name.startsWith(LIBRARY) }.toList()

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
        const val CLASS_SUFFIX = ".class"
        const val LIBRARY = "se.lublin.humla."
    }
}
