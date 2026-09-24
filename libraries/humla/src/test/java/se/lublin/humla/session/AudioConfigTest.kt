@file:Suppress("DEPRECATION") // see AudioConfig.kt

package se.lublin.humla.session

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.Constants

/**
 * [AudioConfig] carries two decisions of its own: the half-duplex rule (spec A7) and, because
 * HumlaService reconfigures the pipeline on `config != previous`, structural equality over every
 * field it holds.
 */
@RunWith(RobolectricTestRunner::class)
class AudioConfigTest {
    private fun config(requested: Boolean, transmitMode: Int) =
        AudioConfig(halfDuplexRequested = requested, transmitMode = transmitMode)

    /**
     * All six corners of the two inputs the property reads, not the two mutations its two clauses
     * would suggest (spec 4.04: 2^k inputs, not k mutations). `transmitMode` is not a boolean, so
     * the space is {requested} x {the three transmit modes}.
     */
    @Test
    fun halfDuplexHoldsOnlyWhenItWasRequestedAndTheModeIsPushToTalk() {
        assertThat(config(true, Constants.TRANSMIT_PUSH_TO_TALK).halfDuplex).isTrue()
        assertThat(config(true, Constants.TRANSMIT_VOICE_ACTIVITY).halfDuplex).isFalse()
        assertThat(config(true, Constants.TRANSMIT_CONTINUOUS).halfDuplex).isFalse()
        assertThat(config(false, Constants.TRANSMIT_PUSH_TO_TALK).halfDuplex).isFalse()
        assertThat(config(false, Constants.TRANSMIT_VOICE_ACTIVITY).halfDuplex).isFalse()
        assertThat(config(false, Constants.TRANSMIT_CONTINUOUS).halfDuplex).isFalse()
    }

    /**
     * The request survives a mode that suppresses it: this is what makes the old
     * `EXTRAS_HALF_DUPLEX` handling's defect unrepeatable. That code resolved the rule once, at the
     * moment the key arrived, against `extras.getInt(EXTRAS_TRANSMIT_MODE)` of the *same* bundle -
     * which is 0 (voice activity) whenever the bundle does not also carry the transmit mode, so a
     * settings write that changed only half duplex always resolved to false.
     */
    @Test
    fun theRequestIsRememberedWhileTheModeSuppressesIt() {
        val requested = config(true, Constants.TRANSMIT_VOICE_ACTIVITY)
        assertThat(requested.halfDuplex).isFalse()
        assertThat(requested.copy(transmitMode = Constants.TRANSMIT_PUSH_TO_TALK).halfDuplex).isTrue()
    }

    /**
     * A media-stream track does not follow the communication device, so a routed device - any of
     * them, not only a headset - moves playback to the voice-call stream, and only an unrouted
     * session keeps the stream the settings chose.
     */
    @Test
    fun aRoutedDeviceMovesPlaybackToTheVoiceCallStream() {
        val unrouted = AudioConfig(audioStream = AudioManager.STREAM_MUSIC)
        assertThat(unrouted.playbackStream).isEqualTo(AudioManager.STREAM_MUSIC)
        for (type in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )) {
            assertThat(unrouted.copy(routedDeviceType = type).playbackStream)
                .isEqualTo(AudioManager.STREAM_VOICE_CALL)
        }
    }

    /** SCO is the one route with its own sample rate, and the one the pipeline is told about. */
    @Test
    fun onlyAnScoRouteIsBluetoothToThePipeline() {
        assertThat(AudioConfig().bluetoothActive).isFalse()
        assertThat(AudioConfig(routedDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO).bluetoothActive).isTrue()
        assertThat(AudioConfig(routedDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).bluetoothActive).isFalse()
    }

    /**
     * Enumerated from the class rather than written out, so a field stream B adds is covered the
     * moment it exists: a property that does not reach `equals` leaves HumlaService believing the
     * settings did not change, and the pipeline keeps the old value until the next connect.
     */
    @Test
    fun everyConstructorPropertyParticipatesInEquality() {
        val base = AudioConfig()
        val arity = generateSequence(1) { it + 1 }
            .takeWhile { runCatching { AudioConfig::class.java.getMethod("component$it") }.isSuccess }
            .count()
        assertThat(arity).isAtLeast(19)
        val ctor = AudioConfig::class.java.declaredConstructors.single { it.parameterCount == arity }
        val values = (1..arity)
            .map { AudioConfig::class.java.getMethod("component$it").invoke(base) }
            .toTypedArray()

        for (i in 0 until arity) {
            val mutated = values.copyOf()
            mutated[i] = perturb(values[i])
            assertThat(ctor.newInstance(*mutated)).isNotEqualTo(base)
        }
        assertThat(ctor.newInstance(*values)).isEqualTo(base)
    }

    private fun perturb(value: Any?): Any = when (value) {
        null -> 1 // routedDeviceType, the one nullable field: null is "the platform's own route"
        is Boolean -> !value
        is Int -> value + 1
        is Float -> value + 1f
        is String -> value + "-other"
        else -> error("AudioConfig gained a ${value?.javaClass} field; teach this test to vary it")
    }
}
