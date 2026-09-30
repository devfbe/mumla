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

import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real speech for the device tests: the Piper clips in `src/testSpeech/speech` (see its
 * `MANIFEST.txt`, regenerated with `nix run .#speech-corpus`), packaged as assets of the test APK
 * only. Each clip is 12 s of mono PCM16 at the voice's own rate; [load] converts it to 48 kHz with
 * the library's own speex resampler (quality 10) and caches it, full scale 1.0.
 */
internal object SpeechCorpus {
    const val RATE = 48_000

    enum class Clip(val file: String) {
        /** Far end, male, German (thorsten-medium, 22.05 kHz). */
        FAR_DE_M("far_de_m.wav"),

        /** Far end, male, English (ryan-medium, 22.05 kHz). */
        FAR_EN_M("far_en_m.wav"),

        /** Near end, female, German (kerstin-low, 16 kHz). */
        NEAR_DE_F("near_de_f.wav"),

        /** Near end, female, English (lessac-medium, 22.05 kHz). */
        NEAR_EN_F("near_en_f.wav"),
    }

    private val cache = HashMap<Clip, FloatArray>()

    fun load(clip: Clip): FloatArray = synchronized(cache) {
        cache.getOrPut(clip) {
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                .open("speech/${clip.file}").use { it.readBytes() }
            val wav = parseWav(bytes)
            val pcm = if (wav.rate == RATE) wav.samples else resample(wav.samples, wav.rate)
            FloatArray(pcm.size) { pcm[it] / FULL_SCALE }
        }
    }

    class Wav(val rate: Int, val samples: ShortArray)

    /** Mono PCM16 RIFF/WAVE; skips any chunk other than `fmt ` and `data`. */
    fun parseWav(bytes: ByteArray): Wav {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a RIFF/WAVE file" }
        var pos = 12
        var rate = 0
        var samples: ShortArray? = null
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val format = b.getShort(body).toInt()
                    val channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    val bits = b.getShort(body + 14).toInt()
                    // 1 = WAVE_FORMAT_PCM, what sox writes for mono 16-bit.
                    check(format == 1 && channels == 1 && bits == 16) {
                        "expected mono PCM16, got format $format, $channels channels, $bits bits"
                    }
                }
                "data" -> {
                    val n = minOf(size, bytes.size - body) / 2
                    samples = ShortArray(n) { b.getShort(body + 2 * it) }
                }
            }
            pos = body + size + (size and 1)
        }
        check(rate > 0 && samples != null) { "no fmt or data chunk" }
        return Wav(rate, samples)
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    /**
     * To 48 kHz, with trailing silence that flushes speex's filter. The output lags the input by
     * the filter's delay (about 6 ms at quality 10), which the clips' 0.2 s lead-in absorbs.
     */
    private fun resample(input: ShortArray, rate: Int): ShortArray {
        val outLength = (input.size.toLong() * RATE / rate).toInt()
        val padded = input.copyOf(input.size + rate / 10) // 100 ms, far more than the filter's delay
        val out = ShortArray(outLength + RATE / 10 + 16)
        val written = SpeexResampler(rate, RATE, quality = RESAMPLER_QUALITY).use {
            it.resample(padded, padded.size, out)
        }
        check(written >= outLength) { "speex produced $written samples, expected at least $outLength" }
        return out.copyOf(outLength)
    }

    private const val FULL_SCALE = 32768f
    private const val RESAMPLER_QUALITY = 10
}
