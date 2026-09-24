@file:Suppress("DEPRECATION") // see AudioConfig.kt

package se.lublin.humla.session

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.Constants

/**
 * [AudioConfig] carries two decisions of its own: the half-duplex rule and, because HumlaService
 * reconfigures the pipeline on `config != previous`, structural equality over every field it holds.
 */
class AudioConfigTest {
    private fun config(requested: Boolean, transmitMode: Int) =
        AudioConfig(halfDuplexRequested = requested, transmitMode = transmitMode)

    /**
     * All six corners of the two inputs the property reads: {requested} x {the three transmit
     * modes}.
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
     * The request survives a mode that suppresses it, so a settings write that changes only the
     * transmit mode later cannot lose it.
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

    /**
     * Enumerated from the class, so a new field is covered automatically: a property that does not
     * reach `equals` would leave HumlaService believing the settings did not change.
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
