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
 * Visibility of the scalar fields of the guarded model; the lists are covered by [ChannelTest].
 *
 * The first two tests show that a missing `volatile` is observable: the JIT hoists a non-volatile
 * read out of a loop and the reader never sees the write. Each spins inline so the read stays
 * monomorphic and really is hoisted. [everyMutableFieldOfTheGuardedModelIsVolatile] then covers
 * all other fields by reflection, so a new field fails until someone decides its threading.
 */
class GuardedModelVisibilityTest {

    @Test
    fun aChannelNameWrittenOnOneThreadBecomesVisibleToAnother() {
        // Written by ModelHandler on the protocol thread, read by the channel list on the main
        // thread.
        val channel = Channel(1, false)
        val sawIt = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val reader = thread(name = "channel-name-reader", isDaemon = true) {
            started.set(true)
            var spins = 0L
            while (channel.name == null && spins < SPIN_LIMIT) spins++
            sawIt.set(spins < SPIN_LIMIT)
        }

        awaitCompiledLoop(started)
        channel.name = "named"
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
            while (user.talkState == TalkState.PASSIVE && spins < SPIN_LIMIT) spins++
            sawIt.set(spins < SPIN_LIMIT)
        }

        awaitCompiledLoop(started)
        user.talkState = TalkState.TALKING
        reader.join(JOIN_TIMEOUT_MILLIS)

        assertThat(sawIt.get()).isTrue()
    }

    @Test
    fun everyMutableFieldOfTheGuardedModelIsVolatile() {
        val unguarded = guardedModel()
            // Up the hierarchy: declaredFields does not report inherited fields.
            .flatMap { type -> type.hierarchy().flatMap { level -> level.declaredFields.map { type to it } } }
            .filterNot { (_, field) -> field.isSynthetic }
            .filterNot { (_, field) -> Modifier.isStatic(field.modifiers) }
            // A final field is published safely by the constructor, and the three lists Channel
            // owns are final and guarded by its monitor instead (see ChannelTest).
            .filterNot { (_, field) -> Modifier.isFinal(field.modifiers) }
            .filterNot { (_, field) -> Modifier.isVolatile(field.modifiers) }
            // Read and written on the protocol thread only.
            .filterNot { (type, field) -> type == ModelHandler::class.java && field.name == "publishScheduled" }
            .map { (type, field) -> "${type.simpleName}.${field.name}" }

        assertThat(unguarded).isEmpty()
    }

    /**
     * The classes of `se.lublin.humla.model`, derived from the package so that a new class is
     * covered automatically. Fields written only in a constructor are `final` and pass the filter.
     *
     * [WhisperTargetList] is excluded, not cleared: `mTakenIds` is updated by read-modify-write,
     * which `volatile` would not make correct, and `append()` writes elements unsynchronized. If it
     * is ever reached from two threads, it needs its own monitor.
     */
    private fun guardedModel(): List<Class<*>> {
        val model = classesIn("se.lublin.humla.model")
        // A scan that finds nothing would make this test pass vacuously.
        assertThat(model).containsAtLeast(
            Channel::class.java, User::class.java, // Kotlin
            Message::class.java, ServerSettings::class.java, // Java
        )
        return model.filterNot { it == WhisperTargetList::class.java } + ModelHandler::class.java
    }

    /**
     * Every production class of [packageName]. Kotlin and Java output go to different code sources,
     * so [Channel] and [Message] locate them; scanning the whole classpath would find test classes.
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
     * The class and its superclasses up to the library boundary. Not up to [Any]: `java.lang.Enum`
     * has a non-final `hash` field since JDK 21.
     */
    private fun Class<*>.hierarchy(): List<Class<*>> =
        generateSequence(this) { it.superclass }.takeWhile { it.name.startsWith(LIBRARY) }.toList()

    /**
     * The write must land after the reader's loop is compiled; an interpreted loop re-reads the
     * field either way. Anything the reader could publish to signal that would itself be a barrier,
     * hence a delay rather than `awaitUntil`.
     */
    private fun awaitCompiledLoop(started: AtomicBoolean) {
        while (!started.get()) Thread.yield()
        Thread.sleep(WRITE_DELAY_MILLIS)
    }

    private companion object {
        /**
         * Far beyond what a loop that re-reads the field needs, so the limit never decides the
         * outcome for a volatile field on a busy machine; a hoisted loop still reaches it within
         * the join timeout.
         */
        const val SPIN_LIMIT = 20_000_000_000L
        const val WRITE_DELAY_MILLIS = 500L
        const val JOIN_TIMEOUT_MILLIS = 30_000L
        const val CLASS_SUFFIX = ".class"
        const val LIBRARY = "se.lublin.humla."
    }
}
