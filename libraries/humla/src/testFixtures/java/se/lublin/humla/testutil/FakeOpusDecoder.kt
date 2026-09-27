/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla.testutil

import se.lublin.humla.audio.AudioHandler
import se.lublin.humla.audio.native.OpusDecoderApi

/**
 * Opus without the native library: every packet has [framesPerPacket] frames of
 * [samplesPerFrame] and decodes to one frame, filled with [fill] unless it is null. Allocates
 * nothing per call.
 */
internal class FakeOpusDecoder(
    private val framesPerPacket: Int = 1,
    private val samplesPerFrame: Int = AudioHandler.FRAME_SIZE,
    private val fill: Float? = null,
) : OpusDecoderApi {
    var destroys = 0
        private set

    override fun create(sampleRate: Int, channels: Int, error: IntArray): Long {
        error[0] = 0
        return 1L
    }

    override fun decodeFloat(
        state: Long,
        data: ByteArray?,
        offset: Int,
        len: Int,
        out: FloatArray,
        frameSize: Int,
        decodeFec: Int,
    ): Int {
        if (fill != null) out.fill(fill, 0, AudioHandler.FRAME_SIZE)
        return AudioHandler.FRAME_SIZE
    }

    override fun destroy(state: Long) {
        destroys++
    }

    override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = framesPerPacket

    override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = samplesPerFrame
}
