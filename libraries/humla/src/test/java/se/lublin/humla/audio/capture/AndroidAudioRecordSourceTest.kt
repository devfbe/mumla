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

package se.lublin.humla.audio.capture

import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

/**
 * The real capture source, as far as Robolectric reaches it. `AudioInputTest` runs against a fake,
 * which is the point of the seam -- and it is also the hole spec 4.04's fakes pass names: with only
 * that test, the hundred lines that talk to `AudioRecord` and `android.media.audiofx` would have no
 * test at all, and spec B6's whole content is in them.
 *
 * What Robolectric cannot express here, stated rather than quietly skipped:
 * - **The rate fallback.** `ShadowAudioRecord.native_get_min_buff_size` answers `2 * (rate / 4)` for
 *   every 16-bit rate, so it is never <= 0, and `AudioRecord`'s own parameter check accepts every
 *   one of [AndroidAudioRecordSource.SAMPLE_RATES]. The first probe therefore always succeeds and
 *   neither the fallback nor the "no rate worked" throw can be reached from a test.
 * - **Platform silencing.** It needs an `AudioRecordingConfiguration` delivered to a registered
 *   `AudioManager.AudioRecordingCallback`; `ShadowAudioRecord` shadows neither registration call.
 *   The mapping from `isClientSilenced` to `CaptureState` is pinned one level up, in
 *   `AudioInputTest`, over the fake.
 * - **The in-flight read/release race.** Two threads and a native window; see the `released` KDoc.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAudioRecordSourceTest {
    private companion object {
        const val RATE = 48000
        const val FRAME = 480
    }

    private val opened = mutableListOf<AndroidAudioRecordSource>()

    @After
    fun tearDown() {
        opened.forEach { it.release() }
        ShadowAudioRecord.clearSource()
        ShadowAudioEffect.reset()
    }

    private fun open(request: CaptureRequest): AndroidAudioRecordSource =
        (AndroidAudioRecordSource.Factory().open(request) as AndroidAudioRecordSource).also { opened += it }

    /** Makes `<Effect>.isAvailable()` answer true, which queries the effect descriptor list. */
    private fun makeAvailable(type: UUID) {
        ShadowAudioEffect.addEffect(
            AudioEffect.Descriptor(
                type.toString(), UUID.randomUUID().toString(), AudioEffect.EFFECT_INSERT, "fake", "test",
            ),
        )
    }

    /**
     * `AudioDeviceInfoBuilder` leaves the underlying `AudioDevicePort`'s role at `ROLE_NONE`, and
     * `AudioRecord.setPreferredDevice` refuses anything whose `isSource()` is false -- so a device
     * straight from the builder is silently not routed to and the assertion reads `null` against
     * correct production code. The role is `AudioPort.ROLE_SOURCE`, which is hidden API, hence 1.
     */
    private fun inputDevice(type: Int): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(info, "mPort"), "mRole", 1)
        return info
    }

    /** Feeds every `AudioRecord.read` the same [value], [count] samples of it. */
    private fun feed(value: Short, count: Int) {
        val reader = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                val n = minOf(count, size)
                for (i in 0 until n) data[offset + i] = value
                return n
            }
        }
        ShadowAudioRecord.setSourceProvider { reader }
    }

    // ------------------------------------------------------------------ opening

    @Test
    fun `opens at the requested rate and reports it`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))

        assertThat(source.sampleRate).isEqualTo(RATE)
        assertThat(source.record.sampleRate).isEqualTo(RATE)
    }

    /** The one place [AudioSourcePolicy] reaches production; without this it is a tested island. */
    @Test
    fun `plain capture opens the requested source`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))

        assertThat(source.record.audioSource).isEqualTo(MediaRecorder.AudioSource.MIC)
    }

    @Test
    fun `an effect switches the open to the voice communication source`() {
        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC, RATE, effects = AndroidAudioEffects(noiseSuppressor = true),
            ),
        )

        assertThat(source.record.audioSource).isEqualTo(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    }

    @Test
    fun `webrtc echo cancellation switches the open to the voice communication source`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE, echo = EchoCancellationMode.WEBRTC))

        assertThat(source.record.audioSource).isEqualTo(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    }

    /** Spec B11: the SCO headset is routed to by device, not by hoping the default input is it. */
    @Test
    fun `a preferred device is routed to`() {
        val sco = inputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE, preferredDevice = sco))

        assertThat(source.record.preferredDevice).isSameInstanceAs(sco)
    }

    @Test
    fun `no preferred device leaves the routing alone`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))

        assertThat(source.record.preferredDevice).isNull()
    }

    // ------------------------------------------------------------------ spec B6, the effects

    @Test
    fun `no effects are attached when none were asked for`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))

        assertThat(source.effects).isEmpty()
    }

    @Test
    fun `the noise suppressor is attached and enabled when asked for`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_NS)

        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC, RATE, effects = AndroidAudioEffects(noiseSuppressor = true),
            ),
        )

        assertThat(source.effects).hasSize(1)
        assertThat(source.effects.single()).isInstanceOf(NoiseSuppressor::class.java)
        assertThat(source.effects.single().enabled).isTrue()
    }

    @Test
    fun `the gain control is attached and enabled when asked for`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_AGC)

        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC, RATE, effects = AndroidAudioEffects(automaticGainControl = true),
            ),
        )

        assertThat(source.effects.single()).isInstanceOf(AutomaticGainControl::class.java)
        assertThat(source.effects.single().enabled).isTrue()
    }

    /**
     * Both at once: the corner spec 4.04 records as having cost a round ("a user who switched on
     * both Android audio effects got neither, silently"), here one level below the policy.
     */
    @Test
    fun `both effects at once are both attached`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_NS)
        makeAvailable(AudioEffect.EFFECT_TYPE_AGC)

        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC,
                RATE,
                effects = AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = true),
            ),
        )

        assertThat(source.effects.map { it::class.java })
            .containsExactly(NoiseSuppressor::class.java, AutomaticGainControl::class.java)
    }

    /**
     * Ours cancels; a platform canceller in front of AEC3 would hand it an already-altered echo.
     * The platform canceller is not offered any more, so no request can attach one - even on a
     * device that has it.
     */
    @Test
    fun `webrtc echo cancellation attaches no platform canceller`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_AEC)

        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE, echo = EchoCancellationMode.WEBRTC))

        assertThat(source.effects).isEmpty()
    }

    @Test
    fun `no echo cancellation attaches no platform canceller`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_AEC)

        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE, echo = EchoCancellationMode.NONE))

        assertThat(source.effects).isEmpty()
    }

    /** A device without the effect must open anyway, without it, rather than fail to record. */
    @Test
    fun `an effect the device does not have is skipped rather than fatal`() {
        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC,
                RATE,
                effects = AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = true),
            ),
        )

        assertThat(NoiseSuppressor.isAvailable()).isFalse()
        assertThat(source.effects).isEmpty()
        assertThat(source.record.state).isEqualTo(AudioRecord.STATE_INITIALIZED)
    }

    // ------------------------------------------------------------------ the frame path

    @Test
    fun `read delegates to the recorder and reports the count`() {
        feed(value = 7, count = 300)
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))
        val buffer = ShortArray(FRAME)

        val n = source.read(buffer, FRAME)

        assertThat(n).isEqualTo(300)
        assertThat(buffer[0]).isEqualTo(7.toShort())
        assertThat(buffer[299]).isEqualTo(7.toShort())
        assertThat(buffer[300]).isEqualTo(0.toShort())
    }

    /** The count asked for is the frame, not the buffer: a longer buffer may not be overrun. */
    @Test
    fun `read never writes past the requested length`() {
        feed(value = 5, count = FRAME * 2)
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))
        val buffer = ShortArray(FRAME * 2)

        val n = source.read(buffer, FRAME)

        assertThat(n).isEqualTo(FRAME)
        assertThat(buffer[FRAME - 1]).isEqualTo(5.toShort())
        assertThat(buffer[FRAME]).isEqualTo(0.toShort())
    }

    // ------------------------------------------------------------------ teardown

    /**
     * Without the flag this reads `AudioRecord.ERROR_INVALID_OPERATION` (-3) on a good day and
     * crashes natively on a bad one; either way the capture loop has to be able to tell it apart
     * from a transient error, and -100 is that code.
     */
    @Test
    fun `a read after release reports the released code`() {
        feed(value = 7, count = FRAME)
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))
        source.release()

        assertThat(source.read(ShortArray(FRAME), FRAME)).isEqualTo(AndroidAudioRecordSource.ERROR_RELEASED)
    }

    /** `AudioInput.stopRecording` stops the source and `shutdown` may have released it first. */
    @Test
    fun `stop after release is a no-op rather than an exception`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))
        source.start()
        source.release()

        source.stop()
    }

    /** The premise that lets [AndroidAudioRecordSource.release] carry no re-entry guard. */
    @Test
    fun `releasing twice is safe`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_NS)
        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC, RATE, effects = AndroidAudioEffects(noiseSuppressor = true),
            ),
        )
        val effect = source.effects.single()

        source.release()
        source.release()

        assertThat(source.record.state).isEqualTo(AudioRecord.STATE_UNINITIALIZED)
        assertThrows(IllegalStateException::class.java) { effect.enabled }
    }

    @Test
    fun `release frees the attached effects too`() {
        makeAvailable(AudioEffect.EFFECT_TYPE_NS)
        val source = open(
            CaptureRequest(
                MediaRecorder.AudioSource.MIC, RATE, effects = AndroidAudioEffects(noiseSuppressor = true),
            ),
        )
        val effect = source.effects.single()
        assertThat(effect.enabled).isTrue()

        source.release()

        // A released AudioEffect answers nothing at all: `getEnabled()` throws on an uninitialized
        // one, which is a sharper observable than `false` and the only one this class can give.
        assertThrows(IllegalStateException::class.java) { effect.enabled }
    }

    @Test
    fun `unregistering a silence listener that was never registered is safe`() {
        val source = open(CaptureRequest(MediaRecorder.AudioSource.MIC, RATE))

        source.setSilenceListener(null)
    }
}
