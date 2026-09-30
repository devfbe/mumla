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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.SelfTestPhase
import se.lublin.humla.audio.SelfTestReading
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.mumla.Settings
import java.io.IOException

/** One run of the double-talk self-test; see `se.lublin.humla.audio.DoubleTalkSelfTest`. */
interface SelfTestEngine {
    /** Whether the echo canceller runs in this test. */
    val echoCancelled: Boolean

    /** @throws AudioInitializationException when the microphone or the speaker cannot be opened. */
    fun start()

    fun stop()

    fun setAttenuationLimitDb(limitDb: Float)
}

/** Builds a test from the current settings; called off the main thread (it decodes the voice). */
fun interface SelfTestEngineFactory {
    /** @throws IOException when the test voice cannot be decoded. */
    fun create(onReading: (SelfTestReading) -> Unit): SelfTestEngine
}

/** What the self-test screen shows. */
data class DoubleTalkTestUiState(
    val status: Status = Status.IDLE,
    val meter: MeterReading? = null,
    val phase: SelfTestPhase = SelfTestPhase.LISTEN,
    val heardPercent: Int? = null,
    val falseOpenPercent: Int? = null,
    /** The noise reduction strength as the slider stores it; see [Settings.rnnoiseStrength]. */
    val strength: Int,
    val strengthIsDefault: Boolean,
    val echoCancelled: Boolean = true,
) {
    enum class Status { IDLE, STARTING, RUNNING, STOPPED, UNAVAILABLE }

    /** The lamp: on while the test runs and the app would transmit the user. */
    val lampOn: Boolean get() = status == Status.RUNNING && meter?.voice == true
}

/**
 * The self-test's state machine: idle, starting, running, stopped (with the last results) or
 * unavailable. The audio work runs on [worker], one task at a time, so a start and a stop never
 * overlap; readings arrive on the test's capture thread and land in [state].
 *
 * The strength slider applies to a running test at once, and is stored when the user lets go
 * ([commitStrength]), not on every step.
 */
class DoubleTalkTestViewModel(
    private val settings: Settings,
    private val engines: SelfTestEngineFactory,
    private val worker: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : ViewModel() {
    private val _state = MutableStateFlow(
        DoubleTalkTestUiState(strength = settings.rnnoiseStrength, strengthIsDefault = settings.isRnnoiseStrengthDefault),
    )
    val state: StateFlow<DoubleTalkTestUiState> = _state.asStateFlow()

    /** The running test; written on [worker], read anywhere. */
    @Volatile
    private var engine: SelfTestEngine? = null

    val isRunning: Boolean
        get() = _state.value.status.let {
            it == DoubleTalkTestUiState.Status.RUNNING || it == DoubleTalkTestUiState.Status.STARTING
        }

    fun start() {
        if (isRunning) return
        _state.update { it.cleared(DoubleTalkTestUiState.Status.STARTING) }
        viewModelScope.launch(worker) { startEngine() }
    }

    /** Stops the test, keeping the last results on screen. */
    fun stop() {
        if (!isRunning) return
        _state.update { it.copy(status = DoubleTalkTestUiState.Status.STOPPED, meter = null) }
        viewModelScope.launch(worker) { stopEngine() }
    }

    /** Starts over from the listening phase, with the current settings. */
    fun retry() {
        _state.update { it.cleared(DoubleTalkTestUiState.Status.STARTING) }
        viewModelScope.launch(worker) {
            stopEngine()
            startEngine()
        }
    }

    /** The slider moved: applied to a running test at once, and shown; not stored yet. */
    fun previewStrength(strength: Int) {
        _state.update { it.copy(strength = strength, strengthIsDefault = strength == DEFAULT_STRENGTH) }
        engine?.setAttenuationLimitDb(Settings.rnnoiseLimitDbOf(strength))
    }

    /** The user let go of the slider: stored, which a session would apply like any audio setting. */
    fun commitStrength(strength: Int) {
        previewStrength(strength)
        settings.rnnoiseStrength = strength
    }

    fun resetStrength() = commitStrength(DEFAULT_STRENGTH)

    /** The stored strength changed elsewhere (the settings screen): show it and use it. */
    fun syncStrength() {
        val stored = settings.rnnoiseStrength
        if (stored != _state.value.strength) previewStrength(stored)
    }

    private fun startEngine() {
        val test = try {
            engines.create(::onReading).also { it.start() }
        } catch (e: AudioInitializationException) {
            unavailable(e)
            return
        } catch (e: IOException) {
            unavailable(e)
            return
        } catch (e: IllegalStateException) {
            unavailable(e)
            return
        }
        engine = test
        // The slider may have moved while the voice was being decoded.
        test.setAttenuationLimitDb(Settings.rnnoiseLimitDbOf(_state.value.strength))
        // A stop tapped meanwhile has queued its own stopEngine behind this task, which stops [test].
        _state.update {
            if (it.status != DoubleTalkTestUiState.Status.STARTING) it
            else it.copy(status = DoubleTalkTestUiState.Status.RUNNING, echoCancelled = test.echoCancelled)
        }
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    private fun unavailable(e: Exception) {
        Log.w(TAG, "the double-talk test could not start", e)
        _state.update {
            if (it.status != DoubleTalkTestUiState.Status.STARTING) it
            else it.copy(status = DoubleTalkTestUiState.Status.UNAVAILABLE, meter = null)
        }
    }

    private fun onReading(reading: SelfTestReading) {
        _state.update {
            // A reading still in flight from a stopped test must not bring its lamp back.
            if (it.status != DoubleTalkTestUiState.Status.RUNNING) {
                it
            } else {
                it.copy(
                    meter = reading.meter,
                    phase = reading.phase,
                    heardPercent = reading.heardPercent,
                    falseOpenPercent = reading.falseOpenPercent,
                )
            }
        }
    }

    override fun onCleared() {
        // The scope is gone already; the microphone and the audio mode must not outlive the screen.
        engine?.stop()
        engine = null
    }

    private fun DoubleTalkTestUiState.cleared(status: DoubleTalkTestUiState.Status) = copy(
        status = status, meter = null, phase = SelfTestPhase.LISTEN, heardPercent = null, falseOpenPercent = null,
    )

    private companion object {
        const val TAG = "DoubleTalkTest"
        val DEFAULT_STRENGTH = Settings.RNNOISE_ATTENUATION_LIMIT_DB.default
    }
}
