package se.lublin.humla.session

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy()

    @Test
    fun firstAttemptWaitsTwoSeconds() {
        assertThat(policy.delayFor(1, 0.0)).isEqualTo(2_000L)
    }

    @Test
    fun delayDoublesPerAttempt() {
        assertThat(policy.delayFor(2, 0.0)).isEqualTo(4_000L)
        assertThat(policy.delayFor(3, 0.0)).isEqualTo(8_000L)
        assertThat(policy.delayFor(4, 0.0)).isEqualTo(16_000L)
    }

    @Test
    fun delayIsCappedAtThirtySeconds() {
        assertThat(policy.delayFor(5, 0.0)).isEqualTo(30_000L)
        assertThat(policy.delayFor(10, 0.0)).isEqualTo(30_000L)
    }

    @Test
    fun jitterAddsUpToAQuarterOfTheDelay() {
        assertThat(policy.delayFor(1, 0.5)).isEqualTo(2_250L)
        assertThat(policy.delayFor(5, 0.5)).isEqualTo(33_750L)
    }

    @Test
    fun attemptsBeyondTheCapAreRefused() {
        assertThat(policy.delayFor(10, 0.0)).isNotNull()
        assertThat(policy.delayFor(11, 0.0)).isNull()
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { policy.delayFor(1, 1.0) }
    }
}
