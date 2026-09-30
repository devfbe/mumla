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
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import se.lublin.humla.audio.AndroidAudioTrackSink
import se.lublin.humla.audio.CapturePreview
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.PcmPlaybackSinkFactory
import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.PcmCaptureSourceFactory
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.Settings

/**
 * The microphone test behind a level meter: a [CapturePreview] built from the user's settings, its
 * readings handed to [onReading] on the main thread. Main thread only.
 *
 * It takes the microphone and may switch the phone to communication mode, which quietens other
 * apps' audio, so callers start it only when the user asks and stop it when their screen pauses.
 */
class MicCheck(private val context: Context, private val onReading: (MeterReading) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var preview: CapturePreview? = null

    val isRunning: Boolean get() = preview != null

    /**
     * (Re)starts the test, with [loopback] playing back what would be sent, and the echo canceller
     * the user has for [echoCategory]. Returns the voice gate it measures with, or null when the
     * microphone is unavailable: no permission, or another app holds it.
     */
    fun start(loopback: Boolean, echoCategory: AudioDeviceCategory): VadConfig? {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val settings = Settings.getInstance(context)
        val vad = settings.vadConfig
        lateinit var started: CapturePreview
        started = CapturePreview(
            context.getSystemService(AudioManager::class.java),
            vad,
            settings.noiseSuppressionMode,
            settings.speexNoiseSuppressDb,
            if (settings.isEchoCancellationEnabled(echoCategory)) EchoCancellationMode.WEBRTC
            else EchoCancellationMode.NONE,
            settings.androidAudioEffects,
            loopback,
            // A capture thread that outlived stop()'s join must not reach a stopped test's meter.
            onReading = { reading -> mainHandler.post { if (preview === started) onReading(reading) } },
            rnnoiseAttenuationLimitDb = settings.rnnoiseAttenuationLimitDb,
            captureFactory = captureFactory,
            sinkFactory = sinkFactory,
        )
        return try {
            started.start()
            preview = started
            vad
        } catch (e: AudioInitializationException) {
            HumlaLog.w(TAG, "microphone test unavailable", e)
            null
        }
    }

    /** Forgets what the voice gate has learned about the room. */
    fun recalibrate() {
        preview?.recalibrate()
    }

    /** Releases the microphone and the audio mode; readings already posted are dropped. */
    fun stop() {
        preview?.stop()
        preview = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "MicCheck"

        /** Where the test gets its microphone and speaker; replaced in tests. */
        @VisibleForTesting
        var captureFactory: PcmCaptureSourceFactory = AndroidAudioRecordSource.Factory()

        @VisibleForTesting
        var sinkFactory: PcmPlaybackSinkFactory = AndroidAudioTrackSink.Factory()

        @VisibleForTesting
        fun resetFactories() {
            captureFactory = AndroidAudioRecordSource.Factory()
            sinkFactory = AndroidAudioTrackSink.Factory()
        }
    }
}
