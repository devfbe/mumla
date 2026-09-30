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

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.SelfTestPhase
import se.lublin.humla.audio.SelfTestReading
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.DoubleTalkTestUiState.Status
import java.io.IOException

/**
 * The self-test's state machine: start, stop and retry, the lamp from the gate, the strength
 * applied live and stored on release, and the microphone released when the screen goes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DoubleTalkTestViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = Settings.getInstance(context)
    private val engines = mutableListOf<FakeEngine>()
    private var failure: Exception? = null
    private var onReading: ((SelfTestReading) -> Unit)? = null

    private class FakeEngine(override val echoCancelled: Boolean) : SelfTestEngine {
        val calls = mutableListOf<String>()
        val limits = mutableListOf<Float>()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun setAttenuationLimitDb(limitDb: Float) {
            limits += limitDb
        }
    }

    private fun model(echoCancelled: Boolean = true, worker: CoroutineDispatcher = UnconfinedTestDispatcher()) =
        DoubleTalkTestViewModel(
            settings,
            { reading ->
                onReading = reading
                failure?.let { throw it }
                FakeEngine(echoCancelled).also { engines += it }
            },
            worker,
        )

    private fun reading(voice: Boolean, phase: SelfTestPhase = SelfTestPhase.TALK, heard: Int? = null) =
        SelfTestReading(
            MeterReading(-20f, -60f, -20f, -40f, voice, holding = false, tooClose = false),
            phase, voicePlaying = true, heardPercent = heard, falseOpenPercent = 3,
        )

    @Test
    fun `a new screen is idle, dark, and shows the stored strength`() {
        settings.rnnoiseStrength = 24
        val model = model()

        assertThat(model.state.value.status).isEqualTo(Status.IDLE)
        assertThat(model.state.value.lampOn).isFalse()
        assertThat(model.state.value.strength).isEqualTo(24)
        assertThat(model.state.value.strengthIsDefault).isFalse()
        assertThat(engines).isEmpty()
    }

    @Test
    fun `start runs one engine with the current strength`() {
        settings.rnnoiseStrength = Settings.RNNOISE_LIMIT_UNLIMITED
        val model = model()

        model.start()
        model.start()

        assertThat(model.state.value.status).isEqualTo(Status.RUNNING)
        assertThat(engines).hasSize(1)
        assertThat(engines[0].calls).containsExactly("start")
        assertThat(engines[0].limits).containsExactly(Float.POSITIVE_INFINITY)
    }

    @Test
    fun `the lamp follows the gate while running, and goes dark on stop`() {
        val model = model()
        model.start()

        onReading!!(reading(voice = true))
        assertThat(model.state.value.lampOn).isTrue()
        onReading!!(reading(voice = false))
        assertThat(model.state.value.lampOn).isFalse()
        onReading!!(reading(voice = true, heard = 87))
        assertThat(model.state.value.heardPercent).isEqualTo(87)
        assertThat(model.state.value.falseOpenPercent).isEqualTo(3)

        model.stop()

        assertThat(model.state.value.status).isEqualTo(Status.STOPPED)
        assertThat(model.state.value.lampOn).isFalse()
        assertThat(engines[0].calls).containsExactly("start", "stop").inOrder()
        // The result stays on screen after the stop.
        assertThat(model.state.value.heardPercent).isEqualTo(87)
    }

    /** The capture thread may deliver one more reading after the stop; it must not light the lamp. */
    @Test
    fun `a reading that arrives after the stop is dropped`() {
        val model = model()
        model.start()
        model.stop()

        onReading!!(reading(voice = true))

        assertThat(model.state.value.lampOn).isFalse()
        assertThat(model.state.value.meter).isNull()
    }

    @Test
    fun `retry stops the old engine, starts a fresh one and clears the result`() {
        val model = model()
        model.start()
        onReading!!(reading(voice = true, heard = 50))

        model.retry()

        assertThat(engines).hasSize(2)
        assertThat(engines[0].calls).containsExactly("start", "stop").inOrder()
        assertThat(engines[1].calls).containsExactly("start")
        assertThat(model.state.value.status).isEqualTo(Status.RUNNING)
        assertThat(model.state.value.heardPercent).isNull()
        assertThat(model.state.value.phase).isEqualTo(SelfTestPhase.LISTEN)
    }

    /** Stop tapped while the voice is still being decoded: the engine that comes up is stopped. */
    @Test
    fun `a stop before the engine is up leaves nothing running`() {
        val worker = StandardTestDispatcher()
        val model = model(worker = worker)

        model.start()
        model.stop()
        worker.scheduler.advanceUntilIdle()

        assertThat(model.state.value.status).isEqualTo(Status.STOPPED)
        assertThat(engines.single().calls).containsExactly("start", "stop").inOrder()
    }

    @Test
    fun `an engine that cannot open the microphone makes the test unavailable`() {
        failure = AudioInitializationException("busy")
        val model = model()

        model.start()

        assertThat(model.state.value.status).isEqualTo(Status.UNAVAILABLE)
        assertThat(model.isRunning).isFalse()
    }

    @Test
    fun `a voice that cannot be decoded makes the test unavailable`() {
        failure = IOException("no codec")
        val model = model()

        model.start()

        assertThat(model.state.value.status).isEqualTo(Status.UNAVAILABLE)
    }

    @Test
    fun `without echo cancellation the state says so`() {
        val model = model(echoCancelled = false)
        model.start()

        assertThat(model.state.value.echoCancelled).isFalse()
    }

    /** Applied at once for the ear, stored only when the user lets go: no write per slider step. */
    @Test
    fun `the strength is applied live while dragging and stored on release`() {
        val model = model()
        model.start()

        model.previewStrength(10)
        model.previewStrength(12)
        assertThat(engines[0].limits.takeLast(2)).containsExactly(10f, 12f).inOrder()
        assertThat(settings.rnnoiseStrength).isEqualTo(18)
        assertThat(model.state.value.strength).isEqualTo(12)

        model.commitStrength(Settings.RNNOISE_LIMIT_UNLIMITED)

        assertThat(settings.rnnoiseStrength).isEqualTo(Settings.RNNOISE_LIMIT_UNLIMITED)
        assertThat(engines[0].limits.last()).isEqualTo(Float.POSITIVE_INFINITY)
        assertThat(model.state.value.strengthIsDefault).isFalse()
    }

    @Test
    fun `reset puts the strength back to 18 dB, stored and applied`() {
        settings.rnnoiseStrength = 40
        val model = model()
        model.start()

        model.resetStrength()

        assertThat(settings.rnnoiseStrength).isEqualTo(18)
        assertThat(engines[0].limits.last()).isEqualTo(18f)
        assertThat(model.state.value.strength).isEqualTo(18)
        assertThat(model.state.value.strengthIsDefault).isTrue()
    }

    @Test
    fun `a strength stored elsewhere is picked up`() {
        val model = model()
        model.start()

        settings.rnnoiseStrength = 30
        model.syncStrength()

        assertThat(model.state.value.strength).isEqualTo(30)
        assertThat(engines[0].limits.last()).isEqualTo(30f)
    }

    /** The screen went away mid-test: the microphone and the audio mode are given back. */
    @Test
    fun `clearing the model stops a running test`() {
        val store = ViewModelStore()
        val made = model()
        val factory = viewModelFactory { initializer { made } }
        val model = ViewModelProvider(store, factory)[DoubleTalkTestViewModel::class.java]
        model.start()

        store.clear()

        assertThat(engines[0].calls).containsExactly("start", "stop").inOrder()
    }
}
