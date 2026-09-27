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

import se.lublin.humla.audio.AudioConfig
import se.lublin.humla.audio.AudioHandlerFactory
import se.lublin.humla.audio.AudioHost
import se.lublin.humla.audio.AudioSessionParams
import se.lublin.humla.audio.ManagedAudio
import se.lublin.humla.exception.AudioException
import se.lublin.humla.net.TcpMessageHandler
import se.lublin.humla.net.VoicePacketHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

const val FAKE_BANDWIDTH = 12_345

/** A pipeline that opens no device; shared by the service tests. */
class FakeAudio : ManagedAudio {
    val shutdownCalls = AtomicInteger()
    @Volatile var shutdownThread: String? = null

    /** Held shut, [shutdown] parks in it - the only way to observe an asynchronous teardown. */
    @Volatile var shutdownGate: CountDownLatch? = null

    val targetIds = CopyOnWriteArrayList<Byte>()

    override val tcpHandler = TcpMessageHandler {}
    override val voiceHandler = VoicePacketHandler { }
    override val currentBandwidth: Int = FAKE_BANDWIDTH
    override fun setVoiceTargetId(id: Byte) { targetIds += id }

    override fun shutdown() {
        shutdownThread = Thread.currentThread().name
        shutdownGate?.await()
        shutdownCalls.incrementAndGet()
    }
}

class FakeAudioFactory : AudioHandlerFactory {
    val created = CopyOnWriteArrayList<FakeAudio>()
    val configs = CopyOnWriteArrayList<AudioConfig>()
    val sessionParams = CopyOnWriteArrayList<AudioSessionParams>()
    val createThreads = CopyOnWriteArrayList<String>()
    @Volatile var failWith: AudioException? = null

    override fun create(
        host: AudioHost,
        config: AudioConfig,
        params: AudioSessionParams,
    ): ManagedAudio {
        createThreads += Thread.currentThread().name
        configs += config
        sessionParams += params
        failWith?.let { throw it }
        return FakeAudio().also { created += it }
    }
}
