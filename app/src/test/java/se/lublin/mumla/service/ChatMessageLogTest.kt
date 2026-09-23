package se.lublin.mumla.service

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatMessageLogTest {
    private fun info(body: String): IChatMessage =
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, body)

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
        // Spec D5's number, written out so that changing the constant is a decision and not a typo.
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
     * ChatAdapter's DiffUtil callback compares by identity, and is only cheap while the log hands
     * the same message objects out again (its KDoc: 4.35 s at 20 000 rows otherwise).
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
    fun aSmallerCapacityIsHonoured() {
        val log = ChatMessageLog(capacity = 2)

        log.add(info("a"))
        log.add(info("b"))
        log.add(info("c"))

        assertThat(log.snapshot().map { it.body }).containsExactly("b", "c").inOrder()
    }

    @Test
    fun aCapacityOfOneKeepsTheNewest() {
        val log = ChatMessageLog(capacity = 1)

        log.add(info("a"))
        log.add(info("b"))

        assertThat(log.snapshot().map { it.body }).containsExactly("b")
    }

    @Test
    fun aCapacityBelowOneIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ChatMessageLog(capacity = 0) }
    }
}
