package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.mumla.testing.info

class ChatMessageLogTest {
    @Test
    fun keepsEveryMessageUntilTheCapacityIsReached() {
        val log = ChatMessageLog()

        repeat(ChatMessageLog.MAX_ENTRIES) { log.add(info("m$it")) }

        assertThat(log.size).isEqualTo(ChatMessageLog.MAX_ENTRIES)
        assertThat(log.snapshot().first().body).isEqualTo("m0")
        assertThat(log.snapshot().last().body).isEqualTo("m499")
    }

    @Test
    fun theFiveHundredAndFirstEntryEvictsTheOldest() {
        val log = ChatMessageLog()
        repeat(ChatMessageLog.MAX_ENTRIES) { log.add(info("m$it")) }

        log.add(info("m500"))

        assertThat(log.size).isEqualTo(ChatMessageLog.MAX_ENTRIES)
        assertThat(log.snapshot().first().body).isEqualTo("m1")
        assertThat(log.snapshot().last().body).isEqualTo("m500")
    }

    @Test
    fun theBoundIsFiveHundred() {
        // Written out so that changing the constant is a decision and not a typo.
        assertThat(ChatMessageLog.MAX_ENTRIES).isEqualTo(500)
    }

    @Test
    fun snapshotIsNotAffectedByLaterAdditions() {
        val log = ChatMessageLog()
        log.add(info("first"))

        val snapshot = log.snapshot()
        log.add(info("second"))

        assertThat(snapshot.map { it.body }).containsExactly("first")
        assertThat(log.snapshot().map { it.body }).containsExactly("first", "second").inOrder()
    }

    /**
     * ChatAdapter's DiffUtil callback compares by identity, so the log must hand out the same
     * message objects again.
     */
    @Test
    fun snapshotHandsOutTheSameInstances() {
        val log = ChatMessageLog()
        val message = info("same")
        log.add(message)

        assertThat(log.snapshot().single()).isSameInstanceAs(message)
        assertThat(log.snapshot().single()).isSameInstanceAs(log.snapshot().single())
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = ChatMessageLog()
        log.add(info("gone"))

        log.clear()

        assertThat(log.size).isEqualTo(0)
        assertThat(log.snapshot()).isEmpty()
    }

    @Test
    fun aSmallerCapacityKeepsTheNewest() {
        for ((capacity, kept) in listOf(2 to listOf("b", "c"), 1 to listOf("c"))) {
            val log = ChatMessageLog(capacity)
            listOf("a", "b", "c").forEach { log.add(info(it)) }

            assertWithMessage("capacity $capacity").that(log.snapshot().map { it.body }).isEqualTo(kept)
        }
    }

    @Test
    fun aCapacityBelowOneIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ChatMessageLog(capacity = 0) }
    }
}
