/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import se.lublin.humla.exception.AudioInitializationException

/** Blocking 16-bit mono PCM playback for the [CapturePreview]'s loopback. */
public interface PcmPlaybackSink {
    public fun play()

    /** @return how many samples were written, or a negative error code. */
    public fun write(buffer: ShortArray, length: Int): Int

    public fun pause()
    public fun flush()
    public fun stop()
    public fun release()
}

public fun interface PcmPlaybackSinkFactory {
    public fun open(audioStream: Int, sampleRate: Int): PcmPlaybackSink
}

/** The real sink: one `AudioTrack` in streaming mode. */
public class AndroidAudioTrackSink internal constructor(private val track: AudioTrack) : PcmPlaybackSink {
    override fun play(): Unit = track.play()

    override fun write(buffer: ShortArray, length: Int): Int = track.write(buffer, 0, length)

    override fun pause(): Unit = track.pause()

    override fun flush(): Unit = track.flush()

    override fun stop(): Unit = track.stop()

    override fun release(): Unit = track.release()

    public class Factory : PcmPlaybackSinkFactory {
        override fun open(audioStream: Int, sampleRate: Int): PcmPlaybackSink {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                throw AudioInitializationException("no AudioTrack at $sampleRate Hz")
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setLegacyStreamType(audioStream)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw AudioInitializationException("AudioTrack did not initialise at $sampleRate Hz")
            }
            return AndroidAudioTrackSink(track)
        }
    }
}
