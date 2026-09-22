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

import android.content.Context
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.exception.AudioException
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.session.AudioConfig
import se.lublin.humla.session.AudioHandlerFactory
import se.lublin.humla.session.AudioSessionParams
import se.lublin.humla.session.ManagedAudio
import se.lublin.humla.util.HumlaLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A pipeline that opens no device; shared by the service tests. */
class FakeAudio : ManagedAudio {
    val shutdownCalls = AtomicInteger()
    @Volatile var shutdownThread: String? = null

    /** Held shut, [shutdown] parks in it - the only way to observe an asynchronous teardown. */
    @Volatile var shutdownGate: CountDownLatch? = null

    // NOT `var warningListener`: a Kotlin `var` generates `setWarningListener`, which collides with
    // the interface method of that name ("platform declaration clash"). Spec 4.05 carries this
    // trap; private field plus a val accessor is the shape that compiles.
    @Volatile private var warningListenerField: ((String) -> Unit)? = null
    val warningListener: ((String) -> Unit)? get() = warningListenerField
    val targetIds = CopyOnWriteArrayList<Byte>()

    override val tcpListener: HumlaTCPMessageListener = object : HumlaTCPMessageListener.Stub() {}
    override val udpListener: HumlaUDPMessageListener = object : HumlaUDPMessageListener.Stub() {}
    override val currentBandwidth: Int = 12_345
    override fun setVoiceTargetId(id: Byte) { targetIds += id }
    override fun setWarningListener(listener: ((String) -> Unit)?) { warningListenerField = listener }

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
        context: Context,
        logger: HumlaLogger,
        config: AudioConfig,
        params: AudioSessionParams,
        encodeListener: AudioHandler.AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener,
    ): ManagedAudio {
        createThreads += Thread.currentThread().name
        configs += config
        sessionParams += params
        failWith?.let { throw it }
        return FakeAudio().also { created += it }
    }
}
