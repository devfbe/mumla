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

package se.lublin.mumla.service

import android.content.Context
import se.lublin.humla.audio.AudioSettings
import se.lublin.humla.audio.PipelineSettings
import se.lublin.humla.model.Server
import se.lublin.humla.session.ClientCertificate
import se.lublin.humla.session.ConnectionConfig
import se.lublin.humla.session.SessionConfig
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.util.MumlaTrustStore

/**
 * Maps the user's settings onto the [SessionConfig] the service runs with.
 * `MumlaServiceAudioPreferencesTest` checks every key in `settings_audio.xml` is in [AUDIO_KEYS]
 * or explicitly exempt, so a new switch cannot be left unwired.
 */
object SessionSettings {
    /** Every preference key [withAudioSettings] reads. */
    val AUDIO_KEYS: Set<String> = Settings.ECHO_CANCELLATION_KEYS + setOf(
        Settings.VAD_MODE.key,
        Settings.VAD_SENSITIVITY.key,
        Settings.VAD_ADAPTIVE_FLOOR.key,
        Settings.VAD_FLOOR_DB.key,
        Settings.THRESHOLD.key,
        Settings.VAD_START.key,
        Settings.VAD_STOP.key,
        Settings.VAD_HOLD_MS.key,
        Settings.VAD_ONSET_FRAMES.key,
        Settings.INPUT_METHOD.key,
        Settings.AUDIO_DEVICE.key,
        Settings.AMPLITUDE_BOOST.key,
        Settings.HALF_DUPLEX.key,
        Settings.NOISE_SUPPRESSION_METHOD.key,
        Settings.SPEEX_NOISE_SUPPRESS_DB.key,
        Settings.ANDROID_NOISE_SUPPRESSOR.key,
        Settings.ANDROID_AGC.key,
        Settings.INPUT_QUALITY.key,
        Settings.INPUT_RATE.key,
        Settings.FRAMES_PER_PACKET.key,
    )

    /** [base] with every audio setting replaced by the user's current choice. */
    fun withAudioSettings(base: SessionConfig, settings: Settings): SessionConfig =
        base.copy(audio = audioSettings(settings))

    private fun audioSettings(settings: Settings) = AudioSettings(
        transmitMode = settings.transmitMode,
        vad = settings.vadConfig,
        halfDuplex = settings.isHalfDuplex,
        echoCancellationOverrides = settings.echoCancellationOverrides,
        preferredDevice = settings.preferredAudioDevice,
        pipeline = PipelineSettings(
            audioStream = Settings.PLAYBACK_STREAM,
            inputSampleRate = settings.inputSampleRate,
            bitrate = settings.inputQuality,
            framesPerPacket = settings.framesPerPacket,
            amplitudeBoost = settings.amplitudeBoostMultiplier,
            noiseSuppression = settings.noiseSuppressionMode,
            speexNoiseSuppressDb = settings.speexNoiseSuppressDb,
            androidEffects = settings.androidAudioEffects,
        ),
    )

    /** Everything to connect to [server] with. Reads the database, so not on the main thread. */
    fun forServer(context: Context, settings: Settings, database: MumlaDatabase, server: Server): SessionConfig {
        val certificate = if (settings.isUsingCertificate) {
            // TODO(acomminos): handle the case where a certificate's data is unavailable.
            database.getCertificateData(settings.defaultCertificateId)?.let { ClientCertificate(it) }
        } else {
            null
        }
        return SessionConfig(
            connection = ConnectionConfig(
                server = server,
                clientName = context.getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
                certificate = certificate,
                trustStorePath = MumlaTrustStore.getTrustStorePath(context),
                trustStorePassword = MumlaTrustStore.STORE_PASSWORD,
                trustStoreFormat = MumlaTrustStore.STORE_FORMAT,
                forceTcp = settings.isTcpForced,
                useTor = settings.isTorEnabled,
                localMuteHistory = if (server.isSaved) database.getLocalMutedUsers(server.id) else emptyList(),
                localIgnoreHistory = if (server.isSaved) database.getLocalIgnoredUsers(server.id) else emptyList(),
            ),
            audio = audioSettings(settings),
            localVolumes = database.getLocalVolumes(),
            autoReconnect = settings.isAutoReconnectEnabled,
            accessTokens = database.getAccessTokens(server.id),
        )
    }
}
