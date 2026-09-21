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

package se.lublin.mumla.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import se.lublin.humla.exception.AudioInitializationException

/**
 * Blocking 16-bit mono PCM playback, the one hardware edge of the loopback monitor.
 *
 * **Why this lives in the app and not in humla.** The plan gave the playback seam to task B10
 * (`AudioOutput`), and task B10 was cancelled -- `PcmPlaybackSink` does not exist anywhere in the
 * library, which the brief for this task assumed it would. Rather than claim a seam a cancelled
 * task owned, the monitor brings its own: it is a dozen lines, it is only ever used by the settings
 * screen, and if `AudioOutput` is ever rewritten it can take this over without a migration.
 */
interface PcmPlaybackSink {
    fun play()

    /** @return how many samples were written, or a negative error code. */
    fun write(buffer: ShortArray, length: Int): Int

    fun pause()
    fun flush()
    fun stop()
    fun release()
}

fun interface PcmPlaybackSinkFactory {
    @Throws(AudioInitializationException::class)
    fun open(audioStream: Int, sampleRate: Int): PcmPlaybackSink
}

/** The real sink: one `AudioTrack` in streaming mode. */
class AndroidAudioTrackSink internal constructor(private val track: AudioTrack) : PcmPlaybackSink {
    override fun play() = track.play()

    override fun write(buffer: ShortArray, length: Int): Int = track.write(buffer, 0, length)

    override fun pause() = track.pause()

    override fun flush() = track.flush()

    override fun stop() = track.stop()

    override fun release() = track.release()

    class Factory : PcmPlaybackSinkFactory {
        @Throws(AudioInitializationException::class)
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
