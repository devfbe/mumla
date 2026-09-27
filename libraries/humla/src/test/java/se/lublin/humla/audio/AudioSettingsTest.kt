package se.lublin.humla.audio

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice

class AudioSettingsTest {
    private fun halfDuplex(requested: Boolean, mode: TransmitMode) =
        AudioSettings(transmitMode = mode, halfDuplex = requested).toAudioConfig(null).halfDuplex

    /** All six corners of {requested} x {the three transmit modes}. */
    @Test
    fun halfDuplexHoldsOnlyWhenItWasRequestedAndTheModeIsPushToTalk() {
        assertThat(halfDuplex(true, TransmitMode.PUSH_TO_TALK)).isTrue()
        assertThat(halfDuplex(true, TransmitMode.VOICE_ACTIVITY)).isFalse()
        assertThat(halfDuplex(true, TransmitMode.CONTINUOUS)).isFalse()
        assertThat(halfDuplex(false, TransmitMode.PUSH_TO_TALK)).isFalse()
        assertThat(halfDuplex(false, TransmitMode.VOICE_ACTIVITY)).isFalse()
        assertThat(halfDuplex(false, TransmitMode.CONTINUOUS)).isFalse()
    }

    @Test
    fun withoutARouteThereIsNoEchoCanceller() {
        val settings = AudioSettings(echoCancellationOverrides = mapOf(AudioDeviceCategory.SPEAKER to true))
        assertThat(settings.toAudioConfig(null).echoCancellation).isEqualTo(EchoCancellationMode.NONE)
    }

    /** A kind of device without an override keeps its default; an override wins for its kind only. */
    @Test
    fun theEchoCancellerFollowsTheOverrideElseTheDefaultOfTheRoutedKind() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val defaults = AudioSettings()
        val overridden = AudioSettings(
            echoCancellationOverrides = mapOf(
                AudioDeviceCategory.SPEAKER to false,
                AudioDeviceCategory.BLUETOOTH to true,
            ),
        )

        assertThat(defaults.toAudioConfig(speaker).echoCancellation).isEqualTo(EchoCancellationMode.WEBRTC)
        assertThat(defaults.toAudioConfig(sco).echoCancellation).isEqualTo(EchoCancellationMode.NONE)
        assertThat(overridden.toAudioConfig(speaker).echoCancellation).isEqualTo(EchoCancellationMode.NONE)
        assertThat(overridden.toAudioConfig(sco).echoCancellation).isEqualTo(EchoCancellationMode.WEBRTC)
        assertThat(overridden.toAudioConfig(earpiece).echoCancellation).isEqualTo(EchoCancellationMode.WEBRTC)
    }

    /** Only what a pipeline is built from ends up in the config; the rest changes nothing there. */
    @Test
    fun theLiveSettingsDoNotReachTheAudioConfig() {
        val base = AudioSettings()
        val live = base.copy(
            vad = VadConfig.amplitude(0.2f),
            preferredDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
            halfDuplex = true,
            echoCancellationOverrides = mapOf(AudioDeviceCategory.WIRED to true),
        )
        assertThat(live.toAudioConfig(null)).isEqualTo(base.toAudioConfig(null))
        assertThat(live.toAudioConfig(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            .isEqualTo(base.toAudioConfig(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}
