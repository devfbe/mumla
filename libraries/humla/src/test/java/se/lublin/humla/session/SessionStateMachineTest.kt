package se.lublin.humla.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.util.HumlaException

class SessionStateMachineTest {
    private val error = HumlaException("socket closed", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
    private val machine = SessionStateMachine(jitterSource = { 0.0 })

    private fun bringToConnected() {
        assertThat(machine.connectRequested()).isTrue()
        assertThat(machine.synchronized()).isTrue()
    }

    @Test
    fun startsDisconnectedWithoutError() {
        assertThat(machine.state.value).isEqualTo(SessionState.Disconnected(null))
    }

    @Test
    fun connectThenSynchronizeReachesConnected() {
        assertThat(machine.connectRequested()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.Connecting)
        assertThat(machine.synchronized()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.Connected)
    }

    @Test
    fun connectIsIgnoredWhileConnectingOrConnected() {
        machine.connectRequested()
        assertThat(machine.connectRequested()).isFalse()
        machine.synchronized()
        assertThat(machine.connectRequested()).isFalse()
    }

    @Test
    fun lossWithAutoReconnectSchedulesFirstAttemptAfterTwoSeconds() {
        bringToConnected()
        val next = machine.lost(autoReconnect = true, error = error)
        assertThat(next).isEqualTo(SessionState.ConnectionLost(2_000L, 1, error))
        assertThat(machine.state.value).isEqualTo(next)
    }

    @Test
    fun lossWithoutAutoReconnectEndsDisconnectedWithTheError() {
        bringToConnected()
        assertThat(machine.lost(autoReconnect = false, error = error)).isEqualTo(SessionState.Disconnected(error))
    }

    @Test
    fun timerMovesToReconnectingAndAFurtherLossBacksOff() {
        bringToConnected()
        machine.lost(autoReconnect = true, error = error)
        assertThat(machine.reconnectTimerFired()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.Reconnecting)
        assertThat(machine.lost(autoReconnect = true, error = error)).isEqualTo(SessionState.ConnectionLost(4_000L, 2, error))
    }

    @Test
    fun timerIsIgnoredOutsideConnectionLost() {
        assertThat(machine.reconnectTimerFired()).isFalse()
        bringToConnected()
        assertThat(machine.reconnectTimerFired()).isFalse()
    }

    @Test
    fun givesUpAfterTenAttempts() {
        bringToConnected()
        repeat(10) { attempt ->
            val lost = machine.lost(autoReconnect = true, error = error)
            assertThat(lost).isInstanceOf(SessionState.ConnectionLost::class.java)
            assertThat((lost as SessionState.ConnectionLost).attempt).isEqualTo(attempt + 1)
            assertThat(machine.reconnectTimerFired()).isTrue()
        }
        assertThat(machine.lost(autoReconnect = true, error = error)).isEqualTo(SessionState.Disconnected(error))
        assertThat(machine.attempt).isEqualTo(0)
    }

    @Test
    fun successfulSessionResetsTheAttemptCounter() {
        bringToConnected()
        repeat(3) {
            machine.lost(autoReconnect = true, error = error)
            machine.reconnectTimerFired()
        }
        assertThat(machine.synchronized()).isTrue()
        assertThat(machine.lost(autoReconnect = true, error = error)).isEqualTo(SessionState.ConnectionLost(2_000L, 1, error))
    }

    @Test
    fun connectivityChangeResetsAttemptsAndAsksForAnImmediateRetry() {
        bringToConnected()
        repeat(4) {
            machine.lost(autoReconnect = true, error = error)
            machine.reconnectTimerFired()
        }
        machine.lost(autoReconnect = true, error = error)
        assertThat(machine.connectivityRestored()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.ConnectionLost(0L, 0, error))
        machine.reconnectTimerFired()
        assertThat(machine.lost(autoReconnect = true, error = error)).isEqualTo(SessionState.ConnectionLost(2_000L, 1, error))
    }

    @Test
    fun connectivityChangeIsIgnoredWhenNotWaitingForReconnect() {
        assertThat(machine.connectivityRestored()).isFalse()
        bringToConnected()
        assertThat(machine.connectivityRestored()).isFalse()
    }

    @Test
    fun cancelReconnectKeepsTheErrorForTheUi() {
        bringToConnected()
        machine.lost(autoReconnect = true, error = error)
        assertThat(machine.cancelReconnect()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.Disconnected(error))
        assertThat(machine.cancelReconnect()).isFalse()
    }

    @Test
    fun manualConnectFromConnectionLostStartsAFreshSession() {
        bringToConnected()
        machine.lost(autoReconnect = true, error = error)
        assertThat(machine.connectRequested()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.Connecting)
        assertThat(machine.attempt).isEqualTo(0)
    }

    @Test
    fun disconnectRequestClearsEverything() {
        bringToConnected()
        assertThat(machine.disconnectRequested()).isTrue()
        assertThat(machine.current).isEqualTo(SessionState.Disconnected(null))
        assertThat(machine.disconnectRequested()).isFalse()
    }

    @Test
    fun lossWhileDisconnectedOrAlreadyLostIsANoOp() {
        assertThat(machine.lost(autoReconnect = true, error = error)).isEqualTo(SessionState.Disconnected(null))
        bringToConnected()
        val lost = machine.lost(autoReconnect = true, error = error)
        assertThat(machine.lost(autoReconnect = true, error = error)).isEqualTo(lost)
    }
}
