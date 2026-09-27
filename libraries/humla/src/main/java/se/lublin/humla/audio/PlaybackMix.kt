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

import android.util.Log

private const val TAG = "PlaybackMix"

/**
 * The talkers being played and one mix of them, decoded inline on the playback thread. Reuses its
 * lists, so a mix allocates nothing. Not thread-safe: `AudioOutput` guards it with its packet lock.
 */
internal class PlaybackMix(private val mixer: IAudioMixer<FloatArray, ShortArray> = BasicClippingShortMixer()) {
    private val speeches = ArrayList<AudioOutputSpeech>()
    private val sources = ArrayList<IAudioMixerSource<FloatArray>>()

    val size: Int get() = speeches.size

    fun add(speech: AudioOutputSpeech) {
        speeches.add(speech)
    }

    /**
     * Decodes every talker and mixes the live ones into [buffer]. A talker whose stream has ended
     * is removed and passed to [onEnded], which must destroy it.
     *
     * @return false, with [buffer] untouched, if nobody contributed.
     */
    fun mixInto(buffer: ShortArray, offset: Int, length: Int, onEnded: (AudioOutputSpeech) -> Unit): Boolean {
        sources.clear()
        for (i in speeches.size - 1 downTo 0) {
            val speech = speeches[i]
            val alive = try {
                speech.decode()
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Skip this talker for one mix rather than stop the playback thread.
                Log.e(TAG, "Decoding failed for session ${speech.session}", e)
                continue
            }
            if (alive) {
                sources.add(speech)
            } else {
                speeches.removeAt(i)
                onEnded(speech)
            }
        }
        if (sources.isEmpty()) return false
        mixer.mix(sources, buffer, offset, length)
        return true
    }

    /** Removes every talker, passing each to [onRemoved]. */
    fun clear(onRemoved: (AudioOutputSpeech) -> Unit) {
        speeches.forEach(onRemoved)
        speeches.clear()
        sources.clear()
    }
}
