package se.lublin.humla.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioConfigTest {
    /**
     * A media-stream track does not follow the communication device, so a routed device - any of
     * them, not only a headset - moves playback to the voice-call stream, and only an unrouted
     * session keeps the stream the settings chose.
     */
    @Test
    fun aRoutedDeviceMovesPlaybackToTheVoiceCallStream() {
        val unrouted = AudioConfig(PipelineSettings(audioStream = AudioManager.STREAM_ALARM))
        assertThat(unrouted.playbackStream).isEqualTo(AudioManager.STREAM_ALARM)
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
}
