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

package se.lublin.mumla.smoke

import android.Manifest
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.CapturePreview
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The settings screen's microphone preview on a real AudioRecord. */
@RunWith(AndroidJUnit4::class)
class CapturePreviewDeviceTest {
    @get:Rule
    val microphone = grant(Manifest.permission.RECORD_AUDIO)

    private val audioManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)

    @Test
    fun thePreviewRecordsFromTheMicrophoneAndReportsReadings() {
        val readings = LinkedBlockingQueue<MeterReading>()
        val preview = CapturePreview(
            audioManager,
            VadConfig(VadMode.ADAPTIVE, startThreshold = 0.6f, stopThreshold = 0.5f, holdTimeMs = 250),
            NoiseSuppressionMode.NONE,
            speexNoiseSuppressDb = 0,
            EchoCancellationMode.NONE,
            AndroidAudioEffects(),
            loopback = false,
            onReading = { readings.add(it) },
        )
        val mode = audioManager.mode
        preview.start()
        try {
            repeat(3) {
                val reading = readings.poll(5, TimeUnit.SECONDS)
                assertThat(reading).isNotNull()
                assertThat(reading!!.levelDbfs.isNaN()).isFalse()
            }
            assertThat(audioManager.activeRecordingConfigurations).isNotEmpty()
        } finally {
            preview.stop()
        }
        assertThat(audioManager.mode).isEqualTo(mode)
    }
}
