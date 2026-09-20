package se.lublin.humla.session

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.MessageHandlerRegistry
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener
import se.lublin.humla.testutil.SilentLogger
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.util.HumlaLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Covers spec A2 for [AudioController]: the pipeline is created and torn down on the
 * "humla-audio-control" thread, no public method joins on its caller, and every [Listener]
 * callback arrives on the handler the controller was given.
 */
@RunWith(RobolectricTestRunner::class)
class AudioControllerTest {
    private val mainLooper = shadowOf(Looper.getMainLooper())

    private class FakeAudio : ManagedAudio {
        val shutdownCalls = AtomicInteger()
        @Volatile var shutdownThread: Thread? = null

        /**
         * Held while the fake is inside [shutdown], to take an assertion in the window where a
         * synchronous controller would still be blocked. Bounded on purpose: an unbounded await
         * turns a synchronous implementation into a hung suite instead of a red test (spec 4.05,
         * "a removed guard can hang the suite instead of failing it").
         */
        @Volatile var shutdownGate: CountDownLatch? = null
        /**
         * Backed by a private field with a read-only accessor, never by a `var`: `var
         * warningListener` generates `setWarningListener(Function1)` and clashes with the
         * interface's own method (spec 4.05). The brief's listing for this task and the one for
         * task 9b both have the `var`; both refuse to compile.
         */
        @Volatile private var warning: ((String) -> Unit)? = null
        val warningListener: ((String) -> Unit)? get() = warning
        val targetIds = CopyOnWriteArrayList<Byte>()
        override val tcpListener: HumlaTCPMessageListener = object : HumlaTCPMessageListener.Stub() {}
        override val udpListener: HumlaUDPMessageListener = object : HumlaUDPMessageListener.Stub() {}
        override val currentBandwidth: Int = 12_345
        override fun setVoiceTargetId(id: Byte) { targetIds += id }
        override fun setWarningListener(listener: ((String) -> Unit)?) { warning = listener }
        override fun shutdown() {
            shutdownThread = Thread.currentThread()
            shutdownGate?.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            shutdownCalls.incrementAndGet()
        }
    }

    private class FakeFactory : AudioHandlerFactory {
        val created = CopyOnWriteArrayList<FakeAudio>()
        val createThreads = CopyOnWriteArrayList<Thread>()
        val contexts = CopyOnWriteArrayList<Context>()
        val loggers = CopyOnWriteArrayList<HumlaLogger>()
        val configs = CopyOnWriteArrayList<AudioConfig>()
        val sessionParams = CopyOnWriteArrayList<AudioSessionParams>()
        val encodeListeners = CopyOnWriteArrayList<AudioHandler.AudioEncodeListener>()
        val outputListeners = CopyOnWriteArrayList<AudioOutput.AudioOutputListener>()
        @Volatile var failWith: Exception? = null

        override fun create(
            context: Context, logger: HumlaLogger, config: AudioConfig, params: AudioSessionParams,
            encodeListener: AudioHandler.AudioEncodeListener, outputListener: AudioOutput.AudioOutputListener,
        ): ManagedAudio {
            createThreads += Thread.currentThread()
            contexts += context
            loggers += logger
            configs += config
            sessionParams += params
            encodeListeners += encodeListener
            outputListeners += outputListener
            failWith?.let { throw it }
            return FakeAudio().also { created += it }
        }
    }

    private class FakeRegistry : MessageHandlerRegistry {
        val tcp = CopyOnWriteArrayList<HumlaTCPMessageListener>()
        val udp = CopyOnWriteArrayList<HumlaUDPMessageListener>()
        override fun addTCPMessageHandlers(vararg handlers: HumlaTCPMessageListener) { tcp += handlers }
        override fun removeTCPMessageHandler(handler: HumlaTCPMessageListener) { tcp.remove(handler) }
        override fun addUDPMessageHandlers(vararg handlers: HumlaUDPMessageListener) { udp += handlers }
        override fun removeUDPMessageHandler(handler: HumlaUDPMessageListener) { udp.remove(handler) }
    }

    private class RecordingListener : AudioController.Listener {
        val started = AtomicInteger()
        val failures = CopyOnWriteArrayList<String>()
        val warnings = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        override fun onAudioStarted() { started.incrementAndGet(); threads += Thread.currentThread() }
        override fun onAudioFailed(message: String) { failures += message; threads += Thread.currentThread() }
        override fun onAudioWarning(message: String) { warnings += message; threads += Thread.currentThread() }
    }

    private val factory = FakeFactory()
    private val registry = FakeRegistry()
    private val listener = RecordingListener()
    private val context = RuntimeEnvironment.getApplication()
    private val encodeListener = object : AudioHandler.AudioEncodeListener {
        override fun onAudioEncoded(data: ByteArray, length: Int) = Unit
        override fun onTalkingStateChanged(talking: Boolean) = Unit
    }
    private val outputListener = object : AudioOutput.AudioOutputListener {
        override fun onUserTalkStateUpdated(user: User?) = Unit
        override fun getUser(session: Int): User? = null
    }
    private val controller = newController()
    private val params = AudioSessionParams(User(1, "me"), -1, HumlaUDPMessageType.UDPVoiceOpus, 0, ContinuousInputMode())

    private fun newController(mainHandler: Handler = Handler(Looper.getMainLooper())) = AudioController(
        context, SilentLogger, { factory }, encodeListener, outputListener, listener, mainHandler,
    )

    @After
    fun tearDown() {
        controller.quit()
    }

    /** Waits for the control thread to have posted something to main, then runs it. */
    private fun idleMainWhenSomethingIsPosted() {
        awaitUntil(description = "main looper task") { !mainLooper.isIdle }
        mainLooper.idle()
    }

    /** Drains the control thread's queue; a post that has already returned has then been executed. */
    private fun idleControlThread() = shadowOf(controller.looper).idle()

    private fun startAndAwaitRunning(config: AudioConfig = AudioConfig()): FakeAudio {
        controller.start(config, params, registry)
        awaitUntil(description = "pipeline running") { controller.isRunning }
        return factory.created.last()
    }

    @Test
    fun startCreatesAudioOnTheControlThreadAndRegistersItsHandlers() {
        controller.start(AudioConfig(amplitudeBoost = 7f), params, registry)

        awaitUntil(description = "pipeline running") { controller.isRunning }
        assertThat(factory.createThreads).containsExactly(controller.looper.thread)
        assertThat(controller.looper.thread.name).isEqualTo(AudioController.THREAD_NAME)
        assertThat(factory.contexts).containsExactly(context)
        assertThat(factory.loggers).containsExactly(SilentLogger)
        assertThat(factory.encodeListeners).containsExactly(encodeListener)
        assertThat(factory.outputListeners).containsExactly(outputListener)
        assertThat(factory.configs[0].amplitudeBoost).isEqualTo(7f)
        assertThat(factory.sessionParams).containsExactly(params)
        assertThat(registry.tcp).containsExactly(factory.created[0].tcpListener)
        assertThat(registry.udp).containsExactly(factory.created[0].udpListener)
        assertThat(controller.currentBandwidth).isEqualTo(12_345)

        idleMainWhenSomethingIsPosted()
        assertThat(listener.started.get()).isEqualTo(1)
        assertThat(listener.threads).containsExactly(Looper.getMainLooper().thread)
    }

    @Test
    fun shutdownReturnsWhileAudioIsStillTearingDown() {
        val audio = startAndAwaitRunning()
        val gate = CountDownLatch(1)
        audio.shutdownGate = gate

        val start = System.nanoTime()
        controller.shutdown()
        val callerBlockedMillis = (System.nanoTime() - start) / 1_000_000

        awaitUntil(description = "shutdown started on control thread") { audio.shutdownThread != null }
        // The structural statement of "returns immediately", and the one that must fire first: the
        // test thread is here while the control thread is still inside ManagedAudio.shutdown().
        assertThat(audio.shutdownCalls.get()).isEqualTo(0)
        assertThat(audio.shutdownThread).isSameInstanceAs(controller.looper.thread)
        // Taken inside the window, which is the only place the order is visible: the handlers are
        // already unregistered while the pipeline is still stopping, so nothing routes a packet
        // into a dying decoder. After the teardown returns, both orders look the same.
        assertThat(registry.tcp).isEmpty()
        assertThat(registry.udp).isEmpty()
        assertThat(audio.warningListener).isNull()
        assertThat(callerBlockedMillis).isLessThan(GATE_TIMEOUT_SECONDS * 1_000)

        gate.countDown()
        awaitUntil(description = "shutdown finished") { audio.shutdownCalls.get() == 1 }
        assertThat(registry.tcp).isEmpty()
        assertThat(registry.udp).isEmpty()
        assertThat(audio.warningListener).isNull()
        assertThat(controller.isRunning).isFalse()
        assertThat(controller.currentBandwidth).isEqualTo(-1)
    }

    @Test
    fun reconfigureRecreatesRunningAudioWithTheNewConfigAndInputMode() {
        startAndAwaitRunning()
        val newInputMode = ContinuousInputMode()

        controller.reconfigure(AudioConfig(amplitudeBoost = 2f), newInputMode)

        awaitUntil(description = "second pipeline") { factory.created.size == 2 }
        idleControlThread()
        assertThat(factory.created[0].shutdownCalls.get()).isEqualTo(1)
        assertThat(factory.configs[1].amplitudeBoost).isEqualTo(2f)
        assertThat(factory.sessionParams[1].inputMode).isSameInstanceAs(newInputMode)
        assertThat(factory.sessionParams[1].self).isSameInstanceAs(params.self)
        assertThat(registry.tcp).containsExactly(factory.created[1].tcpListener)
        assertThat(registry.udp).containsExactly(factory.created[1].udpListener)
    }

    /**
     * All four corners of the two inputs the short-circuit reads, not the two mutations its two
     * clauses would suggest (spec 4.04: 2^k inputs). The (different, different) corner is
     * [reconfigureRecreatesRunningAudioWithTheNewConfigAndInputMode].
     */
    @Test
    fun reconfigureRebuildsOnlyWhenTheConfigOrTheInputModeReallyDiffers() {
        val config = AudioConfig(amplitudeBoost = 5f)
        val inputMode = ContinuousInputMode()
        controller.start(config, params.copy(inputMode = inputMode), registry)
        awaitUntil(description = "pipeline running") { controller.isRunning }

        // same, same
        controller.reconfigure(config.copy(), inputMode)
        idleControlThread()
        assertThat(factory.created).hasSize(1)

        // different config, same input mode
        controller.reconfigure(config.copy(amplitudeBoost = 6f), inputMode)
        awaitUntil(description = "rebuilt for the config") { factory.created.size == 2 }

        // same as the last reconfigure, same input mode: this is the corner that reads the config
        // reconfigure() STORED, not the one start() stored. Without `session = next` above, the
        // comparison is against the start config and this rebuilds (measured: that mutation
        // survived the whole suite before this assertion existed).
        controller.reconfigure(config.copy(amplitudeBoost = 6f), inputMode)
        idleControlThread()
        assertThat(factory.created).hasSize(2)

        // same config, different input mode
        controller.reconfigure(config.copy(amplitudeBoost = 6f), ContinuousInputMode())
        awaitUntil(description = "rebuilt for the input mode") { factory.created.size == 3 }
    }

    @Test
    fun reconfigureAndVoiceTargetBeforeStartCreateNothingAndLeaveTheControlThreadUsable() {
        controller.reconfigure(AudioConfig(amplitudeBoost = 9f), ContinuousInputMode())
        controller.setVoiceTargetId(3)

        idleControlThread()
        assertThat(factory.created).isEmpty()

        // The control thread must have survived both no-ops, or every later post is dropped in
        // silence rather than failing.
        startAndAwaitRunning()
        assertThat(factory.configs).hasSize(1)
        assertThat(factory.configs[0].amplitudeBoost).isEqualTo(1f)
        assertThat(factory.sessionParams[0].targetId).isEqualTo(0.toByte())
    }

    @Test
    fun startWhileRunningReplacesThePipelineAndUnregistersFromTheOldRegistry() {
        val first = startAndAwaitRunning()
        val secondRegistry = FakeRegistry()

        controller.start(AudioConfig(amplitudeBoost = 4f), params, secondRegistry)

        awaitUntil(description = "second pipeline") { factory.created.size == 2 }
        idleControlThread()
        assertThat(first.shutdownCalls.get()).isEqualTo(1)
        assertThat(registry.tcp).isEmpty()
        assertThat(registry.udp).isEmpty()
        assertThat(secondRegistry.tcp).containsExactly(factory.created[1].tcpListener)
        assertThat(secondRegistry.udp).containsExactly(factory.created[1].udpListener)
    }

    @Test
    fun voiceTargetIsForwardedAndSurvivesRecreation() {
        val audio = startAndAwaitRunning()

        controller.setVoiceTargetId(5)
        controller.reconfigure(AudioConfig(amplitudeBoost = 3f), ContinuousInputMode())

        awaitUntil(description = "second pipeline") { factory.created.size == 2 }
        assertThat(audio.targetIds).containsExactly(5.toByte())
        // "Survives recreation" along the axis that matters: the pipeline reconfigure() builds is
        // built WITH the target id, not merely told about it afterwards. Without the
        // params.copy(targetId = id) in setVoiceTargetId this assertion fails on its own.
        assertThat(factory.sessionParams[1].targetId).isEqualTo(5.toByte())
        assertThat(factory.sessionParams[0].targetId).isEqualTo(0.toByte())
    }

    @Test
    fun creationFailureIsReportedOnMainAndLeavesNothingRegistered() {
        factory.failWith = AudioInitializationException("no microphone")

        controller.start(AudioConfig(), params, registry)

        idleMainWhenSomethingIsPosted()
        assertThat(listener.failures).containsExactly("no microphone")
        assertThat(listener.threads).containsExactly(Looper.getMainLooper().thread)
        assertThat(registry.tcp).isEmpty()
        assertThat(registry.udp).isEmpty()
        assertThat(controller.isRunning).isFalse()
        assertThat(ShadowLog.getLogsForTag(AudioController.TAG).map { it.msg })
            .contains("Audio initialization failed")
    }

    /**
     * A pipeline that never started is not a running one, so a settings change must not silently
     * build one behind the user's back. This is the pre-existing behaviour: HumlaService.java
     * reloaded the audio subsystem only `if (mAudioHandler != null && isInitialized())`, and a
     * failed AudioHandler.Builder.initialize left mAudioHandler null.
     */
    @Test
    fun reconfigureAfterAFailedStartDoesNotRetry() {
        factory.failWith = AudioInitializationException("no microphone")
        controller.start(AudioConfig(), params, registry)
        awaitUntil(description = "first attempt") { factory.createThreads.size == 1 }
        factory.failWith = null

        controller.reconfigure(AudioConfig(amplitudeBoost = 2f), ContinuousInputMode())

        idleControlThread()
        assertThat(factory.createThreads).hasSize(1)
        assertThat(factory.created).isEmpty()
        assertThat(controller.isRunning).isFalse()
    }

    /** A pipeline that dies on a RuntimeException must not take the control thread with it. */
    @Test
    fun aRuntimeFailureIsReportedAndTheControllerStaysUsable() {
        factory.failWith = IllegalStateException()
        controller.start(AudioConfig(), params, registry)
        idleMainWhenSomethingIsPosted()
        assertThat(listener.failures).containsExactly("IllegalStateException")

        factory.failWith = null
        startAndAwaitRunning()
        assertThat(controller.isRunning).isTrue()
    }

    /**
     * The warning is raised from a foreign thread, because that is where it comes from - the
     * capture thread, not the caller. Raised from the test thread, which *is* the main thread
     * under Robolectric, a controller that called the listener inline would look identical.
     */
    @Test
    fun warningsFromAudioReachTheListenerOnMain() {
        val audio = startAndAwaitRunning()
        idleMainWhenSomethingIsPosted() // onAudioStarted

        thread(name = "fake-capture") { audio.warningListener!!.invoke("microphone silenced by the system") }.join()

        assertThat(listener.warnings).isEmpty()
        idleMainWhenSomethingIsPosted()
        assertThat(listener.warnings).containsExactly("microphone silenced by the system")
        assertThat(listener.threads).containsExactly(Looper.getMainLooper().thread, Looper.getMainLooper().thread)
    }

    /**
     * The callbacks go to the handler the controller was constructed with. Pinning "not the caller's
     * thread" alone would pass for a controller that hard-codes the main looper.
     */
    @Test
    fun listenerCallbacksRunOnTheSuppliedHandlerRatherThanTheMainLooper() {
        val callbackThread = HandlerThread("test-callbacks").apply { start() }
        val other = newController(Handler(callbackThread.looper))
        try {
            other.start(AudioConfig(), params, registry)
            awaitUntil(description = "started callback") { listener.started.get() == 1 }
            val audio = factory.created[0]
            thread(name = "fake-capture") { audio.warningListener!!.invoke("silenced") }.join()
            awaitUntil(description = "warning callback") { listener.warnings.isNotEmpty() }

            factory.failWith = AudioInitializationException("no microphone")
            other.start(AudioConfig(), params, registry)
            awaitUntil(description = "failure callback") { listener.failures.isNotEmpty() }

            assertThat(listener.threads).containsExactly(callbackThread, callbackThread, callbackThread)
            assertThat(mainLooper.isIdle).isTrue()
        } finally {
            other.quit()
            callbackThread.quitSafely()
        }
    }

    @Test
    fun shutdownWithNothingRunningIsHarmlessAndTheControllerStillStarts() {
        controller.shutdown()
        idleControlThread()

        startAndAwaitRunning()
        assertThat(controller.isRunning).isTrue()
    }

    @Test
    fun quitTearsDownTheRunningPipelineBeforeStoppingTheControlThread() {
        val audio = startAndAwaitRunning()

        controller.quit()

        awaitUntil(description = "control thread stopped") { !controller.thread.isAlive }
        // HandlerThread.getLooper() answers null once the thread is gone; the Handler still holds it.
        assertThat(controller.looper).isNotNull()
        assertThat(audio.shutdownCalls.get()).isEqualTo(1)
        assertThat(registry.tcp).isEmpty()
        assertThat(registry.udp).isEmpty()
    }

    private companion object {
        /** Long enough that a caller who joins is unmistakable, short enough to fail rather than hang. */
        const val GATE_TIMEOUT_SECONDS = 5L
    }
}
