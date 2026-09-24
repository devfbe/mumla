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
import se.lublin.humla.model.Server
import se.lublin.humla.session.ClientCertificate
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
        Settings.PREF_VAD_MODE,
        Settings.PREF_VAD_SENSITIVITY,
        Settings.PREF_VAD_ADAPTIVE_FLOOR,
        Settings.PREF_VAD_FLOOR_DB,
        Settings.PREF_THRESHOLD,
        Settings.PREF_VAD_START,
        Settings.PREF_VAD_STOP,
        Settings.PREF_VAD_HOLD_MS,
        Settings.PREF_VAD_ONSET_FRAMES,
        Settings.PREF_INPUT_METHOD,
        Settings.PREF_DEFAULT_OUTPUT,
        Settings.PREF_AMPLITUDE_BOOST,
        Settings.PREF_HALF_DUPLEX,
        Settings.PREF_NOISE_SUPPRESSION_METHOD,
        Settings.PREF_SPEEX_NOISE_SUPPRESS_DB,
        Settings.PREF_ANDROID_NOISE_SUPPRESSOR,
        Settings.PREF_ANDROID_AGC,
        Settings.PREF_INPUT_QUALITY,
        Settings.PREF_INPUT_RATE,
        Settings.PREF_FRAMES_PER_PACKET,
    )

    /** [base] with every audio setting replaced by the user's current choice. */
    fun withAudioSettings(base: SessionConfig, settings: Settings): SessionConfig {
        val effects = settings.androidAudioEffects
        return base.copy(
            transmitMode = settings.humlaInputMethod,
            vadConfig = settings.vadConfig,
            amplitudeBoost = settings.amplitudeBoostMultiplier,
            inputSampleRate = settings.inputSampleRate,
            inputQuality = settings.inputQuality,
            framesPerPacket = settings.framesPerPacket,
            halfDuplex = settings.isHalfDuplex,
            preprocessorEnabled = settings.isPreprocessorEnabled,
            noiseSuppressionMethod = settings.noiseSuppressionMethod,
            speexNoiseSuppressDb = settings.speexNoiseSuppressDb,
            androidNoiseSuppressor = effects.noiseSuppressor,
            androidAgc = effects.automaticGainControl,
            echoCancellationOverrides = settings.echoCancellationOverrides,
            earpieceByDefault = settings.isEarpieceDefaultOutput,
        )
    }

    /** Everything to connect to [server] with. Reads the database, so not on the main thread. */
    fun forServer(context: Context, settings: Settings, database: MumlaDatabase, server: Server): SessionConfig {
        val certificate = if (settings.isUsingCertificate) {
            // TODO(acomminos): handle the case where a certificate's data is unavailable.
            database.getCertificateData(settings.defaultCertificateId)?.let { ClientCertificate(it) }
        } else {
            null
        }
        val connection = SessionConfig(
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
            localVolumes = database.getLocalVolumes(),
            autoReconnect = settings.isAutoReconnectEnabled,
            accessTokens = database.getAccessTokens(server.id),
            audioStream = Settings.PLAYBACK_STREAM,
        )
        return withAudioSettings(connection, settings)
    }
}
