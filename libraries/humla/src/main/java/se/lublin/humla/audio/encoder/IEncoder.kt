/*
 * Copyright (C) 2014 Andrew Comminos
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
package se.lublin.humla.audio.encoder

import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

/** A native audio encoder that buffers frames and serves encoded packets. */
interface IEncoder : AutoCloseable {
    /** The number of frames buffered for the next packet. */
    val bufferedFrames: Int

    /** True once enough audio is encoded to send a packet. */
    val isReady: Boolean

    /** The size of the ready Opus packet in bytes; valid while [isReady]. */
    val encodedLength: Int

    /** Whether the ready packet ends the transmission; valid while [isReady]. */
    val isTerminator: Boolean

    /**
     * Encodes [inputSize] samples of [input].
     * @return the number of bytes encoded.
     * @throws NativeAudioException if encoding failed.
     */
    fun encode(input: ShortArray, inputSize: Int): Int

    /**
     * Appends the ready Opus packet, [encodedLength] bytes, to [packetBuffer] and starts the next
     * one; call only while [isReady].
     * @throws java.nio.BufferUnderflowException if not enough audio is encoded.
     */
    fun getEncodedData(packetBuffer: PacketBuffer)

    /**
     * Ends the transmission: pending frames are encoded, which may make the encoder [isReady].
     * @throws NativeAudioException if encoding failed.
     */
    fun terminate()
}
