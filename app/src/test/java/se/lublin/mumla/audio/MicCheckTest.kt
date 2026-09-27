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

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.testutil.TestCaptureSource
import se.lublin.humla.testutil.TestPlaybackSink
import se.lublin.humla.testutil.awaitUntil
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.drainMainUntil

/** The microphone test only runs between start and stop, and nothing of it reaches the meter after. */
@RunWith(RobolectricTestRunner::class)
class MicCheckTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val source = TestCaptureSource(listOf(ShortArray(FRAME) { 1000 }), loopLastFrame = true)
    private val capture = TestCaptureSource.Factory(source)
    private val sink = TestPlaybackSink.Factory(TestPlaybackSink())
    private val readings = mutableListOf<MeterReading>()
    private val check = MicCheck(app) { readings += it }

    @Before
    fun seams() {
        MicCheck.captureFactory = capture
        MicCheck.sinkFactory = sink
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun resetSeams() {
        check.stop()
        MicCheck.resetFactories()
    }

    @Test
    fun `nothing is captured until the test is started`() {
        idleMainLooper()

        assertThat(check.isRunning).isFalse()
        assertThat(capture.request).isNull()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `started, it captures and hands readings over on the main thread`() {
        val vad = check.start(loopback = false, echoCategory = AudioDeviceCategory.SPEAKER)

        assertThat(vad).isEqualTo(Settings.getInstance(app).vadConfig)
        assertThat(check.isRunning).isTrue()
        assertThat(sink.openedWith).isNull()
        drainMainUntil(description = "a reading") { readings.isNotEmpty() }
    }

    @Test
    fun `the echo canceller is the one the user has for the given kind of device`() {
        Settings.getInstance(app).setEchoCancellationOverride(AudioDeviceCategory.BLUETOOTH, true)
        Settings.getInstance(app).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, false)

        check.start(loopback = false, echoCategory = AudioDeviceCategory.BLUETOOTH)

        assertThat(capture.request?.echo).isEqualTo(EchoCancellationMode.WEBRTC)
    }

    @Test
    fun `stopping releases the microphone and the audio mode, and drops readings already posted`() {
        Settings.getInstance(app).setEchoCancellationOverride(AudioDeviceCategory.SPEAKER, true)
        check.start(loopback = false, echoCategory = AudioDeviceCategory.SPEAKER)
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        awaitUntil(description = "a posted reading") { !shadowOf(Looper.getMainLooper()).isIdle }

        check.stop()
        idleMainLooper()

        assertThat(readings).isEmpty()
        assertThat(check.isRunning).isFalse()
        assertThat(source.events.last()).isEqualTo("release")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `without the microphone permission it does not start`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val vad = check.start(loopback = false, echoCategory = AudioDeviceCategory.SPEAKER)

        assertThat(vad).isNull()
        assertThat(check.isRunning).isFalse()
        assertThat(capture.request).isNull()
    }

    private companion object {
        const val FRAME = 480
    }
}
