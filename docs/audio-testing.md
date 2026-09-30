# Audio testing

How Mumla's capture chain (echo cancellation, noise suppression, voice gate) is tested: what each
test proves, how to run the ones that need a phone and a PC speaker, how to read their output, and
what they measured. Everything here concerns speakerphone double talk: the user talking while a
remote user talks through the phone's speaker.

The chain under test, for `EchoCancellationMode.WEBRTC` and Strong noise suppression:

```
microphone ─▶ WebRTC APM (AEC3 + AGC2 + high-pass) ─▶ RNNoise (attenuation limit) ─▶ voice gate ─▶ Opus
                  ▲
remote users ─▶ mix ─▶ FarEndFrameChunker ─▶ AEC3 far-end reference, then AudioTrack
```

## 1. Overview

| Level | Where | What it proves |
|---|---|---|
| JVM (Robolectric, fakes for the native code) | `libraries/humla/src/test`, `app/src/test` | Wiring and logic: the chain's order, the settings reaching the pipeline, the attenuation limit's arithmetic, the self-test's state machine. Runs in `verify`. |
| Device, simulated acoustics | `libraries/humla/src/androidTest/.../capture` | The real `libhumla_native.so` (AEC3, AGC2, RNNoise) on the phone's CPU, fed synthetic or Piper speech through a simulated echo path (`DoubleTalkRig`). Deterministic; no microphone. |
| Device, real acoustics | `AcousticLoopbackDeviceTest` | The phone's own speaker and microphone: routing, what each capture source hears, latency. |
| Room | `RoomAcousticsDeviceTest` + `tools/room-test/room_test.py` | A real room: the phone plays the far end, the PC speaker plays the near end, and every capture configuration is recorded and replayed through the app's chain. Manual, opt-in. |
| In-app | the "Talk-over test" | The same chain, live, for the user to check and tune their own phone. `DoubleTalkSelfTestDeviceTest` runs its engine against the PC speaker. |

Device tests are built by `verify` (`assembleAndroidTest`) but only run from Android Studio. The
manual ones skip themselves unless a system property enables them (see section 5).

### JVM tests that matter here

- `RnnoisePreprocessorTest`: the limit's mix `wet + a·(dry − wet)`, `a = 10^(−limit/20)`, lines up
  with RNNoise's two-frame latency to the sample; unlimited is RNNoise's own output bit for bit; a
  limit changed mid-stream applies from the next frame, aligned; 18 dB is the default.
- `CapturePreprocessorFactoryTest`, `CaptureWiringTest`: AEC3 before the denoiser, AGC2 in front of
  RNNoise, the requested limit (default 18 dB) reaching the stage.
- `SettingsAudioTest`, `SessionSettingsSyncTest`, `AudioSettingsFragmentTest`,
  `AudioSettingsPolicyTest`: the strength setting (default 18, "Unlimited" at the slider's end,
  clamping, reset to 18), a running session reconfigured without a reconnect, the slider and its reset
  shown only with RNNoise.
- `DoubleTalkSelfTestTest` (engine) and `DoubleTalkTestViewModelTest` (screen): see section 6.

## 2. The Piper speech corpus

RNNoise is a model trained on speech, so its verdict on synthetic vowels does not carry over (the
synthetic pair lost 30 % of the near end alone; real speech loses 1-2 %). The device tests therefore
also use real, synthesized speech.

- **Generator.** `nix run .#speech-corpus` runs `tools/speech-corpus/generate.sh` with Piper, sox
  and coreutils from the flake, and four Piper voices pinned by hash in `flake.nix`. It writes four
  12 s mono PCM16 clips and `MANIFEST.txt` (voice, model hash, text, clip hash, flags) to
  `libraries/humla/src/testSpeech/speech/`.
- **Voices.** Far end (male): `de_DE-thorsten-medium` (`far_de_m.wav`), `en_US-ryan-medium`
  (`far_en_m.wav`). Near end (female): `de_DE-kerstin-low` (`near_de_f.wav`),
  `en_US-lessac-medium` (`near_en_f.wav`). The model weights are MIT (rhasspy/piper-voices); each
  voice's dataset has its own license in its `MODEL_CARD`. lessac's dataset (Blizzard 2013 /
  Lessac Technologies) is research-only, so these clips stay test-only.
- **Determinism.** Piper runs with `--noise-scale 0 --noise-w-scale 0`, sox with `-D` (no
  dither): a rerun is bit-identical for the same tool versions. The clips are committed; nothing is
  generated at build time.
- **Wiring.** `libraries/humla/build.gradle.kts` adds `src/testSpeech` as assets of the device-test
  APK and resources of the JVM tests only. No library or app APK packages them. `SpeechCorpus`
  loads a clip and resamples it to 48 kHz with the library's own Speex resampler.
- **The app's own voice** is separate: `nix run .#selftest-clip` (`tools/selftest-clip/`) writes
  `app/src/main/res/raw/double_talk_voice.ogg`, which ships in the APK. It uses
  `en_US-ljspeech-medium`: MIT weights trained from scratch on the public-domain LJ Speech dataset,
  so it may be redistributed. Opus 24 kbit/s with a fixed Ogg serial, 59 KB, about 19 s of speech.
  License and hashes: `tools/selftest-clip/MANIFEST.txt`; credited on the app's About screen.

## 3. AcousticLoopbackDeviceTest

`libraries/humla/src/androidTest/java/se/lublin/humla/audio/routing/AcousticLoopbackDeviceTest.kt`

**What it measures.** A 1 s log sweep (200 Hz-8 kHz) is played on the voice-call path the app uses
(`USAGE_VOICE_COMMUNICATION` in `MODE_IN_COMMUNICATION`, routed with `AndroidCommunicationDevices`
or the app's `AudioRouter`) and recorded from VOICE_COMMUNICATION, MIC, UNPROCESSED,
VOICE_RECOGNITION and CAMCORDER. A matched filter finds the sweep. A source "hears" it when the
correlation peak stands 35 dB above the rest (a gated or noise-only recording reaches 18-28 dB, a
heard sweep 40-60 dB), lies 0 to 500 ms after playback, and the loop gain is 20 dB above the silence
run's (taken no lower than −100 dB, because a gated source returns exact zeros for silence). It
asserts the communication device, the track's routed device, audibility and latency.

**Run it.** Phone on USB, no headset, quiet room. In Studio: the shared run configuration
`AcousticLoopbackDeviceTest`, or the gutter icon of one test. It raises the voice-call volume to
maximum and sets the test package's `RECORD_AUDIO` app op to `allow` (the test process has no
activity), restoring both afterwards.

**Read the output.** `adb logcat -s AcousticLoopback:I TestRunner:*` gives one line per route and source:
peak dB, lag, loop gain, gain over silence, share of exact zeros. The WAVs are in
`/sdcard/Android/data/se.lublin.humla.test/files/loopback` until Studio uninstalls the test APK.

**The platform's half duplex (SM-S938B, 2026-09-30, voice-call volume at maximum):**

- Earpiece: the probe comes back at −36 dB loop gain on UNPROCESSED, 43-48 dB above silence.
- Speaker: −14 dB loop gain on UNPROCESSED. But VOICE_COMMUNICATION and MIC, which take the
  platform's voice path in communication mode, deliver **94-100 % exact zeros while the speaker
  plays**: the platform gates the microphone during far-end playback. On the earpiece the same
  sources are gated on 57-71 % of the sweep.
- Quiet input on VOICE_COMMUNICATION and MIC comes back as exact zeros, and the first second of
  every capture is zeros.
- Latency, presented to captured: 150 ms on VOICE_COMMUNICATION and MIC, 93-109 ms on UNPROCESSED,
  VOICE_RECOGNITION and CAMCORDER.

## 4. Simulated double talk: DoubleTalkAec3DeviceTest, RealSpeechDeviceTest, the RNNoise tests

All of them run the real native chain over `DoubleTalkRig`'s timeline (48 kHz, 10 ms frames): 8 s
far end only, 4 s double talk, 4 s near end only; far end at −18 dBFS, near end at −26 dBFS, a
−65 dBFS noise floor; echo paths LINEAR (delay, two reflections, low-pass) and NONLINEAR (small-
speaker high-pass, light saturation, a 300 ms room tail) at −20/−10/0/+6 dB. Three runs per chain:
A = echo + near + noise, B = echo + noise, C = near + noise with a silent reference. The gate is the
app's adaptive voice gate (onset 2 frames, hold 250 ms). Tables go to logcat, tag `DoubleTalk`.

**Metrics.**

- *Gate %* (double-talk gate): share of voiced near-end frames in double talk (near end within
  10 dB of its level) on which run A's gate is open.
- *False-open %*: share of echo-only frames of run B, after AEC3's first 2 s, on which the gate
  opens, i.e. the far end hearing itself. "(0-2 s)" is the same during convergence.
- *Residual*: mean (and 95th percentile) output level of run B, converged, in dBFS.
- *Zeroed %*: share of voiced near-end double-talk frames that run A outputs as digital silence
  (mean power below 1 LSB²), i.e. RNNoise deleting the user.
- *Kept dB*: near-end power kept in double talk, `10·log10((P_A − P_B) / P_C)`.
- *Near alone %*: run C's gate on voiced near-end frames.

**DoubleTalkAec3DeviceTest.shippedChainInDoubleTalk** (synthetic talkers; bounds in the test's
companion). The shipped chain limits RNNoise to 18 dB (SM-S938B, 2026-09-30):

| Scenario | gate | zeroed | false-open |
|---|---|---|---|
| LINEAR −20 / −10 / 0 / +6 dB | 97 / 93 / 92 / 74 % | 0 % | 0 / 0 / 0 / 0 % |
| NONLINEAR −20 / −10 / 0 / +6 dB | 97 / 80 / 71 / 66 % | 0 % | 9.6 / 25.3 / 11.9 / 37.4 % |

Unlimited RNNoise at LINEAR −20 / 0 dB: gate 62 / 26 %, zeroed 45 / 64 %; AEC3 alone: 99 / 94 %.
Bounds: false-open ≤ 45 %, gate ≥ 55 % per scenario, 18 dB at least 25 points above unlimited and
zeroing ≤ 2 %. Note the trade: on the loud nonlinear synthetic path echo alone opens the limited gate
more often (37 % against 30 % unlimited). Real speech and the real room do not show this (below).

**RealSpeechDeviceTest** repeats this with the four Piper pairs. `shippedChainWithRealSpeech`
compares the shipped chain, RNNoise without a limit, and APM alone; `attenuationLimitSweepWithReal
Speech` sweeps the limit (unlimited, 12, 18, 24, 30 dB). Measured before the change: without a limit
gate 66-81 %, zeroed 34-39 %, near alone 98-99 %; a 12-30 dB limit lifts the mean gate from 74 % to
85-86 %; up to 18 dB the worst scenario's echo false-open rises by at most 0.4 points (24 dB +3.6,
30 dB +7.2). The shipped chain is exactly the sweep's 18 dB chain (AGC2 in front), so those numbers
stand for it: mean gate 85-86 %, near alone 98-99 %; `shippedChainWithRealSpeech` now also runs
"no limit" for comparison and bounds the shipped chain at ≥ 72 % gate and ≤ 5 % zeroed per pair.
(A per-pair rerun of `shippedChainWithRealSpeech` with the 18 dB default is still to be logged.)

**RnnoiseAttenuationLimitDeviceTest** (synthetic, three pairs): the limit and AGC2's position. AGC2
behind RNNoise lifts residual babble from about −48 to −26 dBFS, so AGC2 stays in front.
**RnnoiseLatencyDeviceTest**: RNNoise's output lags its input by exactly two frames (960 samples),
and the limited mix of the shipped 18 dB has no comb notches.

## 5. RoomAcousticsDeviceTest and tools/room-test/room_test.py

`libraries/humla/src/androidTest/java/se/lublin/humla/audio/capture/RoomAcousticsDeviceTest.kt`

**Question.** Which capture source and audio mode lets the near end break in on speakerphone
without the far end hearing its own echo, and what does the app's chain make of it?

**Physical setup.** The phone lies next to the PC speaker, screen up, no headset connected, a quiet
room. The PC plays through its analog output, PipeWire sink
`alsa_output.pci-0000_00_1f.3.analog-stereo`, at 95 % volume (leave it there; use `--gain-db` for a
quieter talker). The test sets the phone's voice-call and music volumes to maximum and restores
them.

**Property switches** (Studio's run configurations cannot pass instrumentation arguments through
its MCP, so system properties stand in; the arguments `roomTest` / `roomTestOnly` work too):

- `debug.mumla.roomtest=true` enables the test; without it the test is skipped (so `verify` and a
  plain run skip it).
- `debug.mumla.roomtest.only=A,B` runs only the configurations whose name contains `A` or `B`, e.g.
  `VOICE_COMMUNICATION/COMM`.
- `debug.mumla.roomtest.pulled=true` is set by the script once it has pulled the files; the test
  waits for it (up to 2 min) after `ROOM_DONE`.

**Run it.**

1. Plug in the phone; check `adb devices`.
2. Start the script first, from the checkout:
   `python3 tools/room-test/room_test.py --serial <serial> [--only VOICE_COMMUNICATION/COMM] [--gain-db -12] [--out room-test-results]`.
   It builds `near.wav` (the Piper clip `near_en_f.wav`, then the same clip again 26 s after the
   first one's start), sets the properties and follows logcat (tag `RoomTest`).
3. Start `RoomAcousticsDeviceTest` from Studio (shared run configuration).
4. On every `ROOM_TRIGGER <configuration>` the script plays `near.wav` with `pw-play`. It logs the
   host-minus-device clock offset; the test does not rely on it (it finds the near end with a
   matched filter).
5. At `ROOM_DONE` the script pulls `/sdcard/Android/data/se.lublin.humla.test/files/room` into
   `--out`, sets `...pulled`, and clears the properties.

**Timeline per configuration** (from the start of recording, 10 ms frames, 42 s): 0-1 s recording
only, then the trigger; the first near-end copy (12 s) while the phone plays silence (still fed to
AEC3 as reference); the far end (`far_de_m.wav` at −18 dBFS, looped) from frame 1450 (14.5 s) to
4150, AEC3's first 2 s skipped in the statistics; the second near-end copy over it (double talk).

**Configurations.** Capture source (VOICE_COMMUNICATION, VOICE_RECOGNITION, UNPROCESSED, MIC) ×
audio mode (COMM: `MODE_IN_COMMUNICATION`, the speaker as communication device, a
voice-communication track, as the app's router does; NORMAL: `MODE_NORMAL` with a media track) ×
platform effects (default: none created, as the app does with WEBRTC; on: AEC and NS created and
enabled; off, VOICE_COMMUNICATION only: created and disabled).

**Output.** In `--out`: `room-test.log` (the logcat lines and the script's notes), `near.wav`, and
under `room/`: one WAV per configuration (the raw recording), `far-reference.wav`, and
`results.csv` with one row per configuration and chain:

| Column | Meaning |
|---|---|
| `config`, `routed`, `effects` | configuration name, the track's routed device type, the effects as `name:before>after` |
| `near_found`, `near_offset_ms` | whether the matched filter found the near end, and where after the trigger |
| `near_dbfs`, `echo_dbfs` | near end and echo at the microphone (raw), dBFS |
| `zeros_near`, `zeros_echo`, `zeros_dt` | share of exact-zero samples: near alone, echo only, double talk (the platform's gate) |
| `echo_delay_ms` | from handing a far-end frame to AEC3 to reading its echo; NaN (or meaningless) when the source is gated |
| `erle_first_db`, `erle_db` | AEC3 alone: echo return loss enhancement in the first second and converged |
| `chain` | `shipped` (RNNoise at 18 dB), `nolimit` (RNNoise without a limit, the chain before), `apm` (AEC3 + AGC2 only). Runs before the 18 dB default call these `shipped` (then unlimited), `rnn18` and `apm`. |
| `near_alone_gate`, `dt_gate` | the gate on voiced near-end frames, alone and in double talk |
| `echo_fo`, `echo_fo_converging` | the gate opening on echo alone, converged and in AEC3's first 2 s |
| `residual_dbfs`, `residual_p95_dbfs` | output level on echo alone |

**Results, SM-S938B, 2026-09-30** (commit 5bf84dbc; near end at the PC's 95 %, full run; in that
run "no limit" was named `shipped` and "18 dB" `rnn18`). DT = double-talk gate, FO = echo-only
false open. Delay and ERLE are meaningless where the echo is almost all zeros.

| Configuration | near / echo at mic (dBFS) | exact zeros near / echo / DT | echo delay | AEC3 ERLE | DT gate: no limit / 18 dB / APM only | echo FO: no limit / 18 dB / APM only |
|---|---|---|---|---|---|---|
| VOICE_COMMUNICATION/COMM/default | -19.0 / -120.3 | 12 / 100 / 17 % | NaN ms | NaN dB | 93.6 / 97.1 / 98.3 % | 0.0 / 0.0 / 0.0 % |
| VOICE_RECOGNITION/COMM/default | -54.5 / -52.4 | 5 / 3 / 1 % | 290 ms | 36.9 dB | 47.8 / 84.4 / 86.6 % | 0.0 / 0.0 / 13.5 % |
| UNPROCESSED/COMM/default | -36.1 / -34.6 | 1 / 1 / 0 % | 296 ms | 32.5 dB | 44.3 / 87.2 / 87.8 % | 0.4 / 7.9 / 7.8 % |
| MIC/COMM/default | -19.2 / -70.9 | 15 / 99 / 17 % | 1003 ms | 24.7 dB | 97.3 / 97.4 / 99.2 % | 0.0 / 0.0 / 0.0 % |
| VOICE_COMMUNICATION/COMM/on | -19.2 / -112.9 | 14 / 100 / 19 % | 1029 ms | NaN dB | 95.7 / 97.6 / 99.1 % | 0.0 / 0.0 / 0.0 % |
| VOICE_RECOGNITION/COMM/on | -54.1 / -52.8 | 7 / 5 / 1 % | 293 ms | 37.4 dB | 38.8 / 77.3 / 89.3 % | 0.0 / 0.0 / 85.9 % |
| UNPROCESSED/COMM/on | -36.0 / -34.7 | 1 / 1 / 0 % | 288 ms | 38.5 dB | 40.9 / 89.5 / 91.3 % | 0.0 / 9.2 / 9.0 % |
| MIC/COMM/on | -19.1 / -91.5 | 12 / 99 / 19 % | 999 ms | 1.7 dB | 95.6 / 96.9 / 98.5 % | 0.0 / 0.0 / 0.0 % |
| VOICE_COMMUNICATION/COMM/off | -19.2 / -67.1 | 11 / 98 / 18 % | -418 ms | 6.9 dB | 96.0 / 96.2 / 98.0 % | 3.0 / 3.0 / 3.3 % |
| VOICE_COMMUNICATION/NORMAL/default | -30.9 / -16.1 | 13 / 12 / 1 % | 228 ms | 20.1 dB | 11.0 / 49.0 / 95.4 % | 7.3 / 44.6 / 91.5 % |
| VOICE_RECOGNITION/NORMAL/default | -35.4 / -11.7 | 0 / 0 / 0 % | 170 ms | 22.8 dB | 31.5 / 59.4 / 59.7 % | 31.4 / 61.6 / 60.7 % |
| UNPROCESSED/NORMAL/default | -35.4 / -11.7 | 0 / 0 / 0 % | 167 ms | 25.3 dB | 33.6 / 57.6 / 58.9 % | 15.1 / 69.2 / 68.9 % |
| MIC/NORMAL/default | -24.1 / -16.1 | 1 / 1 / 0 % | 197 ms | 43.6 dB | 25.2 / 57.9 / 61.5 % | 2.6 / 11.6 / 11.7 % |
| VOICE_COMMUNICATION/NORMAL/on | -31.0 / -16.3 | 15 / 8 / 1 % | 219 ms | 22.5 dB | 8.2 / 29.9 / 94.8 % | 10.3 / 31.2 / 92.7 % |
| VOICE_RECOGNITION/NORMAL/on | -35.4 / -11.7 | 0 / 0 / 0 % | 175 ms | 19.6 dB | 33.1 / 62.1 / 63.4 % | 24.3 / 78.6 / 78.4 % |
| UNPROCESSED/NORMAL/on | -35.1 / -11.7 | 0 / 0 / 0 % | 168 ms | 25.2 dB | 44.3 / 61.2 / 61.1 % | 32.1 / 50.6 / 50.0 % |
| MIC/NORMAL/on | -24.1 / -16.1 | 0 / 1 / 0 % | 191 ms | 26.1 dB | 20.6 / 54.8 / 62.0 % | 2.6 / 12.1 / 14.7 % |
| VOICE_COMMUNICATION/NORMAL/off | -30.5 / -16.2 | 14 / 11 / 1 % | 217 ms | 25.4 dB | 10.1 / 42.4 / 86.9 % | 6.9 / 21.2 / 88.0 % |

Quieter near end, the app's configuration (VOICE_COMMUNICATION/COMM/default), `--gain-db −12` and
`−20`: double-talk gate no limit / 18 dB / APM only 60.2 / 91.1 / 95.9 % and 17.3 / 79.7 / 85.0 %;
near end alone 96.9 / 98.8 / 100 % and 21.4 / 95.6 / 97.7 %; echo false-open 0 % throughout.
Together with the loud run: **94 / 60 / 17 % without a limit, 97 / 91 / 80 % with 18 dB.**

**Conclusions.**

- Keep VOICE_COMMUNICATION + MODE_IN_COMMUNICATION (the app's configuration). The platform gates
  the microphone while the speaker plays (97-100 % exact zeros on echo alone), so no echo reaches the
  far end (false-open 0 %), and it lets a louder near end through. The ungated sources (UNPROCESSED,
  VOICE_RECOGNITION) hear everything and leave AEC3 with a residual (false-open up to 15 %); normal
  mode is worse in every respect (echo 20 dB louder).
- RNNoise was the break-in limiter: behind AEC3 (and the platform gate) it silenced the quieter near
  end. Limited to 18 dB it keeps 80-97 % of double-talk speech at no measured echo cost on the app's
  configuration. 18 dB is the shipped default; the user can change it (6-40 dB or unlimited).

**Caveats.** One phone (SM-S938B, Snapdragon, One UI); the platform's gate is vendor behaviour and a
Pixel is unmeasured. The talkers are Piper voices through a PC speaker, not people. Bluetooth SCO is
untested. One room, one position; the near end is louder than a person across the room would be.

## 6. The in-app talk-over test

**What the user sees.** Audio settings → "Talk-over test", or the audio panel's "Talk-over test"
button next to the microphone check; only while not connected (it needs the microphone and the
speaker for itself). Start plays a test voice on the speaker, the way someone in a call sounds. The
sheet shows:

- a big lamp: green while the app would transmit the user, grey otherwise (with a state description
  for screen readers);
- the instruction for the current phase: first "Stay quiet for a moment. The lamp should stay dark
  while the voice speaks alone." (6 s), then "Talk over the voice. The lamp should light up while you
  speak, and stay dark while you are silent.";
- the level meter the microphone check uses;
- two results: "Heard you X % of the time you spoke over the voice" and "Opened on the voice alone
  Y % of the time", with a line explaining how they are counted;
- the "Maximum noise reduction" slider, applied live (stored when released), and "Reset to default"
  (18 dB), greyed out at the default; the settings screen has the same reset;
- Start/Stop and Retry. Without echo cancellation for the speaker it still runs and warns that the
  far end may hear itself.

**How it works.** `DoubleTalkSelfTest` (humla) runs what a call runs: `MODE_IN_COMMUNICATION`, the
built-in speaker as communication device, a track built exactly like `AudioOutput`'s on the
voice-call stream, each played frame handed to AEC3 through `FarEndFrameChunker` before the write,
and the capture chain from the user's settings (AEC3 + AGC2, then RNNoise with the chosen limit, then
the user's gate). It restores the audio mode and the communication device it found. The voice is
`res/raw/double_talk_voice.ogg`, decoded once with MediaExtractor + MediaCodec.

Nobody knows when the user really spoke, so the result uses a proxy: a second adaptive gate on the
signal after echo cancellation and before the denoiser (the *reference gate*). "Heard you" is, of the
frames in which the voice plays (voiced, or within 500 ms after, for the echo tail) and the reference
gate is open, the share the whole chain transmits. "Opened on the voice alone" is the share of frames
with the voice playing on which the gate opened during the quiet phase (its first second not
counted). A strength change restarts the measurement with the quiet phase, so every number belongs
to one strength. Fewer than 50 counted frames show no percentage.

The engine allocates nothing per frame (one reading object every fifth frame). The screen's
`DoubleTalkTestViewModel` serialises start and stop on one worker and publishes a `StateFlow`; the
sheet stops the test when it pauses.

**Tests.** `DoubleTalkSelfTestTest` (route and restore, reference before write, lamp from the gate,
the two counts, live limit, failure clean-up); `DoubleTalkTestViewModelTest` (start/stop/retry, a
stop racing a start, lamp, late readings dropped, strength live and stored on release, reset,
cleared model stops the engine). On the phone, `DoubleTalkSelfTestDeviceTest` runs the engine with
the PC as the user: `python3 tools/room-test/room_test.py --serial <serial> --selftest`, then start
the test from Studio (it is skipped unless `debug.mumla.selftest=true`). It uses the far-end Piper
clip as the test voice at −18 dBFS and the near-end clip from the PC, with the shipped settings; it
logs `SELFTEST voice alone: false open …%; talk-over: heard …%` (tag `SelfTest`) and asserts false
open ≤ 10 % and heard ≥ 70 %. Those bounds are provisional until its first run on the SM-S938B.

## 7. Troubleshooting

- **Studio's device run shows only "Connected to process".** The results are not shown in the run
  window over the MCP. Read the runner in logcat: `adb logcat -s 'TestRunner:*' DoubleTalk:I RoomTest:I
  AcousticLoopback:I SelfTest:I` shows `started:`, `failed:` with the assertion, and `run finished: N tests,
  M failed`.
- **The room test's files are gone.** Studio uninstalls the test APK, and with it its files
  directory, when the run ends. That is why the test waits after `ROOM_DONE` for
  `debug.mumla.roomtest.pulled`, which the script sets after `adb pull`. Start the script before the
  test, or pull by hand during the wait.
- **UNPROCESSED "unsupported".** `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`
  is not `true` on the SM-S938B, yet recording from UNPROCESSED works (the raw microphone). The tests
  try it anyway and skip it only if the recorder cannot be opened.
- **The build fails with SDK, NDK or CMake errors.** `local.properties`' `sdk.dir` must be the
  flake's SDK, a `/nix/store/…-androidsdk/libexec/android-sdk` path (`echo $ANDROID_HOME` inside
  `nix develop` prints it). Studio rewrites it when it is started outside `nix run
  .#android-studio`, and a store path from an older flake revision may be garbage-collected or lack
  the pinned NDK, CMake and build-tools; either breaks the build. Fix the path and resync.
- **The phone drops off adb** (USB power saving, the cable, or someone using the phone): `adb
  devices` lists nothing. Replug, unlock and accept the USB debugging prompt; `adb kill-server; adb
  start-server` if it stays away. A device test that was running is lost; rerun it. Before running
  audio tests, check nobody is using the phone (`adb shell dumpsys activity activities | grep
  topResumed`): the tests change the audio mode and route and play sound.
- **A test was skipped.** The manual tests need their property (`debug.mumla.roomtest`,
  `debug.mumla.selftest`); every audio device test skips itself when a wired or Bluetooth headset is
  connected.
