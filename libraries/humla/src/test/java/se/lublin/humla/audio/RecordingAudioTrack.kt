package se.lublin.humla.audio

import android.media.AudioTrack
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CopyOnWriteArrayList

sealed interface TrackEvent
data class Write(val samples: Int, val silent: Boolean) : TrackEvent
data object Pause : TrackEvent
data object Flush : TrackEvent

/** The track calls of every [AudioTrack] in the test, in order; Robolectric drops `short` writes otherwise. */
val trackEvents = CopyOnWriteArrayList<TrackEvent>()

/** Records into [trackEvents] what the playback thread does to its track. */
@Suppress("ProtectedMemberInFinalClass") // Robolectric matches the framework's own signatures
@Implements(AudioTrack::class)
class RecordingAudioTrack : ShadowAudioTrack() {
    @Suppress("FunctionNaming", "UnusedParameter")
    @Implementation
    protected fun native_write_short(
        audioData: ShortArray,
        offsetInShorts: Int,
        sizeInShorts: Int,
        format: Int,
        isBlocking: Boolean,
    ): Int {
        val silent = (offsetInShorts until offsetInShorts + sizeInShorts).all { audioData[it] == 0.toShort() }
        trackEvents += Write(sizeInShorts, silent)
        return sizeInShorts
    }

    @Suppress("FunctionNaming")
    @Implementation
    protected fun native_pause() {
        trackEvents += Pause
    }

    @Implementation
    override fun flush() {
        trackEvents += Flush
        super.flush()
    }
}
