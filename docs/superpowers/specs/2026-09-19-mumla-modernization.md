# Mumla Modernization — Master Specification

Date: 2026-09-19. Branch: `modernization` (integration branch; never commit on `master`).

This document is the binding authority for all implementation plans under
`docs/superpowers/plans/`. Where a plan and this spec disagree, the spec wins.

## 1. Goals

Turn Mumla (Android Mumble client, GPLv3) into a reliable, modern voice client:

1. Fix the user-reported bugs with their root causes, not symptoms: Bluetooth SCO
   lost after reconnect; "App not responding"; microphone silent while the screen
   is off; voice activation triggered by background noise.
2. Rebuild the capture pipeline: noise suppression (RNNoise), echo cancellation
   for speaker use (WebRTC audio processing), a sane voice activity detector.
3. Modernize the chat: image thumbnails, tap-to-view, RecyclerView.
4. Move Humla (the protocol/audio library) off the main thread and give it an
   explicit session state machine.
5. Bring build, dependencies, tests and platform compliance up to date.

Non-goals: new Mumble protocol features, Wear/Auto, redesign of the visual style,
keeping support for Android below the new minimum.

## 2. Global constraints (apply to every task in every plan)

- **Android floor:** `minSdk = 31`, `targetSdk = 36`, `compileSdk = 36`. Delete
  code paths that only exist for API < 31 when you touch the file that contains them.
- **Language:** all new files are Kotlin. A Java file that a task modifies in a
  non-trivial way (more than a few lines) is converted to Kotlin in that task,
  before the behavior change, as its own commit (`refactor: convert X to kotlin`).
  Generated protobuf code stays Java.
- **Concurrency:** Kotlin coroutines (`kotlinx-coroutines-android`). No new
  `AsyncTask`, no new bare `Thread` except where the audio path needs a
  dedicated real-time thread (capture and playback loops).
- **TDD is mandatory:** for every behavior change write the failing test first,
  run it and see it fail, then implement, then see it pass. Tests run on the JVM:
  JUnit 4 + Robolectric (Android framework classes) + MockK + Google Truth +
  `kotlinx-coroutines-test`. No instrumentation tests are required; design seams
  (interfaces, fakes) so that logic is testable without a device or native libs.
  Native code (C/C++) gets host-side unit tests where practical (CMake `ctest`
  targets built for the host), otherwise the JNI layer stays a thin pass-through
  and the Kotlin side is tested against a fake.
- **Commits:** Conventional Commits, English, one commit per logical unit
  (`feat:`, `fix:`, `refactor:`, `test:`, `build:`, `docs:`, `chore:`), optional
  scope in parentheses (`fix(humla):`), imperative subject ≤ 72 chars, optional body.
  **No trailers of any kind** — no `Co-Authored-By`, no `Claude-Session`, no
  `Signed-off-by`.
- **Humla lives in the main repo.** The `libraries/humla` git submodule is
  inlined (Foundation stream). After that, `libraries/humla` is an ordinary
  directory; third-party native sources (opus, speex, celt, rnnoise,
  webrtc-audio-processing) are git submodules of the main repo pointing at
  upstream release tags.
- **Native build:** CMake via AGP `externalNativeBuild`, one `CMakeLists.txt`
  under `libraries/humla/src/main/cpp/`. `ndk-build`, `Android.mk`,
  `Application.mk` and the `javacpp` bindings are removed. ABIs:
  `arm64-v8a`, `armeabi-v7a`, `x86_64`. NDK version `29.0.14206865`, SDK CMake
  `4.1.2`, build-tools `36.1.0` — set in `flake.nix` (`ndkVersions`,
  `cmakeVersions`, `buildToolsVersions`) and in `libraries/humla/build.gradle`
  (`ndkVersion`) by the Foundation stream; the dev shell currently ships NDK
  26.1.10909125 and is bumped in F6.
- **Dependencies:** every dependency at its latest stable release at the time
  of the task. Spongycastle is replaced by BouncyCastle (`org.bouncycastle:bcprov-jdk18on`,
  `bcpkix-jdk18on`). `javacpp`, `guava` are removed. `protobuf-java` is updated and
  `Mumble.java` regenerated from `libraries/humla/src/Mumble.proto` with the matching
  `protoc` (use the `protobuf-gradle-plugin`, do not check in generated code).
- **Licensing:** only GPLv3-compatible dependencies (BSD, MIT, Apache-2.0, LGPL).
  RNNoise (BSD-3) and WebRTC audio processing (BSD-3) qualify. Add each new
  third-party component to `NOTICE.md` at the repo root.
- **Build must stay green:** `nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
  passes at the end of every task. Lint runs with `abortOnError = true`; do not
  disable lint checks to get green, fix the finding.
- **Do not break the protocol:** wire behavior toward Mumble servers (TCP/UDP
  messages, crypt state, codec negotiation, CELT/Speex legacy support) is preserved.
- **User-facing strings** go to `res/values/strings.xml` / `preference.xml`
  (English source). Do not edit translation files; Weblate handles them.

## 3. Streams

Work is split into streams so that they can run in parallel worktrees after the
Foundation stream has landed. File ownership is exclusive per stream; a task
that must touch another stream's file records that in its plan and keeps the
change minimal.

| Stream | Owns |
|---|---|
| F Foundation | `build.gradle*`, `settings.gradle`, `gradle.properties`, `flake.nix`, `.gitmodules`, `libraries/humla/build.gradle`, `libraries/humla/src/main/cpp/CMakeLists.txt` (initial), test infrastructure, `Mumble.proto` build, crypto (`net/HumlaSSLSocketFactory`, `net/HumlaCertificateGenerator`, `util/MumlaTrustStore`, `preference/Certificate*Activity`), `NOTICE.md`, `README.md`, `.gitlab-ci.yml` |
| A Core | `HumlaService`, `net/HumlaConnection`, `net/HumlaTCP`, `net/HumlaUDP`, `net/HumlaNetworkThread`, `net/CryptState`, `protocol/ModelHandler`, `util/HumlaCallbacks`, `service/MumlaService`, `service/MumlaConnectionNotification`, `service/MumlaReconnectNotification`, `model/*`, new `service/ChatMessageLog` (bounded log, D5 acceptance lives here) |
| B Audio | `protocol/AudioHandler`, `audio/**` (input, output, encoders, input modes, `BluetoothScoReceiver`), `src/main/cpp/**` (after Foundation created it), `preference/AudioSettingsFragment`, `res/xml/settings_audio.xml`, audio keys in `Settings.kt` (additive only) |
| D Chat & UI | `channel/ChannelChatFragment`, `util/MumbleImageGetter`, `util/BitmapUtils`, `util/HtmlUtils`, `service/IChatMessage`, `service/MumlaMessageNotification`, chat layouts, new image viewer, new `chat/` package |
| P Platform & controls | `app/MumlaActivity` (permissions, MediaSession wiring), `channel/ChannelListFragment` (Bluetooth menu), `channel/ChannelListAdapter` (rebuild coalescing, see 4.1), `channel/ChannelFragment` (talk button), `service/MumlaOverlay` (talk button, see 4.1), `channel/ChannelSearchProvider` (see 4.1), new `service/MumlaMediaSession`, non-audio keys in `Settings.kt` (additive only), `res/xml/settings_general.xml`, `AndroidManifest.xml`, battery-optimization dialog |

Rules for shared files: `Settings.java` is converted to `Settings.kt` by
Foundation (F3); streams B and P only add keys and accessors. `MumlaService`
belongs to A; streams D and P may add hooks of at most ~10 lines each (a call
into their own new class) and must list that in their plan. A task never edits
a file another stream owns beyond such a hook.

### 3.1 Stream F — Foundation (sequential, lands first)

F1. Nix dev shell (done by a separate agent): JDK 21, Android SDK 36, NDK, CMake, meson/ninja.
F2. Inline the `libraries/humla` submodule into the repo (copy tree, drop `.git`,
    keep license headers, re-register `opus`, `speex`, `celt-0.7.0-src`,
    `celt-0.11.0-src` as submodules of the main repo at the same commits, delete the
    `libs/humla-spongycastle` submodule). Commit `build: inline humla library into main repo`.
F3. Gradle modernization: Kotlin Gradle plugin (latest), `kotlin-android`, version
    catalog `gradle/libs.versions.toml`, minSdk 31, Java/Kotlin toolchain 21,
    `android.nonTransitiveRClass`, remove Jetifier. Test infra: JUnit 4, Robolectric,
    MockK, Truth, coroutines-test, `testOptions.unitTests.isIncludeAndroidResources = true`;
    one passing Robolectric smoke test per module proves the setup. Convert
    `app/.../Settings.java` to `Settings.kt` (same public API) with tests for the
    threshold/enum mappings, so later streams only add keys.
F4. Dependencies: AndroidX/material latest; jsoup latest; netcipher latest (or
    evaluate replacement `info.guardianproject.netcipher` is unmaintained — if no
    maintained release exists, keep and note in `NOTICE.md`); billing latest;
    minidns latest; drop guava (replace the few usages with Kotlin stdlib);
    protobuf via `protobuf-gradle-plugin` with `protoc` from Maven, remove checked-in
    `Mumble.java`.
F5. Spongycastle → BouncyCastle. The custom "PKCS12 keybag" patch in
    `libs/humla-spongycastle` exists to read Mumble's unencrypted PKCS#12
    certificates; the task must test that importing such a certificate
    (`libraries/humla/src/test/resources/` fixture generated in the test) still works
    with stock BouncyCastle, and if not, implement a minimal Kotlin PKCS#12 reader for
    the unencrypted keybag case.
F6. Native build to CMake: build opus, speex (codec + dsp), celt 0.7 and 0.11 via
    `externalNativeBuild`, hand-written JNI (`src/main/cpp/jni_*.cpp`) replacing
    javacpp; Kotlin `external fun` wrappers in `se.lublin.humla.audio.native`
    (`OpusEncoderNative`, `OpusDecoderNative`, `SpeexPreprocessNative`,
    `SpeexResamplerNative`, `SpeexJitterNative`, `SpeexDecoderNative`,
    `Celt7Native`, `Celt11Native`). Existing Java call sites are adapted with no
    behavior change. Update opus to the latest release tag; speex to the latest
    `speex` + `speexdsp` release tags (two submodules) if the old combined tree
    cannot build with the new NDK, otherwise keep and note.
F7. CI: `.gitlab-ci.yml` runs `nix develop --command ./gradlew assembleFossDebug test lint`
    on a Nix image (`nixos/nix`), caches `~/.gradle` and the Nix store.
F8. `README.md`: replace the "maintenance situation" preamble with a short
    project description, dev-shell instructions, and a "Contributing" section
    (TDD, conventional commits).

### 3.2 Stream A — Core (Humla threading, session state, reconnect, foreground)

Root causes (from the analysis): every TCP/UDP packet is posted to the main
looper; audio teardown joins threads on the main thread; a disconnect tears
down foreground status, wake lock, SCO and audio even when an auto-reconnect
follows; errors are swallowed.

A1. **Protocol thread.** `HumlaConnection` dispatches parsing, `ModelHandler`
    and audio packet routing on a dedicated `HandlerThread("humla-protocol")`
    (or a single-threaded coroutine dispatcher). Observer callbacks
    (`IHumlaObserver`) are delivered on the main thread via `HumlaCallbacks`.
    Model objects (`Channel`, `User`) become immutable snapshots or are guarded;
    the UI reads through `IHumlaSession` which returns snapshots.
    Test: a fake `HumlaTCP` feeding 5 000 `ChannelState` messages must not block
    a main-looper task for more than 16 ms (Robolectric paused looper).
A2. **Audio lifecycle thread.** `AudioHandler` creation/shutdown runs on a
    `HandlerThread("humla-audio-control")`; `HumlaService.onConnectionDisconnected`,
    `onBluetoothSco*` and `configureExtras` post to it and never join on the main thread.
A3. **Session state machine.** `SessionState`: `Disconnected`, `Connecting`,
    `Connected`, `ConnectionLost(reconnectIn)`, `Reconnecting`. A `ConnectionLost`
    transition with auto-reconnect keeps: foreground status (notification text
    changes to "Connection lost – reconnecting…"), the partial wake lock, the
    `bluetoothScoWanted` flag, and the user's mute/deafen state. Only `Disconnected`
    releases them. Reconnect uses exponential backoff 2 s → 30 s with jitter,
    capped at 10 attempts unless connectivity changes.
A4. **Bluetooth SCO desired state.** `bluetoothScoWanted` is set only by
    `enableBluetoothSco()` / `disableBluetoothSco()`; after `Connected` the
    service restarts SCO if wanted. On API 31+ use
    `AudioManager.setCommunicationDevice` with the first `TYPE_BLUETOOTH_SCO`
    device instead of `startBluetoothSco`; `usingBluetoothSco()` reports the
    wanted state, a separate `isBluetoothScoActive()` reports the actual state.
A5. **UDP recovery.** On `onUDPConnectionError` set `usingUdp = false` so outgoing
    voice tunnels over TCP, then restart the UDP thread with backoff; the
    UDP-vs-TCP decision uses deltas over a 20 s window instead of cumulative
    good counters; a missing UDP ping reply for 15 s switches to TCP.
A6. **Foreground service robustness.** `startForeground` is wrapped; on
    `ForegroundServiceStartNotAllowedException`/`SecurityException` the service
    logs, emits a `ConnectionWarning` to the chat log and shows the reconnect
    notification instead of crashing. `MumlaService` calls `startForeground`
    once in `Connecting` and keeps it through `ConnectionLost`.
A7. **Half-duplex runtime change** carries the transmit mode in the extras bundle.
A8. **Errors are surfaced.** Decoder creation failure, UDP failure, and
    microphone silencing (from stream B) reach the chat log as warnings.

### 3.3 Stream B — Audio pipeline

B1. **Pipeline order.** `AudioInput` (AudioRecord, 48 kHz preferred, fallback
    sample rates) → resampler to 48 kHz (only if needed) → `CapturePreprocessor`
    (see B2) on **every** frame → `VoiceActivityDetector` → amplitude boost →
    encoder. Preprocessing is not gated on the talking state.
B2. **`CapturePreprocessor` interface** with implementations selectable in
    settings: `None`, `Speex` (denoise, configurable suppression dB, VAD
    probability via `SPEEX_PREPROCESS_GET_PROB`, no AGC calls), `RNNoise`
    (48 kHz/480-sample frames, returns VAD probability), `WebRtcApm`
    (AEC3 + AGC2 + high-pass, **noise suppression off** — see 4.1, VAD from level). Composition rule: WebRTC APM
    (when enabled for echo cancellation) runs first, then RNNoise; the VAD
    probability comes from the last stage that provides one.
B3. **Echo cancellation via WebRTC APM.** Vendor `webrtc-audio-processing`
    (freedesktop, latest release tag) as a submodule under
    `libraries/humla/src/main/cpp/third_party/webrtc-audio-processing`, built
    by a meson cross-build step invoked from CMake (`ExternalProject`) or a
    CMake port of its file list — the plan decides after checking the upstream
    build files. The playback path feeds the far-end signal
    (`AudioOutput` mixed frames) to `WebRtcApm.analyzeReverseStream` on every
    played frame. Settings: "Echo cancellation: None / Android / WebRTC".
B4. **RNNoise.** Submodule `xiph/rnnoise` at the latest release tag; the model
    weights file is checked in (or fetched by a documented, pinned CMake step —
    plan decides, reproducibility required). Settings: "Noise suppression:
    None / Light (Speex) / Strong (RNNoise)".
B5. **Voice activity detection.** `VoiceActivityDetector` with modes
    `Amplitude` (existing dBFS logic, kept for devices where models fail) and
    `Probability` (uses preprocessor VAD probability). Both use start/stop
    hysteresis (`startThreshold`, `stopThreshold`, `holdTimeMs`). Defaults:
    start 0.6, stop 0.3, hold 250 ms for `Probability`; existing single slider
    maps to start with stop = start − 0.15 for `Amplitude`.
B6. **Android audiofx.** `NoiseSuppressor` and `AutomaticGainControl` attached to
    the `AudioRecord` session when the user selects them (settings toggles),
    following the existing `AcousticEchoCanceler` pattern; `VOICE_COMMUNICATION`
    source and `MODE_IN_COMMUNICATION` whenever any effect or WebRTC AEC is active.
B7. **Silence detection.** `AudioInput` registers an `AudioRecordingCallback`;
    on `isClientSilenced()` it reports `CaptureState.Silenced` to `AudioHandler`,
    which surfaces a warning (stream A8) and retries capture after 2 s.
B8. **Thread safety of AudioOutput/AudioInput.** `mPacketLock` uses try/finally;
    the output loop waits on a predicate with a 100 ms timeout; `running` flags are
    `@Volatile`; `stop()` calls `AudioRecord.stop()` before `join(2000)`; a join
    timeout logs and releases anyway.
B9. **Speex preprocessor correctness.** Remove AGC calls (fixed-point build has
    no AGC), fix `GET_PROB_START` → `SET_PROB_START`, expose
    `noiseSuppressDb` (−15/−25/−35), correct the preference summary text.
B10. **Settings UI.** Audio settings get a live input meter (`InputLevelMeterPreference`)
    showing the current level and the start/stop thresholds while the settings
    screen is open (uses a short-lived `AudioRecord` on a background thread,
    released on pause); a "Test" toggle plays back your own voice after
    processing (loopback) so users can hear the effect.
B11. **Bluetooth SCO input.** When SCO is active, capture runs at the device's
    SCO rate and is resampled to 48 kHz; RNNoise/Speex run after resampling.

### 3.4 Stream D — Chat and images

D1. **Chat list on RecyclerView** with `ListAdapter` + `DiffUtil`, view types
    `TextMessage`, `InfoMessage`, `ImageMessage`. HTML bodies are parsed once
    per message (off the main thread, cached in the message model) into a
    `ChatContent` sealed class (`Text(spanned)`, `Image(source, textBefore, textAfter)`).
D2. **Image loading.** `ChatImageLoader` (Kotlin, coroutines) decodes `data:`
    URIs and http(s) URLs with `inJustDecodeBounds` + `inSampleSize` to a
    thumbnail bound (max 240 dp wide, 240 dp tall), `LruCache` sized to 1/8 of
    max heap keyed by SHA-1 of the source, `HttpURLConnection` with 5 s connect
    / 10 s read timeouts and a 5 MB cap, error results cached. No network or
    decoding on the main thread; `StrictMode.permitAll` is deleted.
D3. **Tap-to-view.** Tapping an image opens `ImageViewerDialogFragment`
    (fullscreen, black background, pinch-zoom via `subsampling-scale-image-view`
    or an in-repo `ZoomImageView` — plan decides, prefer the in-repo view if it
    stays under ~150 lines), decoding the full image for the screen size on a
    background thread; a share action exports via `FileProvider`.
D4. **Sending images** decodes on a background thread with `ImageDecoder`
    (API 28+) and target sample size; UI shows a progress state.
D5. `MumlaMessageNotification` strips images to "[image]" and truncates lines
    (the bounded message log itself is stream A's `ChatMessageLog`, 500 entries,
    oldest dropped).
D6. **Notification inline reply** (`RemoteInput`) sends to the current channel.

### 3.5 Stream P — Platform and controls

P1. **MediaSession push-to-talk.** `MumlaMediaSession` (`MediaSessionCompat`)
    active while connected; `KEYCODE_HEADSETHOOK`, `MEDIA_PLAY_PAUSE` and the
    Bluetooth AVRCP equivalents toggle PTT (in PTT mode) or mute (in VAD mode),
    configurable in settings; works with the screen off.
P2. **Bluetooth as persistent setting** (`pref_bluetooth_sco`, default off),
    the menu toggle writes the preference; stream A's `bluetoothScoWanted` is
    initialized from it on connect.
P3. **Runtime permissions:** `BLUETOOTH_CONNECT` requested before SCO is used;
    `POST_NOTIFICATIONS` flow kept; `RECORD_AUDIO` rationale dialog.
P4. **Battery optimization exemption** offered once (dismissable) after the
    first successful connection, via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
P5. **Manifest:** `foregroundServiceType="microphone|mediaPlayback"`,
    `android:exported` audit, remove obsolete permissions (`BLUETOOTH` legacy).

## 4. Interfaces between streams

- `IHumlaSession` (A) gains: `isBluetoothScoActive(): Boolean`,
  `sessionState: StateFlow<SessionState>`, `messageLog` unchanged.
- `AudioHandler.Builder` (B) gains: `setNoiseSuppression(mode)`,
  `setEchoCancellation(mode)`, `setVadConfig(VadConfig)`, `setAudioEffects(ns, agc)`.
  `HumlaService.configureExtras` (A) maps new `EXTRAS_*` keys to them; the keys
  are defined in `HumlaService` by stream A with the exact names
  `EXTRAS_NOISE_SUPPRESSION`, `EXTRAS_ECHO_CANCELLATION`, `EXTRAS_VAD_MODE`,
  `EXTRAS_VAD_START`, `EXTRAS_VAD_STOP`, `EXTRAS_VAD_HOLD_MS`,
  `EXTRAS_ANDROID_NS`, `EXTRAS_ANDROID_AGC`, `EXTRAS_BLUETOOTH_WANTED`.
- `AudioHandler` (B) exposes `captureState: StateFlow<CaptureState>`
  (`Active`, `Silenced`, `Error(msg)`); `HumlaService` (A) forwards `Silenced`
  and `Error` to `onLogWarning`.
- `AudioHandler.shutdown()` (B) is safe to call from any thread and returns
  within 3 s worst case; stream A calls it only from the audio-control thread.
- Stream D consumes `IChatMessage` unchanged; stream A must not change its shape.

### 4.0 Decisions the user made during execution

- **Chat images are not fetched automatically any more (D).** The default for
  `load_images` becomes off, with a per-server "always load on this server"
  opt-in. Rationale the user was given: with the old default every image URL in
  every chat message is fetched, so a tracking pixel tells its sender the user's
  IP address and when they are online. Inline `data:` images keep rendering —
  they ask nobody for anything, so hiding them would cost privacy nothing and
  usability a lot. This needs a settings surface and per-server storage; it is
  its own task. The default itself lives in exactly two places that must move
  together: `app/src/main/res/xml/settings_general.xml:52`
  (`android:defaultValue="true"`) and `app/src/main/java/se/lublin/mumla/Settings.kt:274`
  (`DEFAULT_LOAD_IMAGES`).
- **APK size is deferred (B).** The two new native libraries add ~8.48 MB
  uncompressed across three ABIs. The user chose to look at this together with
  the already-deferred R8 and APK-size work rather than to decide on ABI splits
  now.

### 4.04 What makes a guard real

Sixteen times in this project a protection looked covered and was not. The
pattern is always the same shape, and this is the rule that catches it
structurally instead of one instance at a time:

> **A guard is real only when removing *it alone* turns some test red. Two guards
> protecting the same observable are one guard and a lie.**

It was derived while closing three unpinned guards, and it immediately found a
seventeenth instance in the fresh fix that derived it: `resyncCryptState` had
been given both a `disconnectRequested` check and a route through
`sendTCPMessage`, and each mutation alone stayed green because the two masked
each other. One was removed.

Three handles follow from it:

1. **Name the observable before writing the guard.** Which observable result does
   this `if (...) return` change — a byte on the wire, a transport created, a
   handler called, the value a public getter returns? If none can be named that
   another guard does not already cover, the guard is an unpinnable branch and
   must not be written. This is why `onTLSHandshakeFailed`, `onUDPConnectionError`
   and `onTCPConnectionDisconnect` deliberately have no entry guard: their only
   effect beyond the listener is an idempotent `disconnect()` or a call already
   gated elsewhere.
2. **Pin the set, not the member.** Where the "places" are the methods of an
   interface, iterate the interface by reflection in the test and demand the
   property of *every* member, instead of writing the N cases out. A callback a
   later task adds then fails the test until someone decides what it does behind a
   disconnect. That is the difference between "nine mutations tried" and "a tenth
   one cannot exist".
3. **One bottleneck instead of N entry guards.** Where the promise is about
   *ordering*, the decision belongs at the delivery point — the consumer's looper —
   not at N call sites. One mechanism has one mutation; N guards have N mutations,
   of which N−1 tend to be invisible.

**Assert the algorithm, not the machine: measure at two sizes and require the
bigger one not to cost proportionally more.** A wall-clock budget in a test is the
flake spec 4.05 warns about; a *ratio* is not. Measured here on a queue scan:
taking the victim by index costs 1 106 ns at depth 1 024 and 1 695 ns at 8 192 —
**1.53x for 8x the depth** — while the scan it replaced costs 57 889 ns and
279 528 ns, **4.83x**. The assertion is "the 8x deeper one must not cost 4x as
much". Three caveats, the third measured after this entry was first written:

1. **Put the ratio assertion first.** An absolute bound placed ahead of it fires
   under the same mutation and reports its own number, so the ratio line is never
   reached (that shadowing happened here and was fixed one commit later in a
   different file).
2. **Watch the margin.** 2.5x between the real ratio and the threshold was the
   tightest number in the round that produced this entry.
3. **"No linear scan can pass a ratio" is a property of the spread and the
   warm-up, not of ratios.** Unshadowing the assertion above showed it had been
   toothless as well as hidden: at its 8x spread the scan mutation measured
   **2.77x, 3.99x, 3.54x and 2.95x — under a 4x threshold in four runs out of
   four**, and only the absolute bound ever fired. Two causes. The *shallow* half
   was paying for JIT compilation of the whole raise path (16 430–20 585 ns for a
   1 024-element scan, against 7–8 ns per element once warm), which inflates the
   number the ratio divides by — and inflates it only in the mutated build, where
   the scan is what gets compiled. And an 8x spread is too narrow for the fixed
   cost per raise to disappear from the shallow reading. A **discarded warm-up
   measurement** and a **64x spread** fixed both: the index then measures 0.50x–
   1.57x for 64x the depth and the scan 11.4x–24.8x, against a threshold of 8.
   So: discard one measurement before the first one that counts, and choose the
   spread so the shallow reading is dominated by the thing being measured.

**Sweep by effect, too.** The enumeration recipes above are all *input*-shaped —
"for every setting the file reads", "every input the file branches on" — so they
structurally cannot produce a line whose result the file never reads back. That is
the third step of the same progression: sweep by field covers your own state,
borrowed state covers your delegates' state, and this covers **state you write into
an object that is not yours and never read again** — a Window, a Dialog, a
theme or style, view flags, intent flags, layout params.

The rule: **for every call the file makes into an object it does not own, name the
test that reads the result back.** If there is none, the line is unpinned however
many mutations the input dimensions have survived. A mutation in a load path goes
red because some test reads what it produced; a mutation on a window never goes
red, because nobody reads the window.

It is how a sweep can be complete and blind at once. Measured here: a viewer
enumerated its load outcomes, its arguments, its display metrics and its
dispatchers — four dimensions, all clean — and never touched the fifth, which was
literally the thing the user asked for (fullscreen). And the sharpener: a line
missing from that enumeration is not merely unchecked, it can **mask** the
assertion that would have been the real one. Here `setLayout(MATCH_PARENT, …)` in
`onStart` was a no-op — `PhoneWindow.generateLayout()` already does it for a
non-floating window — and while it stood there, the theme attribute that actually
produces fullscreen could not be made to fail.

**A label or a comparison must name the dimension it holds along, or it is a
mechanism description wearing a guarantee's clothes.** Two sentences from one task
report were copied into this spec as binding guarantees and both were measured
false. Neither was careless — both were *summaries of correct mechanism
descriptions*, and both lost the same thing:

- *"The observer queue is bounded and folding."* The mechanism was stated
  correctly in the same report: trimming runs **when a droppable event arrives**.
  The summary kept the mechanism's name and dropped the condition. The tell is
  grammatical — **"bounded" with no object.** Bounded *in what*? Unsaid reads as
  "in everything".
- *"the same state as a channel whose parent has not arrived yet."* An appositive
  asserting two states are equal. True along the dimension that had been looked at
  (the field is null either way), false along the one that matters: one heals on
  the next frame and the other never does. Same tell — **"the same as X" with no
  "along which axis"** — and note what it smuggles in: a claim about the state's
  *future*, dressed as a claim about its present.

One question catches both: **bounded in what? the same along which axis?** If a
sentence cannot answer that inside itself, it is not a guarantee.

And the part that matters for whoever reads reports: those reports were **not**
uniformly loose. "Goes red in every run" was literally true and a reviewer
confirmed it. It was specifically the **summary** sentences that generalised —
which is the dangerous place for it, because a summary is what gets copied into a
spec.

**"No test can distinguish this" is only writable after the mutation that would
distinguish it has been run.** An unproven unpinnability claim is more expensive
than none: it replaces the measurement with an assertion and immunises exactly the
spot that needed measuring. It also has to name **which single mutation** it means.
Learned the hard way: a KDoc here said "nothing pinnable" about a *lock-nesting*
detail, and the next reader — its own author — took it as a licence covering the
whole `synchronized` block and never mutated it. The lock turned out to be
unpinned and load-bearing; removing it alone threw a `NullPointerException` on the
main thread in three runs out of three. Same shape as the unscoped "no observable
found" sentence, except this one stopped its writer from taking the measurement
that would have refuted it.

**A test-author defect is a defect of the form, not of the site.** Whoever finds
one greps the file for every other occurrence of the idiom **before committing**,
rather than repairing the places currently under the nose. Here a broken race
writer (`i % 2` choosing the branch beside `i % size` choosing the element, so
even iterations only ever added and odd ones only ever removed something absent)
was diagnosed correctly, fixed in the two neighbouring tests that looked alike,
and missed in the third — because the repair followed the shape of the code rather
than the property. One grep; three rounds.

**A surviving guard marks an unexplored dimension, not just an unpinned line.**
When a condition survives mutation, do not only ask "can I pin this?" — ask **what
else in this file branches on the same condition, and what am I about to add that
branches on it?** A survivor says no test distinguishes the two sides of that
condition, which is a statement about the whole input space, not about one line:
every other branch on it is untested too, and any branch added on it is untested
*by construction*.

This cost a critical here. A merge of `ACTION_UP` and `ACTION_CANCEL` passed five
individual mutations, all killed — because every test in the class ran in
push-to-talk *hold* mode, where the merge is correct. In toggle mode, where
`onTalkKeyUp()` **is** the action rather than a release, a cancelled gesture turned
the microphone on and nothing took it back. The sweep was structurally incapable
of seeing it: **a mutation sweep measures whether the tests can see a change, not
whether a branch's discriminating input ever appears in any test.** All five
mutants were sampled from the correct half of the behaviour space. And the tell
was two methods below, in code written in the same round: a `!isPushToTalkToggle()`
guard that had already survived its own mutation. The survivor was the map of the
hole.

The mechanical form, cheap enough to do every time: **for every setting or mode
the file reads, grep the test class for a write of it.** Enumerate from the
*production file*, before looking at the diff — not from what the change made
relevant. The round that wrote this rule then failed it on the next pass, and
diagnosed itself: it swept the settings its own fix had touched, found the one the
cancel branch reads, and stopped. The setting that mattered was read fifty lines
away in a method the diff never went near, and under the narrower reading it never
entered the list. Consequence, measured: all eleven tests in that class were
driving a button the fragment had set to `GONE`, and only worked because the test
helper dispatched touches directly, bypassing hit-testing. The unit is the file the
test class hosts. The unit is **every input the file
branches on**, not only the ones that look like settings: the round that missed
the fourth corner above had read "setting" as "Preference", and the two inputs
that mattered were constructor parameters of a data class — which reach the file
by exactly the path the user operates. A test class that never
writes a preference the file reads is testing exactly one configuration, and the
sweep will confirm whatever that configuration does.

**Sweep by field, not by call path.** The three handles above all check a guard
as it is written. They need the reverse sweep too: for every mutable field, grep
every write and every read, and ask of each write whether any reader can tell it
apart from its absence. The round that derived this rule did the call-path sweep
and found one masked pair, then missed a second — because the two guards on that
field are not on a path at all. One sits in a getter, the other in a teardown, in
different halves of the file, with no call relation whatsoever; the only thing
tying them together is the field they both decide. Two flags with exactly one
reader each had four writes of `false` between them, a ratio ten seconds of grep
makes obvious and no amount of reading call paths will.

**"I keep no state" is a claim about your fields, not about the behaviour.** When a
mutation survives, the next sweep is over the mutable state of every object you
*delegate to*, not only your own — and specifically over the transitions that only
the event you forward can trigger. This was learned the expensive way: a view
forwarded `ACTION_CANCEL` to Android's gesture detectors, a mutation that
swallowed it left the whole suite green, and the conclusion drawn was "there is no
observable, the view holds nothing to unwind". Measured afterwards, there is:
`GestureDetector.mIsDoubleTapping` is cleared by `cancel()` or `ACTION_UP` and by
nothing else, and `ScaleGestureDetector`'s anchored-scale mode resets only on a
complete stream. Neither is cleared by a fresh `ACTION_DOWN`, so "the next gesture
re-bases anyway" is true of the focus and false of the flags — after a double tap,
a swallowed cancel leaves the next drag either dead or zooming. Borrowed state is
still state, and forwarding is the only thing that unwinds it.

**"No observable found" is always scoped to the histories you tried.** Write the
scope into the sentence — "cancelling mid-drag is recoverable, because a plain
DOWN re-bases the focus" — never the bare conclusion. An unscoped "there is
nothing to leave behind" is not a finding, it is a licence: the next reader cashes
it in by deleting the forwarding, and the comment is what told them it was safe.

**Treat "assert after the teardown returned" as a false-green pattern.** Three of
the six survivors in that one file were masked by the same thing: the teardown
drops the state a moment later, so an assertion taken afterwards holds either
way. Only an assertion taken *inside* the window can tell the difference, which
is why a helper that opens that window earns its keep. One of those three guards
turned out to be real and load-bearing — the only gate on the voice path — and it
had survived mutation for exactly this reason, invisible everywhere except in the
window where the audio thread is still handing over frames. That is the window
that matters.

**Mutate a compound condition clause by clause — and know what that does not
prove.** `if (a && b && c)` is three guards wearing one pair of brackets, and
removing the whole condition kills a test while removing `b` alone may not. A
sweep that treats the `if` as one unit reports a clean result over a passenger.
Done properly on one file here: 8 of 8 sub-clauses each killed a test on their
own.

**But clause-wise mutation and input-space coverage are orthogonal, and reading
the first as if it covered the second has already cost a round.** A clause sweep
proves every clause carries weight; it proves **nothing about the operator that
joins them**. `||` mutated to `xor` survived a file whose clause sweep was
complete, because over three of the four corners of a two-boolean input space the
two operators agree — and the test class never wrote the fourth corner. For a
compound condition over k booleans the requirement is **2^k inputs, not k
mutations**. The consequence in that case: a user who switched on both Android
audio effects got neither, silently.

And the tool: do not run the suite once. **Mutate each guard on its own and
require exactly one test to go red.** Here that costs about eleven seconds a run.

Removing state beats adding a guard. Twice in the same round a guard was replaced
by deleting the state that made the error expressible: the connection flags were
given a single writing thread rather than a corrected comment, and the host field
stopped being cleared on teardown rather than being null-checked — `getByName`
resolves both `null` and `""` to loopback, so only never resetting it is safe.

**A cost claim about a call site must count the guards between the entry and the
call.** Same discipline as mutating a compound condition clause by clause, applied
to *reading* instead of testing. Written into this spec as binding: "`onBindViewHolder`
called it twice per row, two full subtree walks per row bound." Two guards sat
between the method entry and those calls — a short-circuiting `||` and an
`if (mShowChannelUserCount)` whose setting is off by default — and with them counted,
the true figure is **0.75 node visits per row**, a ninth of one rebuild. The number
was not merely too big; it named the wrong bottleneck, and the file that names
bottlenecks is the one every stream reads. Grepping the call site is not reading it:
count what stands in front of it, and prefer a measurement to a count.

**A line whose comment explains what would break without it is a line for which a
test has probably not been written.** The explanation is the substitute: writing
the failure out precisely feels like accounting for it, and — unlike running a
mutation — it produces no output that can contradict you. This is the summary case
one level down. It is not a sentence that over-generalises; it is a sentence that
is *correct*, and whose correctness stood in for a measurement. It is also
greppable, which is why it earns a rule: **on writing such a comment, delete the
line, run the suite, and only then write the comment, with the result in it.**
And the sharpest form of it, measured later in the same stream: the explanation is
not always merely *unverified*, it can be **wrong**. A `udp = null` carried a
paragraph about an OCB2 sequence number burned in the window between a callback
being posted and the transport clearing its own flag. Read in the source, the
transport clears that flag as the **first statement of the `finally`, on the same
thread**, long before the callback is dequeued — the window is real and is closed
by the other side first, so the line is a no-op and the paragraph describes a
mechanism it cannot participate in. A wrong explanation defends a line better than
a right one, because it answers the question before anyone asks it.

Two things make it easier to believe, and both are about granularity:

- **A pinned *call site* lends the whole function an air of coverage — the same
  thing one level up.** Measured: swapping a refused-parent fallback back for
  "leave it parentless" turned three tests red, so at call-site granularity the
  function looked covered. **Every single branch inside it could be deleted with
  the suite green** — including the one whose KDoc called its failure "the one
  thing worse than ignoring that frame". So the granularity to sweep at is not the
  call, and not the function: it is the **branch**.
- **A pinned sibling branch lends the whole function an air of coverage.** The
  mutation granularity one reaches for is the function. `forget()` is one `when`
  with two arms; the droppable arm is obviously load-bearing and obviously pinned,
  so at function granularity `forget()` looks tested — while deleting the *fold*
  arm kept the whole suite green. This is 4.04's "two guards over one observable
  read as one guard", in the sibling case: two branches in one function, only one
  of which anything reads back. Sweep by field, sweep by effect — and inside a
  function, sweep **by arm**.
- **A run-time remnant inherits the sense of already-being-proved from the type
  that carries the rest.** The arm arrived in a refactor whose argument was
  structural ("make a folded-and-droppable event unrepresentable"). What the type
  system carries is proved by the type system. What it does not carry is proved by
  nothing, and looks identical in the diff.

And the honest note about how each of this round's two findings was made: the
shadowed ratio assertion surfaced **while a mutation was already running for
another reason** — an output that was being read anyway produced it, not judgement.
The unpinned arm needed a mutation nothing else in the round would have produced.
Findings that cost nothing do not tell you the sweep is working.

**A guard whose premise is false is invisible from both sides, and the answer is to
delete it and pin the premise.** Mutation testing asks whether removing a line
turns a test red. It cannot distinguish "nothing reads this line" from "this line
never did anything", and the second case also refuses to be pinned: an attempt to
write the test goes red against correct production code. Measured here on
`state != newState.constantState` in a talk-state repaint — the icons are layer
lists, and `LayerDrawable.getConstantState()` hands back its **own state, freshly
copied per instance**, so two lookups of the same resource never share one. The
comparison was true on every call; the guard had never once suppressed a repaint.
The move is not to pin it and not to leave it: **delete it** (behaviour-identical,
and it removed a latent dereference with it) and write the premise down as its own
test — *two lookups of one icon never share a constant state* — so that a framework
or resource change turns that red instead of silently giving the dead guard a job.
Note the order this is discovered in: the tell was that **writing the pinning test
produced a RED against correct code**. A test you cannot write is evidence about
the production line, not about your scaffolding.

**Sweep the fakes' outputs as well as the production file's inputs.** The
enumeration recipes point at the production file, and a dimension can be closed off
by the **test double** instead: the fake returns a constant, so every corner behind
that input is unreachable and the enumeration never notices, because the production
file does branch on it. Here `FakeUser.getTexture()` was a constant `null` — so
avatars, a whole dimension, had never been rendered in a test, and the branch's
success case had never run. This is the third instance of the same shape (a mock
returning a constant `emptyList()`, a fake that could not express a null user, this
one), which makes it a rule rather than an anecdote: **for every input the
production file branches on, name the fake that produces it and check it can
produce more than one value.**
Two riders, both earned the hard way. **Run the enumeration to exhaustion, not to
the first find.** The pass that found `useTor` — a dimension closed *by construction*
across an entire repository — stopped there, and had two more answers in it: the
same fake discarded the host and port it was handed, and a sibling fake discarded
the crypt state, which made three of six decisions unreachable end-to-end. A pass
that produces one good find feels like it has done its work; it has only started.
And **say when a pass was not blind.** The enumeration is supposed to happen before
the diff is read. When that order slipped, the honest report was "treat this as an
enumeration *from* the production file rather than one made blind" — which is worth
more than the pass pretending to a provenance it does not have.
Third rider, about your own correct work: **applying a rule once does not discharge
it.** The same author who spelled out "2^k inputs, not k mutations" in a test's KDoc,
and satisfied it exactly for one compound condition, left the four-corner gap open on
the predicate he had just opened up two files away. A rule is a grep, not a habit.

**A mutation sweep inherits the blind spots of the fixture set.** It measures
whether the tests can *see* a change; it cannot tell you that a branch's
discriminating input never appears in any test at all. Fifty-six mutants, all
killed — and every one of them had been sampled from one half of a two-boolean
input space, because every image fixture in the suite was an `InfoMessage` and none
was the `TextMessage` that a user sending a picture actually produces. The corner
that was missing was the feature's main use case, and the mutation that breaks it
survived all 27 tests.
The tell is the one §4.04 already gives — enumerate the corners **from the
production file** — but this is the case where the enumeration has to reach the
**fixtures**: for each corner, name the fixture that *is* that corner, not the
parameter that could be set to it.
And the method for reporting one, because it separates two very different things:
**write the test, run it on HEAD, then run it under the mutation.** Passing on HEAD
and failing under the mutation proves a pure coverage hole. Failing on HEAD would
have proved a live defect. Saying which one it is costs one extra run and is the
difference between "the evidence is missing" and "the app is broken".

### 4.05 Testing hazards that have already produced a false green

Both were caught in this project, each after a test had been written, reviewed
and reported as passing. They are repo-wide, not stream-specific.

- **kotlinx.coroutines renames threads.** While a coroutine runs on a thread, its
  name becomes `"<name> @coroutine#<n>"`. An assertion of the form
  `assertThat(threadNames).doesNotContain("mumla-test-caller")` therefore passes
  even when that very thread did the work. This was found only because the
  measured runtime did not fit the claim. Strip the ` @coroutine#` suffix before
  comparing, in every thread assertion.
- **A Kotlin `var` clashes with any `fun getX`/`fun setX` the interface declares.**
  `override var isTalking` generates `setTalking(Z)V`, which collides with an
  interface's own `fun setTalking`; `var service` collides with a Java interface's
  `getService()` the same way. This has now cost three separate rounds — twice in a
  brief's test listing and once in a fresh test scaffold — so the rule is: a fake
  implementing an interface with explicit accessors backs the value in a private
  field and overrides the accessors, never with a `var`.
- **A concurrent `ArrayList` write is not always visible to a snapshot reader, and
  which write it is decides everything.** Measured standalone on x86_64: 2 484
  concurrent `toArray()` copies taken while another thread does pure tail `add(e)`
  produced **0 holes, 0 duplicates, 0 exceptions** — the element store is ordered
  before the size store, so no hole can appear. Switch the writer to the sorted
  insert `add(i, e)` that real code uses and **5 947 of 5 951** copies contain a
  **duplicate**, because the tail is shifted right with one `System.arraycopy` and
  a copy taken mid-shift sees the moved element twice. Removal is the hole
  producer: `fastRemove` writes `es[size = newSize] = null`, 7 659–11 502 holes per
  run. So a race test needs **three** damage signals — an exception, a hole, and a
  duplicate — and the one most likely to be missing is the duplicate. The caveat
  that goes with this: "invisible on TSO" is exact only for the tail append. When
  three such guards survived mutation here, the cause was a broken test writer
  (`i % 2` for the branch and `i % size` for the element, so even indices only
  ever added and odd ones only ever removed something absent), not the memory
  model. Check the writer before believing the architecture.
- **`FileProvider` caches its parsed roots per authority for the life of the JVM,
  and Robolectric gives every test *method* a fresh data directory.** The second
  method in a class that calls `getUriForFile` is answered by a strategy built for
  the first method's `cacheDir` and dies with `Failed to find configured root`.
  Measured: **7 of 13 tests failed, and which ones depended on JUnit's method
  order** — a perfect "looks covered, isn't" if the six survivors had been the ones
  under inspection. Clear the static cache by reflection in `@Before`. On a device
  the directory does not move, so this is a harness fix only.
- **A hot-window-only allocation measurement is a lie.** HotSpot's C2
  scalar-replaces the `Iterator` of a `for (x in aList)`, so a chain that really
  allocates reads **0.000 B** in a warmed-up window and passes. The same code in a
  cold window reads **32.000 B per frame** — and ART performs no such elimination
  for an interface iterator, so the hot-only number certifies an allocation the
  device actually makes. Measure **both** windows, validate the instrument each run
  against a known allocation (a `ShortArray(480)` is exactly 976.0 B), and assert
  against half the smallest object the JVM can allocate rather than against zero:
  one stray JIT-bookkeeping allocation in an 8 000-call window reads as 0.238 B per
  call and fails an `isEqualTo(0.0)` about one run in nine.
- **A no-op mutation reads exactly like a proven-unpinnable guard.** Two mutations
  in one sweep here reported SURVIVED because they changed a storage type or added
  an unused field without changing behaviour. Both killed once corrected. Before
  recording a survivor, check that the mutation changes what the code *does*.
- **A removed guard can hang the suite instead of failing it.** Deleting a
  `count < 0` check in a native bridge does not produce a red test: `-1` becomes a
  four-billion unsigned count and the library runs. A mutation sweep without a
  per-test timeout stalls on the first such mutation and produces nothing — one
  here burned twenty minutes before anyone noticed. Pass `--timeout` to `ctest`,
  and treat a sweep that produces no output as a result to investigate rather than
  a run to repeat.
- **A naive sequence assertion over hundreds of thousands of elements is expensive
  enough to look like a hang — and that is a different entry from the one above.**
  The `ctest` case above is a real hang. This one was written here as one and was
  not; it was measured twice, in two streams, and both readings say the same thing.
  Two ~3 000 000-element `List<Short>` handed to `isEqualTo` with a **single** sample
  differing: **273 s and a 43 MB failure message**, against 0.1 s for that test green
  and ~12 s for the whole module. (A smaller pairing measured 29 s against a 12–15 s
  baseline — same shape, same conclusion.) It fails, with output. But under a
  per-test timeout below that, it becomes a timeout instead of a diagnosis, and the
  message goes into the XML, the HTML report and the CI log.
  Two riders, both learned by getting them wrong first. The divergence has to be
  **content-only**: seed it by changing a count and the cheap size assertion fires
  ahead of the comparison, the expensive one never runs, and you conclude the form
  is fine (12 s, 152 characters of output). And the right form is an index loop that
  reports the **first** diverging index — `diverges at sample %s` — which costs
  nothing and says more.
- **The graphics mode decides which claims are even expressible, and it cuts both ways.**
  Measured on the outgoing-image path, same code, both modes:
  - **Orientation only exists under NATIVE.** An eight-orientation JPEG read through
    `ImageDecoder` reports `60x40` for orientations 1–4 and **`40x60` for 5–8** under
    `@GraphicsMode(NATIVE)`, and **`60x40` for all eight** under legacy, whose
    `ShadowImageDecoder` reads width, height and MIME from the header and nothing else.
    A legacy suite cannot see a double rotation — which is what the brief here
    prescribed, and it would have turned every rotated photo a half turn.
  - **Memory claims invert under legacy.** Legacy's decoder produces the **full-size**
    bitmap and scales afterwards, so a legacy suite measuring "one allocation the size
    of what is kept" measures the opposite of the truth.
  - **And the tool for counting allocations does not exist under NATIVE.**
    `shadowOf(bitmap).getCreatedFromBitmap()` works under legacy
    (`ShadowLegacyBitmap`) and throws **`UnsupportedOperationException`** under NATIVE
    (`ShadowNativeBitmap`). So a suite that needs NATIVE for correctness cannot use the
    allocation-counting instrument, and any obligation written in terms of it is
    impossible for that suite — as one in this project's own ledger was. State the
    substitution and why it was forced; do not let it look like a weaker test chosen
    freely.
  The rule: **pick the graphics mode from the dimension under test, then say which
  assertions that choice makes unwritable.** Per-test where the neighbours do not need
  it, whole-suite where every corner does — the cost measured here was 3.23 s plus
  1.04 s for 35 tests, far less than feared.
- **Under Robolectric's legacy graphics, `BitmapFactory` decodes anything.** Hand it
  arbitrary bytes and it returns a `Bitmap` rather than null, so the corner "these
  bytes do not decode" — exactly the one a `yes, decoding can fail` comment is
  written over — is **unwritable**, and the attempt goes red against correct code.
  The success case then passes for the wrong reason. `@GraphicsMode(NATIVE)` on that
  one test makes it expressible; note it is per-test, because native graphics is
  slower and not needed by its neighbours. Same family as `inJustDecodeBounds`,
  which legacy graphics does not implement at all.
- **`dispatchTouchEvent` on the target view is still not what a finger does — the
  visibility filter lives in the *parent*.** `performClick()` ignores `isEnabled`
  entirely (that is the entry below, and it drove eleven tests against an invisible
  button in one stream). The repair everyone reaches for next — dispatch a touch
  straight at the view under test — has the **same shape one level up**:
  `ViewGroup.canViewReceivePointerEvents` is what drops events for a `GONE` child,
  so a touch delivered directly to that child runs its listener anyway. Measured: a
  test asserting "a tap on a failed (GONE) row reports nothing" **failed** under
  direct delivery and passed only when the event entered at the row and was routed
  down. And routing needs a real window: an **unattached** view puts its click into
  the `HandlerActionQueue` instead of running it, while `post()` still returns
  `true`, so the test reads as green either way. So: a real `Activity`,
  `setContentView`, `measure` and `layout` — or the assertion is about the harness.
- **Check the harness that reads the results, not just the one that runs them.** A
  passing JUnit test is a **self-closing** `<testcase/>` element, so a lazy
  `(.*?)</testcase>` regex attaches the next `<failure>` to the first *passing* test
  in the file. Measured symptom: the same innocent test reported red under all
  twenty mutations, and one real survivor hidden among them. Parse the XML with a
  parser. This is the second harness defect in this project to invert verdicts
  wholesale — the first read only stdout while Kotlin writes compile errors to
  stderr — which makes it a class: **before believing a sweep, run one mutation you
  are certain kills and one you are certain does not, and check the harness reports
  both correctly.**
- **A mutation that does not compile reads as a survivor if you only watch stdout.**
  Kotlin writes compile errors to **stderr**. Three "survivors" in one sweep were
  mutations the compiler had rejected — `if (false)` had destroyed a smart cast — and
  the harness, which grepped stdout for compile errors, filed them as unpinned
  guards. This is §4.04's no-op hazard displaced into the tooling, and it is worth
  the same suspicion: **re-run a survivor in a form that certainly compiles** (here
  `cond && System.nanoTime() < 0`) before writing it down. Two riders from the same
  sweep: an XML mutation must carry enough context to be unique — `layout_height=
  "wrap_content"` appeared five times in one file — and a **900 s per-run timeout**,
  because a hung mutant and a killed one look identical from outside.
- **An assertion is shadowed by any earlier assertion in the same test, and that is
  how coverage gets mis-attributed.** Same mechanism as an absolute bound placed
  ahead of a ratio (§4.04), but about *attribution* rather than sensitivity:
  "this line is already covered by that test" is a claim that the test **reaches**
  the assertion. Measured: an `android:focusable` attribute was about to be written
  off as covered by `clickable` — and under the mutation the test failed at the
  earlier `isClickable` assertion, so the `isFocusable` line never ran. The cover
  did not exist. Check by running the mutation, not by reading the test.
- **One Gradle daemon is shared across every worktree in this session, and another
  agent's `--stop` reads as a passing baseline.** Measured: a mutation batch died on
  `Gradle build daemon has been stopped: stop command received`, and the harness
  filed the **baseline** run as a failure — which in a sweep means every mutation
  after it is recorded as KILLED. Same class as the shared scratchpad that had a
  mutation script overwritten under a running agent, one level up: it does not
  corrupt one number, it inverts every verdict in the batch. So: **detect
  `daemon has been stopped` and re-run rather than record a verdict**, keep tooling
  in a per-agent subfolder of the scratchpad, and treat a baseline that fails as a
  reason to stop rather than a data point.
- **zsh does not word-split an unquoted `$VAR`.** A mutation harness written for bash
  and run under this project's shell reported **NO RESULTS** for four mutations
  instead of a verdict — silently, because "no results" is not "failed". Same family
  as the stdout/stderr and regex cases: the tooling answered a question nobody asked.
  Quote or use arrays, and make "no result" an error rather than a row.
- **A test or lint count summed off disk includes reports the run did not produce.**
  `build/**/reports` keeps the previous flavour's results, so a counter that globs
  them reports a total no single command produced. Seen twice in one task: a gate
  that runs exactly two test tasks (**388** tests) was recorded as **1 388**, and a
  lint count over "all five reports" included four flavours that gate never built.
  Neither number was wrong on purpose and both read as authoritative. Count what
  **this** invocation wrote — or clean first — and name the command that produced it.
- **Read a SARIF result's *effective* level, and trust the build's exit status more.**
  An earlier version of this entry said to read each result's `level` rather than
  the rule default. That is **wrong as a general rule, and it was measured**: in
  `lint-results-fossDebug.sarif`, **10 of 325 results carry a `level` field at
  all**, and they are exactly the `MissingQuantity` hits the module's own config
  demotes. Every other result omits `level` and inherits from
  `rules[ruleId].defaultConfiguration.level`. A counter that reads `result.level`
  with a "warning" fallback therefore reports **0 errors for a build lint fails** —
  demonstrated by deleting an unused string, which produced 23 `ExtraTranslation`
  results, none of them carrying a `level`, rule default `error`, `Lint found 23
  errors`, build aborted. The correct formulation is **effective level =
  `result.level` if present, otherwise the rule's default** — and the gradle task's
  exit status stays the real gate. Earlier rounds' "0 errors" claims are safe
  because those builds passed, but the method they cite would not have caught a
  regression.
- **Robolectric's gesture constants are fixtures, not Android.**
  `ShadowViewConfiguration` hard-codes touch slop 16, paging touch slop 32 and
  double-tap slop 100 at density 1.0, and the 170 px minimum scaling span sits
  behind the `robolectric.useRealMinScalingSpan` switch. On a real 3x device the
  touch slop is about 24 px, the span slop about 48, and `config_min_scaling_span`
  is a physical 27 mm. `ShadowGestureDetector` and `ShadowScaleGestureDetector` do
  exist and are in the path — they delegate the logic to the real class by
  reflector but can override `scaleFactor` and the focus. So the real AOSP logic
  runs, over emulated constants, through a shadow. Two consequences measured here:
  the first `onScale` after `onScaleBegin` always reports factor exactly 1.0 and
  re-bases the span, and a naive synthetic pinch of 180 to 200 px therefore
  produces no zoom at all — once because the 20 px change is under the span slop,
  and again because the first callback is 1.0. A test asserting "scale > 1" from a
  small synthetic pinch gets a mystery or, worse, a vacuous pass.
- **Robolectric does not implement `inJustDecodeBounds`.** Its
  `ShadowBitmapFactory.create` allocates the full bitmap for the bounds pass, so a
  heap-delta measurement of a decode path measures the opposite of what it claims
  (bounds pass 120 MB allocated against 72 MB for the sampled decode). Measure
  `byteCount` of the bitmap the path actually produced, or reach the source
  instance through `shadowOf(bitmap).createdFromBitmap`, and never assert a heap
  delta. `ShadowBitmapFactory` also invents a 100x100 bitmap for undecodable bytes
  unless `setAllowInvalidImageData(false)` is set, and `@Config(shadows = [...])`
  that replaces the shadow silently drops that switch.

### 4.1 Binding constraints discovered during execution

**Check the ownership table before deferring anything.** Three times now work
has been handed to a task that does not own the file: the observer-queue cap
went to a task that never opens `HumlaCallbacks.kt`, an unchecked length field
went to a task that never opens `HumlaTCP.kt`, and `mConnectionState`'s missing
`@Volatile` was addressed to task 11 (notifications) when task 9 owns
`HumlaService.java` and already declares that very field volatile in its own
listing. Each time the sentence read as if the work were scheduled. Name the
task from the ownership table, not from memory of what a task is about.

These were found by implementers and reviewers after the plans were written. They
are binding on the tasks named, and they live here rather than in a stream ledger
because `.superpowers/sdd/` is gitignored — a ledger disappears with its worktree.

- **Close the model race task 4 opened (A, task 5, hard precondition for
  integration).** Task 4 moved frame parsing off the main looper, which is what it
  was for — and with it `ModelHandler`, which writes `mChannels`/`mUsers` (plain
  `HashMap`) and `Channel.mSubchannels`/`mUsers` (plain `ArrayList`). The UI reads
  those same objects on main: `ChannelListAdapter.updateChannels()` runs from the
  `onChannelAdded`/`onUserJoinedChannel` observers and iterates `getUsers()`
  (`:444`) and `getSubchannels()` (`:450`) while the protocol thread is still
  feeding frames. **Measured: `ConcurrentModificationException` after 158
  iterations, at frame 835 of 5 000.** `updateChannels()` catches only
  `IllegalStateException`, so it is not covered. Same root cause, not measured: an
  unsynchronised `HashMap` read during a `put` resize can return `null` for a key
  that is present. The user-visible failure is a crash on joining a large server
  with the channel list open — exactly the moment task 4 exists to speed up. The
  ownership table already assigns the fix (`ModelHandler.java`: `ConcurrentHashMap`
  + `volatile`; `Channel`/`User`: copy-on-read lists), so this is a sequencing
  constraint, not new work: **the branch is not integrable until task 5 lands**,
  and task 5 takes the measured reproduction above as its acceptance test rather
  than writing a new one.
- **Take `AudioHandler.shutdown()` off the main thread (A, tasks 7 and 9).**
  Section 4 of this spec says `shutdown()` "is safe to call from any thread and
  returns within 3 s worst case; stream A calls it only from the audio-control
  thread", and section 6 requires "no main-thread join in disconnect". Neither
  holds today: `HumlaService.onConnectionDisconnected` (`:447-449`) and
  `HumlaService.java:534` both call it on main, and it joins the audio threads
  with no timeout (`AudioHandler.shutdown()` -> `AudioInput.shutdown()` ->
  `stopRecording()` -> `mRecordThread.join()`, `AudioInput.java:149`). Task 4 made
  the path newly reachable from `onDestroy()` as well. This is an unmet acceptance
  item, not an observation, and it is the second half of the "not responding" root
  cause -- task 4 fixed the first half by moving parsing off main.
- **The decoder must stop holding two bitmaps (D, task 10 — and the recipe below
  replaces the one I first wrote).** Measured: `BoundedBitmapDecoder` samples by
  powers of two and then calls `Bitmap.createScaledBitmap`, so the sampled
  intermediate **and** the result are alive together. Sampling stops as soon as one
  more halving would undershoot, leaving the intermediate in [1x, 2x) of the target
  per axis — up to 4x the pixels, so a peak of **just over 5x** what is kept
  (measured 5.0026; the excess is the integer truncation in `resizeKeepingAspect`,
  so 5 is a supremum, not an exact value). Scaled to a 1080x2340 phone, **K=2 peaks
  at ~193 MiB** against the 128 MiB floor this spec names as its own criterion.
  **Trivially reachable: a flat 4320x9360 PNG is 136 303 bytes** — a factor of 38
  under the fetch cap, not "plausible JPEG size" but something anyone builds in ten
  seconds. Nothing catches `OutOfMemoryError`.
  **Two corrections to my first version of this entry.**
  (1) It said "(D, binding)" and named **no task**. `BoundedBitmapDecoder.kt`
  belongs to task 5, which is closed, and task 6 is closed; no open task in the D
  plan opens that file. That is the fourth time work has been addressed to nobody,
  and the first time by the entry that warns about it. It goes to **task 10**,
  which already consumes `sampleSizeFor` for the send path and meets the same peak
  there — and which runs before task 11 wires the tap that makes the viewer
  reachable at all.
  (2) The recipe was wrong. `inScaled` does not remove the second bitmap:
  `BitmapFactory::doDecode` implements it exactly as this decoder does by hand —
  decode sampled, then draw into a second, scaled bitmap — and when scaling is
  required it redirects `inBitmap` to the heap allocator, so reuse is no escape
  either. `ImageDecoder.setTargetSize` only helps codecs with native scaled
  decoding (JPEG, WebP) and **not PNG**, which is the format the worst case is
  built from. Left as written, someone implements `inScaled`, reports it done, and
  the peak is unchanged.
  **What actually works: round the sample size up instead of down** (`while (1f /
  sample > fit) sample *= 2`) and drop `resizeKeepingAspect` on the fullscreen
  path. The decoded bitmap is then always at or below the box — one allocation,
  peak equals what is kept, at most 40.4 MB at K=2. The cost is up to one halving
  of detail against an exact fit, which lands the effective sharpness between K=1
  and K=2 at K=1's memory. It costs nothing in display, because `ZoomImageView`
  uses `ScaleType.MATRIX` and derives `maxScale` from the intrinsic size.

- **Decide the zoom ceiling against the decoder, not by taste (D, tasks 7 and 8).**
  `MAX_SCALE = 5` came from the plan and nobody checked it against what the
  decoder produces. The chain, read out of the code: the viewer calls
  `loadFull(source, screenWidth, screenHeight)`, `BoundedBitmapDecoder.decode`
  ends on `BitmapUtils.resizeKeepingAspect`, and that never enlarges — so the
  decoded bitmap is exactly view-sized on the limiting axis, `fitScale` is 1.0,
  and **every zoom past the fit is pure upscaling**: at 5x one source pixel covers
  twenty-five screen pixels. The same bound is what the task-7 brief cites for not
  needing tile subsampling, so the decision was made without following its own
  consequence. Two knobs that have to move together: decode at K x screen for the
  full-screen viewer (1080x2340 ARGB_8888: K=1 is 10.1 MB, **K=2 is 40.4 MB**,
  K=3 is 91 MB, K=5 is 253 MB — against the 128 MiB `heapgrowthlimit` floor
  measured in task 6 and the `maxMemory()/8` cache, **K=2 is the largest
  defensible**), and derive the ceiling per image rather than fixing it:
  `maxScale = (1f / fitScale).coerceIn(2f, 5f)`, i.e. zoom until one source pixel
  is one screen pixel, but at least 2x so a small image stays inspectable and at
  most 5x so a tiny one does not become mush. That cannot live in `ZoomState`'s
  `init require`, because a state valid for one image would be invalid for
  another; it belongs in `scaledBy`'s `coerceIn`, with only a generous absolute
  limit left in the constructor. **Lowering `MAX_SCALE` makes a stored 5x crash on
  the next rotation unless the restore path coerces instead of requiring.**
- **Pin the viewer's two restore conditions where the viewer is (D, task 8).**
  `ZoomImageView` saves nothing without an `android:id`, and any placeholder with
  an intrinsic size spends the restored zoom so the real image arriving afterwards
  counts as a second image and resets to the fit (a `ColorDrawable`, intrinsic −1,
  does not). Both measured. The plan's layout and dialog already satisfy them, but
  they are written down only in another file's KDoc, and the task-8 brief mentions
  neither "placeholder" nor "restore". One test in the dialog's own suite using
  `scenario.recreate()` twice pins both conditions and the two-rotation case.

- **The overlay's talk button has the defect the fragment's just had (P, task 8).**
  `MumlaOverlay.java:170-181` handles only DOWN and UP, never `ACTION_CANCEL`, and
  calls `setTalkingState(true/false)` directly. A gesture the system takes away
  therefore leaves the microphone open — in an overlay window, which nothing
  pauses. Same shape as the fragment's, worse consequence, and `MumlaOverlay` was
  in no ownership list either. Note the asymmetry the fragment fix ran into: in
  hold mode a cancel must release, but in toggle mode `onTalkKeyUp()` *is* the
  action, so a cancel must do nothing at all. The overlay calls `setTalkingState`
  directly, so it needs the release unconditionally — but check that against the
  toggle preference before writing it.
- **QA must cover a held media-key press, not only a tap (P, tasks 4 and 5).**
  Acting on DOWN is the right trade, but it has a consequence worth stating to the
  user: once a press generates key repeats, the system stops tracking it and
  everything that reaches us — the repeats and the final UP — is swallowed. A
  button held past the repeat threshold (~400 ms) produces **no toggle at all**.
  That is a new route to the complaint this whole project started from ("I pressed
  it and nobody heard me"), so the hardware QA item covers a held press as well as
  a tap, and task 5's settings copy says *tap, do not hold*.

- **Task 3's shipped native interfaces are authoritative; task 6 does not
  re-declare them (B, task 6).** Plan §0.4 and the task 6 listing put `RnnoiseApi`
  and `WebRtcApmApi` in `se.lublin.humla.audio.capture`; task 3 shipped both in
  `se.lublin.humla.audio.native`. `RnnoiseApi` is identical, so the duplicate would
  merely be waste. `WebRtcApmApi` is **not**: the shipped one takes flat booleans
  rather than a config object and carries an extra `frameSize(handle)`. Task 6's
  planned fake implements neither and would not compile, and `WebRtcApmNative`
  could not be handed to `WebRtcApmPreprocessor` without a pointless adapter.
  Ruling: use the shipped interfaces, delete the re-declarations from the plan's
  listing. Likewise `FarEndSink` is declared in `CapturePreprocessor.kt` by task 4,
  not in `WebRtcApmPreprocessor.kt` as §0.4 plans — it is half of what the one lock
  must cover, and splitting the declaration is exactly what makes a stage with two
  audio threads look like it has one. **Task 6 must not re-declare it.**
- **`Float?` stays as the per-frame probability return (B, decided).** Measured:
  every stage that *computes* a probability boxes 16 B per frame, so three stages
  cost about 4.8 KB/s on the audio thread. Kept anyway. That rate is two to three
  orders of magnitude below what moves ART's allocation-triggered collection, and
  the alternative — a primitive with a NaN sentinel — trades a type-system
  guarantee for a convention every future stage has to remember, in a contract
  tasks 5 through 8 are already written against. Revisit only if on-device
  profiling shows dropouts attributable to it; the measurement is in
  `CaptureThreadAllocationTest` so the number does not have to be rediscovered.
- **Scope the "release must never overlap" rule to what is not a stage (B, task 11).**
  Two binding texts now contradict each other. `SingleHandleStage` states that
  `release()` may overlap a frame in flight and is safe — that is the whole purpose
  of the one lock, and it is measured. The task 11 plan text says the opposite
  ("the KDoc **requires** that release() never overlaps") and deliberately leaks
  native state on a join timeout because of it. Both were true when written; only
  the second still is, and only for the **resampler**, which is not a
  `SingleHandleStage`. Rewrite the task 11 text to say so before dispatching it.
  Leaving both sentences standing is how a later reader concludes that release
  never overlaps anyway and deletes the lock.
- **Never call into a preprocessor stage while holding `mEncoderLock` (B, task 11).**
  The capture thread will hold `mEncoderLock` around `encode()` and the stage lock
  around `process()`. Nothing takes them in both orders today and nothing may: the
  inversion is a deadlock between the capture and playback threads. Also note
  `release()` can now block for one native call (~0.3 ms at 48 kHz), so spec §4's
  "shutdown returns within 3 s" has a real dependency where it had a free
  operation.
- **The WebRTC APM runs with its own noise suppression off (B, decided).** B2 above
  used to say "NS + AEC3 + AGC2 + high-pass", and task 6 implemented that
  literally, then flagged the consequence rather than deciding it alone — correctly.
  The consequence is in two halves and the second one settles it: with NS set to
  Speex or RNNoise, **two noise suppressors run cascaded**, which is an accident
  rather than a design (RNNoise's model is speech plus additive noise, and feeding
  it pre-suppressed audio is not the input it was trained on); and with NS set to
  **none**, the APM suppresses noise anyway — **a switch that does not do what it
  says**. This whole project has been about removing switches that lie: the media
  key that did nothing, the AGC setting that never reached Speex, the preference
  whose default disagreed with its XML. Adding one deliberately is not available.
  **AEC3, AGC2 and the high-pass stay on.** AGC2 in particular, because it is the
  only **automatic** gain control **inside the capture chain, today** — Speex's is
  dead code in the fixed-point build (`FIXED_POINT` at `CMakeLists.txt:92,108`,
  `SET_AGC` under `#ifndef FIXED_POINT`). The axis matters, because the first
  version of this sentence said "the only gain control left in the project" and
  that is false in two directions: `AudioHandler.java:454-458` applies
  `mAmplitudeBoost` on the capture path today, and **B6 requires adding Android's
  `AutomaticGainControl` as a settings toggle** — a binding sibling requirement the
  unqualified sentence contradicted. The high-pass helps the canceller and costs
  nothing.
  **Second consequence, measured in task 7 against the real APM, and it is not the
  one this entry first predicted (B, binding).** 800 frames per point, read after
  5 s of settling, three non-speech characters plus a speech-shaped signal:
  - **The non-speech floor is now pinned rather than merely louder.** With the
    APM's suppressor off, webrtc's own `AdaptiveDigital::max_output_noise_level_dbfs
    = -50` binds, and a non-speech frame settles at **−45.0 dBFS whatever the input**
    (−44.97 / −44.98 / −44.98 / −44.67 for low-passed noise at −60/−55/−50/−45 in).
    With NS on it *tracked* the input: −61.9 / −56.6 / −51.1 / −45.6.
  - **"Every non-speech frame measures louder" is false along the input-level axis.**
    The shift is **+16.9 dB at −70 and −60 dBFS in, +11.7 at −55, +6.1 at −50,
    +0.95 at −45, −0.42 at −35, −1.31 at −30** — above about −45 dBFS in it measures
    *quieter*. It is the cap clamping, not a uniform offset. (Decomposed with AGC2
    removed the offset *is* uniform, +13…+17 dB, and it hits speech as hard as noise:
    the APM's suppressor attenuates broadband here, it does not separate.)
  - **What actually moved is the headroom for speech**, by about the 4.9 dB of
    effective SNR the suppressor used to hand AGC2: the same input now yields a
    probability **0.16 lower — 0.608 → 0.443**. Under NS it sat *just* over B5's
    start of 0.6; it is now under it.
  - **The live defect is at the bottom of the window, not the top.**
    `LevelToProbability.SILENCE_DBFS = −50` is below anything the chain now
    produces: `fromDbfs` never returns less than **0.167**, so **any stop threshold
    below 0.167 can never be crossed and the detector would never release**. B5's
    default stop of 0.3 clears it by 4.0 dB of level; a task-12 slider does not.
  **Ruling, corrected by the review that measured it independently.** The first
  version of this ruling moved `SILENCE_DBFS` to −45 and left `FULL_DBFS` at −20 on
  the argument that the top could not be calibrated and should therefore stay put.
  **That is wrong, and the arithmetic is not subtle:** `fromDbfs` is a ratio over the
  window *width*, so raising one edge rescales the whole curve. Leaving the top alone
  does not preserve the top; it tightens it.

  | window | start 0.6 as a level | **SNR above the floor** | stop 0.3 | SNR |
  |---|---|---|---|---|
  | today −50/−20 | −32.0 dBFS | **13.0 dB** | −41.0 dBFS | 4.0 dB |
  | −45/−20 (first ruling) | −30.0 dBFS | **15.0 dB** | −37.5 dBFS | 7.5 dB |
  | −45/−25 (task 7's proposal) | −33.0 dBFS | **12.0 dB** | −39.0 dBFS | 6.0 dB |
  | **−45/−23.3 (adopted)** | −32.0 dBFS | **13.0 dB** | −38.5 dBFS | 6.5 dB |

  The first ruling would have demanded **2 dB more** SNR than today, and put the
  weakest talker of the 15–30 dB range named in this very entry at **exactly 0.600** —
  a coin flow per frame. Measured on the same speech stand-in, its stop reserve
  shrinks from 0.143 to 0.032, so the tail of a sentence clips.

  **Adopted: `SILENCE_DBFS = −45`, `FULL_DBFS = −23.3`.** The bottom is a defect and
  is fixed; the top is chosen to be the value that **changes the start contract least**
  — 13.0 dB, exactly today's — because it cannot be calibrated without a real talker
  and the honest move is to claim nothing. The stop necessarily moves from 4.0 to
  6.5 dB SNR: with one edge pinned by the floor there is one free parameter and two
  contracts, and the start is the one that decides whether a person is heard at all.
  The reviewer preferred the round −25; it is defensible and 1 dB more generous, and
  it is rejected only because it changes a contract nobody has measured.
  **What is binding about "0.6":** it is not a loudness, it is a demand for SNR above
  the floor — **13.0 dB under the adopted window**, 13.0 under today's, 15.0 under the
  ruling this replaces. The first version of that sentence gave the number without its
  window, in the same paragraph as a ruling that changed the window. Name the axis.
  **Still open, and it grew.** The NS-off decision itself cost speech about 4.9 dB of
  effective SNR (probability 0.61 → 0.44 on the same input), so holding the *nominal*
  start contract at 13.0 dB leaves a real talker slightly worse off than before that
  decision. Whether to spend that back is a question for a real voice, not a
  synthetic one — **QA item with hardware, owner B task 13** (live input meter and
  loopback), together with the top of the window itself.
  **Measurement caveats, from two independent runs.** The floor is **not** one number:
  across two measurements and three non-speech characters it spans **−43.5 … −47.8
  dBFS**, median ≈ −45, and it is flat against *input level* inside one character —
  that is the axis. −45 is a measured median with spread, **not** derived from
  webrtc's constant (that constant is −50). And the sign reversal the first version of
  this entry recorded as fact (−0.42 dB at −35, −1.31 at −30) **did not reproduce**:
  27 points, three characters, minimum **+3.18 dB**, never negative, and the curve
  turns back up above −42 because the same AGC2 ceiling binds with NS on. What both
  runs do support: the shift falls from about +17 dB to a few dB near −45 dBFS in and
  is not meaningful above that — it is the cap clamping, not a uniform offset. The
  cleanest evidence for that is the decomposition with AGC2 removed, where the offset
  **is** uniform: +18.13 dB at seven of eight points, identical to two decimals.
    **First consequence, as originally written:**
  `humla_apm.cpp:88-91` measures `last_level_dbfs` on the **processed** frame, i.e.
  after NS and AGC2. With NS off, every non-speech frame measures louder — and in
  the configuration NS=`NONE` + echo=`WEBRTC`, `LevelToProbability` (−50…−20 dBFS)
  is the **only** opinion in the chain, which is what task 7 gates transmission on.
  Task 7 owns re-checking that window against the new levels; it must not meet this
  as a surprise.
  The constant is named and its test compares against a written-out literal, so
  each of the four flags going the other way turns a test red. That is what makes
  this decision reversible on purpose rather than by accident. Measured: the
  production change alone turns `the apm is built for echo cancellation at 48 kHz`
  red, which is the property the decision claims for itself.
- **Drop `SET_VAD` and `SET_PROB_START` from the Speex stage (B, decided).** Both
  are answered by the library and both are **observably inert for this stage**:
  `vad_enabled`, `speech_prob_start` and `speech_prob_continue` are read in exactly
  one place, the hysteresis at `preprocess.c:993-1002`, and that decides only the
  **return value of `speex_preprocess_run`** — which the stage discards in favour of
  reading `GET_PROB` directly. Spec B9 names the `GET_PROB_START`→`SET_PROB_START`
  fix because the legacy code issued a *get* where a *set* was meant; the purpose of
  that fix was to make the hysteresis work, and this stage does not use the
  hysteresis. Reading the probability directly is strictly better than configuring a
  threshold on a value that is thrown away. And the continue half **cannot be set at
  all** through the bridge's allow list, so speex's hysteresis could only ever be
  half-configured here. Two configuration calls whose own KDoc explains that nothing
  observable depends on them are the licence pattern in call form. B9 is satisfied
  by the direct read; say so where the calls used to be.
- **Three Speex control calls are dead and must not be reissued (B, measured).**
  `SET_AGC` (2) and `SET_AGC_TARGET` (46) return −1: the whole block sits behind
  `#ifndef FIXED_POINT` (`preprocess.c:1057,1193`) and `CMakeLists.txt:108` defines
  `FIXED_POINT` for this target. `SET_DEREVERB` (8) is answered but sets a field
  **nothing in `preprocess.c` ever reads**. And `SET_PROB_CONTINUE` (16) is not on
  the bridge's allow list at all, so it is refused before it reaches speex — the
  plan's own listing calls it, and the plan's own fake would have recorded the
  refused call as a *successful* set. Name every request through
  `SpeexPreprocessNative`'s constants, which the bridge documents its allow list as
  mirroring: a refused request then has no constant to spell it with and **does not
  compile**.
- **Do not throw from the capture thread (B, task 6).** Task 6's planned
  `RnnoisePreprocessor` test requires `process` to throw `IllegalArgumentException`
  on a 441-sample frame. That is an exception once per frame from the audio thread,
  and an uncaught one kills the capture thread — the user goes silent with no
  warning, which is the complaint this project started from. The JNI bridge already
  refuses a short frame with `-1` without overrunning. Refuse and report; do not
  throw.
- **Pin "off is off" by identity, in task 6.** `NoopPreprocessor` is proven to run
  no code on the frame, but the observable that matters — that the factory returns
  it for `NONE` rather than a stage constructed with neutral parameters — lives in
  `CapturePreprocessorFactory`. Assert the *identity* of what the factory returns,
  not that the output frame is unchanged.

- **`MumlaService` becomes `exported="false"` (P, task 9 — decided).** It is
  currently `android:exported="true"` with no permission, and `onBind` returns the
  binder **unconditionally** — no permission check, no caller check. So any
  installed app that knows the component name, which is public in an open-source
  app on F-Droid, can bind it and hold `IHumlaService`: disconnect the session,
  and through it reach the microphone. That is the failure class this whole
  project has been closing, reachable from another application.
  Checked before deciding rather than assumed: the service declares **no
  intent-filter**, so nothing can find it by action; and all three internal
  binders — `MumlaActivity:401`, `ChannelSearchProvider:97`, `ServerConnectTask:64`
  — build `new Intent(context, MumlaService.class)`, an explicit component intent,
  which works unchanged when the service is not exported. Nothing in the tree
  documents or implies an external client.
  The residual risk is an undocumented third-party integration binding it today;
  that is unsupported, and the change is one attribute, trivially reversible if
  anyone reports it. `AndroidManifest.xml` belongs to Platform, and task 9 is the
  one that already opens the permission surface.

- **Two leftovers from the adapter work, now owned (P, task 8).**
  (a) `ChannelSearchProvider` walks the channel tree from a **binder thread** —
  `channelSearch()`/`userSearch()` recurse over `getSubchannels()`/`getUsers()`
  while the protocol thread writes, with no `catch` anywhere. Stream A's guarded
  model covers the race (those getters now return copies), so this is not a live
  crash — but the file was in **no** ownership table, and it carries the same
  defect the adapter work just removed: `:135` calls `getSubchannelUserCount()`
  **twice per suggestion row**. Not on the main thread, so it is cheaper, but it
  is the same shape.
  (b) `MumlaOverlay` and `ChannelListAdapter` **disagree about icon priority**. The
  channel list orders self-deafened → server-deafened → self-muted →
  server-muted; the overlay orders self-deafened → self-muted → server-deafened →
  server-muted. A user who is server-deafened and self-muted therefore shows a
  **different icon in the two lists**. Both chains are now pinned, so the
  divergence is documented rather than merely present — but nobody ever decided
  it. Decide it in task 8, which opens the overlay anyway.
- **The observer queue's ceiling rests on thread confinement, which task 6 is
  about to break (A, task 6 — contract).** `HumlaCallbacks.absoluteCeiling` drops
  the oldest event whatever its policy, with one exemption: the four
  connection-lifecycle events. The invariant is therefore not `size <= ceiling`
  but **`queuedEvents <= max(absoluteCeiling, number of lifecycle events
  enqueued)`**, and the second term is only small for one reason: all four are
  raised *on the delivery thread itself*. `HumlaConnection` posts every listener
  callback to its `mainHandler` (`deliverDisconnected`, `notifyListener`),
  `HumlaTCP` posts `onTLSHandshakeFailed` to its callback handler,
  `HumlaService.connect()` raises `onConnecting` on main, and `setReconnecting()`
  posts the retry to a main `Handler`. While that thread is stuck — the only state
  in which the queue grows at all — no lifecycle event can arrive to grow it.
  The argument first written into the code (*"their number is the connection's to
  choose rather than the server's"*) is **false** and must not be relied on:
  `onConnectionDisconnected` turns a `CONNECTION_ERROR` into
  `setReconnecting(true)`, which posts `connect()` after the auto-reconnect delay,
  and every cycle raises `onConnecting` and `onDisconnected` again — there is no
  attempt cap in the production path, since the session state machine that adds one
  is tasks 6, 9 and 12. **The contract for task 6:** it owns `HumlaConnection.kt`
  and is the first task that can give the connection a handler that is not
  `HumlaCallbacks`'s. The moment those are two threads, this exemption has no
  argument left and the queue has no bound; the same goes for tasks 9 and 12. Two
  things to do then, both cheap: re-derive the exemption or drop it, and re-cost
  `dropOldestUnlessLifecycle()`, whose scan over the exempt prefix is O(L) per
  raise under the lock the protocol thread shares with the audio thread — today
  unreachable for the confinement reason and for no other, and **not** covered by
  `findingTheOldestDroppableEventDoesNotScanTheQueue`, which fills with `onLogInfo`
  and stops that loop at element 0.
- **Correction to the adapter ruling above, twice corrected (P, task 6 review,
  binding).** The first correction named the right winner and the wrong runner-up.
  What an independent paired run on one machine measures — one machine, one
  best-of-N, a sample and not a constant:
  - **Coalescing is effectively the whole win.** 5 000-event sync: 2 174.4 ms →
    0.4 ms. Decomposed: coalescing alone ≈ 0.37 ms (99.98 % of the gain); the
    O(n·depth)→O(n) count fix alone leaves ≈ 5 000 × 243.7 µs = **1 218 ms** —
    44 % cheaper and still an ANR.
  - **The count fix halves a single rebuild** (374.7 µs → 243.7 µs) and takes the
    recursive count visits from **33 179 to 0** per rebuild, exactly.
  - **"`onBindViewHolder` called it twice per channel row, two full subtree walks
    per row" is false in all four corners.** Java `||` short-circuits, so a channel
    that *has* subchannels never reaches the call in `ChannelListAdapter.java:135-136`,
    and a leaf's "subtree walk" is one node; the second call sits behind
    `mShowChannelUserCount`, whose default is `false` (`Settings.kt:317`). Measured
    over all 4 997 visible rows of a 5 000-channel tree: **3 747 node visits with
    the setting at its default — 0.75 per row, one ninth of a single rebuild** —
    and 36 926 with it on, i.e. ≈ 1.1 rebuilds for scrolling the *entire* list,
    against the thousands of rebuilds per sync the old code did. Binding never
    dominated. Moving the counts onto the node takes it to zero, which is worth
    having and is not where the block was.
  - Minor: "the count visits are cheap — field reads, no allocation" is also not
    quite right. `Channel.getSubchannelUserCount()` (`Channel.java:188-194`)
    for-eaches `mSubchannels`, so one `Iterator` per visit. It does not move the
    measured 374.7 → 243.7.

- **Coalesce the adapter's own rebuilds (P, task 6).** With the observer queue
  bounded, the largest remaining main-thread cost is not in the model any more —
  it is `ChannelListAdapter.updateChannels()`, which is O(n·depth) and runs *in
  full* from every model observer event. Measured on a 5 000-channel tree: **637 µs
  per rebuild on a desktop JVM**, and one large sync still delivers 1 024 of them
  after the cap, i.e. **roughly three to six seconds of main-thread work on a
  phone**. The cap cut it fivefold and can cut it no further, because the events
  that survive are exactly the ones every observer answers with a full rebuild. The
  fix belongs in the adapter: one posted rebuild per frame instead of one per
  event. Inside the walk, `getSubchannelUserCount()` dominates — it is recomputed
  from scratch at every node of every walk, which is what turns an O(n) walk into
  O(n·depth); the file's own `FIXME: is it necessary to cache this?` is the answer.
  `ChannelListAdapter` was in no stream's ownership list, which is why this had
  nobody to go to; it is now Platform's, next to `ChannelListFragment`, and task 6
  is the task that already opens that neighbourhood.
  Two more adapter defects found at the same time, same owner: `ChannelAdapter.java:50-60`
  calls `getUsers()` **three times per bind**, and `getCount()` and
  `getItem(position)` read two *different* snapshots — a user leaving between them
  is an `IndexOutOfBoundsException`. That was equally racy before the guarded model
  and copy-on-read does not fix it; the adapter has to hold one snapshot.
- **Two model facts, both corrected after measurement (A, binding).** I wrote the
  first version of this entry from a task report and both halves were wrong. The
  measured truth:
  (a) **The observer queue is *not* bounded.** Trimming now runs only when the
  arriving event is itself droppable — which fixed a real starvation bug, but means
  the ceiling is `#undroppable + 1` and nothing bounds `#undroppable`. Twelve of
  the nineteen observer events are undroppable, and two of them are bulk traffic
  during a sync: a 5 000-user server produces **at least 10 000** of them
  (`onUserConnected` plus an `onLogInfo` per user). Measured consequence: the
  trim scan is no longer capped either — **2.1 ms inside one `dispatch()` while
  holding the lock** in the real sync ordering, and **100 µs per enqueue in the
  steady state against 0.2 µs before**, on a lock the protocol thread shares with
  the audio thread. What *is* bounded is the population of the three tree-shape
  events. A second, absolute ceiling is still owed, and until it exists the honest
  statement is: **bounded in droppable events, unbounded in total, and the newest
  tree-shape event is never dropped.** An observer must still treat a model event
  as "read this again", never as a delta it accumulates.
  (b) **A rejected parent is permanent, not "the same as one that has not arrived
  yet".** `ModelHandler` refuses a `ChannelState` whose parent is the channel
  itself or one of its descendants, or that would sit deeper than 256 below the
  root — and that part is right, cheap (measured: 3.1 ms across a whole
  5 000-frame sync even in the worst shape the guard permits, because the walk is
  upward and depth-capped, not a subtree walk) and correctly placed at the one
  bottleneck. But the channel is then parentless, `ChannelListAdapter` only ever
  walks downward from the root channels, and nothing in the app iterates the
  channel map — so **the channel and every user in it disappear from the list for
  good**, with a `Log.w` as the only trace. The server does not resend that frame.
  Ruling: attach a rejected channel to the **root** instead of leaving it
  parentless. The tree stays finite and acyclic, and the channel stays visible in
  the wrong place rather than invisibly absent. **Owner: A, task 6**, as a rider —
  it is a few lines in `ModelHandler.java`, no other stream owns that file, and no
  later brief goes near the frame boundary where the guard sits. **Status: the
  fallback was in fact already written in task 5's fix round (`39924e64`), 22
  commits before task 6 began — but its *inside* was unpinned**, every branch in it
  deletable with the suite green, which is the pinned-call-site case in §4.04.
  Task 6 pinned it and removed one branch that decided the same result on the same
  input as the closing check. The contract
  paragraph in the core ledger that reads *"the same state as a channel whose
  parent frame has not arrived yet"* is **withdrawn**: one heals on the next frame
  and the other never does, which is the whole point.

- **Denying `BLUETOOTH_CONNECT` must not take the headset away (P, task 7 review, decided).**
  The permission is requested because spec P3 says so and because the store listing has
  carried the Nearby-devices entry since task 2 — that justification stands. But the
  implementation went further than P3 asked: `shouldRouteToBluetooth = wish && hasPermission`
  **refuses to route** without it. Measured against the SDK's own annotation database
  (`platforms/android-36/data/annotations.zip`): of 26 annotated `AudioManager` members,
  exactly four carry a `RequiresPermission` and `startBluetoothSco()` is not among them;
  `BLUETOOTH_CONNECT` appears on 136 members, 130 of them under `android.bluetooth.*`, none
  in `android.media`. The same holds for `setCommunicationDevice`, which A8 migrates to.
  So a user on API 31+ who denies the dialog loses a headset that, by the platform's own
  documentation, would have worked — and before this task the menu item asked for nothing.
  **Ruling: keep asking, stop gating.** Route on the wish alone, and wrap the call so a
  `SecurityException` from an OEM that does enforce it is caught, reported once in the chat
  log and does not crash. That keeps the feature for the deny case, stays safe where the
  platform is stricter than its own annotations, and removes a behaviour regression nobody
  decided to make. "Absent from the annotation database" is not "never throws anywhere",
  which is exactly why the call is wrapped rather than trusted.

- **A PNG carrying an `eXIf` orientation stops being rotated on the send path (D, task 10,
  accepted).** The old path read `ExifInterface` and rotated by hand; the new one lets
  `ImageDecoder` rotate, and the PNG codec does not honour `eXIf`. So that one case
  regresses. Accepted, because the alternative is worse: rotating by hand on top of a
  decoder that may already have rotated needs **a second source of truth for orientation
  and no way to tell which one already acted**. The review sharpened this correctly — for
  the four axis-swapping orientations the decoder's action *is* detectable by comparing
  `info.size` against the container header, but it is **not** detectable for 180° or a
  flip, and a partial orientation fix is worse than none. Cameras emit JPEG/HEIF, the case
  is pinned by a test so a Skia change goes red rather than silent, and it is disclosed
  here rather than in a commit body. **Correction to how this was reported:** an
  over-long image message is **not** silently dropped — Murmur answers
  `PERM_DENIED_TYPE(TextTooLong)`, `ModelHandler` maps it to *"Denied: Text message too
  long."* and `MumlaActivity` shows a dialog. The defect was real; the stated symptom was
  not. A wrong-sounding dialog for a picture is still a defect, and it belongs to D task 11.

- **The Bluetooth wish has exactly one carrier, and it is the preference (P task 7 /
  A task 8, binding).** After P7 the wish lives in `pref_bluetooth_sco` on disk and
  survives a reconnect — which is the whole point, since the user's complaint was
  that it did not. A's task 8 introduces `ScoRouter.wanted` in memory, so two
  carriers exist. Ruling: **the preference is the truth, `ScoRouter.wanted` is
  derived state initialised from it at connect, and nothing in the UI reads the
  in-memory wish.** Two riders that fall out of it, both for A task 8: if
  `MumlaService` ever sets the wish through `EXTRAS_BLUETOOTH_WANTED`/`configureExtras`
  rather than `enableBluetoothSco()`, the connect-time hook must move with it — today
  it calls the public method, which exists in both worlds; and after A8
  `usingBluetoothSco()` means the in-memory wish while `isBluetoothScoActive()` means
  the state. **No app code reads `usingBluetoothSco()` any more** (verified: its only
  caller was the menu path P7 deleted), so whoever displays the wish reads the
  preference — otherwise the UI has two truths again, which is the defect class this
  whole project has been removing.

- **Never run a mutation sweep in a worktree another agent commits from (process, mine).**
  A mutation sweep *edits production files* — that is what it is. If a second agent is
  committing from the same worktree, a `git add -A` pulls a deliberately broken guard
  into a commit, and it looks green because the sweep restores the file a second later.
  This nearly happened: a reviewer's task-notification said "stopped with background
  work of its own still running", I read it as finished, and dispatched the fix round
  into the same worktree. The reviewer caught it afterwards and verified line by line
  that nothing of its sweep survived — the repaired trim intact, `||` not turned into
  `xor`, the warning map not swapped, its calibration marker and probe file gone.
  What actually prevented it was a rule written for an unrelated reason: **`git add`
  path-scoped, never `-A`**, introduced after an implementer tore a production change
  apart from its RED. Two rules now carry the weight:
  1. **A review runs in a detached worktree at the commit under review**, never in the
     stream's own worktree. This was adopted for wall-clock — two agents per stream —
     and turns out to be the safety property as well.
  2. **"Agent finished" means it delivered its report**, not that a notification fired.
     A notification that says background work is still running is not a completion, and
     an agent that stops twice without reporting is stuck, not done — look in its
     worktree (one command) before dispatching anything that writes there.

- **Measured: the Gradle knobs do not work, so the lever is fewer invocations (process).**
  Paired runs on one machine, `:libraries:humla:testDebugUnitTest`: `maxParallelForks`
  1 → 6 is **22/24 s against 20/25 s, i.e. nothing**; the **configuration cache is worse**,
  35–45 s against 22–24 s, because AGP pays more to serialize the model than the cache
  returns; a no-op invocation with everything up to date still costs **7 s**. So the test
  execution is not the bottleneck, the invocation is, and no setting fixes it.
  Two rules follow, and they are binding on every dispatch:
  1. **Never `--rerun-tasks` for a mutation run — use `--rerun` on the one test task.**
     `--rerun-tasks` re-runs the entire graph including the CMake native build for
     **three ABIs**, which is the most expensive thing in this project, to answer a
     question about one test class. Several reviewers did exactly this, 20–55 times
     per task.
  2. **The full gate runs once, at the end of the task.** `assembleFossDebug` plus lint
     is the proof that the app still builds; it is not an iteration step. Mutations run
     the single relevant test task and nothing else.
  What is *not* worth doing, measured: forks, the configuration cache, and narrowing the
  ABI list — the native artefacts are cached anyway unless the stream touches C++, which
  only stream B does.
  For wall clock the remaining lever is **more concurrent agents, not faster builds**.
  Structure that allows it: a review is read-only by mandate, so it runs in a **detached
  worktree at the commit under review**, which frees the stream's own worktree for the
  next implementer. Two agents per stream instead of one.

- **Measured: where the context actually goes, and what was done about it (process).**
  Across 135 subagent runs, 5.13 M tokens of tool output: **Bash 87.5 %, Read 11.6 %**.
  Inside Bash the split is **reading source with `sed`/`cat` 73.6 %**, grep 10 %, git
  9.5 %, and **Gradle only 6.5 %** — the build runs, which were assumed to dominate,
  are the smallest real line. Broken down by what is being read, as a share of *all*
  tool output: **Kotlin/Java source 28 %**, **the spec, ledgers, briefs and plans
  together 18 %**, C/C++ 3.5 %. Whole source files are read whole only 1.6 % of the
  time; the 1 378 source reads average ~260 lines, i.e. they are already contiguous
  reading, which is what finds a guard standing in front of a call.
  Two things follow. A symbol-level retrieval tool addresses ~31 % and would
  realistically save 10–15 %, against carrying its schemas in every agent context, a
  Kotlin language server, a `compile_commands.json` the Gradle/NDK build does not
  emit — and a new way to see a method body without what guards it, which is this
  project's own worst failure class. **Not adopted**; the honest test, if it is ever
  wanted, is one task with and one without, compared on tool tokens.
  The 18 % in documents is free to reclaim and was: each stream's ledger is now split
  into **`contracts.md`** (every contract, obligation to a later task, cross-stream
  report and open item with an owner — 14–18 KB, mandatory reading) and
  **`progress.md`** (measurements, fix rounds, reasoning — 35–75 KB and growing, read
  by `grep`/`sed` only, never whole). Dispatches name `contracts.md`. New obligations
  go in **both**: the requirement in the first, the measurement behind it in the second.
  **And four task pairs were merged**, because the cost that dominates is not what an
  agent loads but **how many agents there are**: three per task (implementer, reviewer,
  fix round) at 150–330 k tokens each, so merging two tasks turns six agents into three
  and saves ~30 % even after the merged task's agents work longer. Merged where the
  second task consumes the first and both need the same scaffolding: `A10+A12`,
  `B12+B13`, `D12+D13`, `P9+P10` — two of the absorbed briefs were 3 KB and 5 KB, absurd
  as standalone tasks with three agents each. **The binding rider**: a bigger diff does
  not get more mutations from the review, it gets the same ones spread over more code,
  so the input, effect and fake passes are run and reported **per half**.
  **Two limits held.** Tasks whose halves are each large or carry their own hardware
  seam were left alone (`B9`/`B10`/`B11`, `P8`, `D11`), and the integration task must
  stay last. And **reviewer and fix round are not merged**, tempting as it is — the
  reviewer has the deepest context of anyone, but the fix round has **refuted the
  reviewer** more than once: on chat task 9 the reviewer wrote off three null checks as
  "real and covered" and the fix round measured one of them surviving all 31 tests.
  Fresh eyes on the reviewer's findings are not a luxury, they are reproducibly the find.
  And **core task 9 was split**: its brief alone was 93.5 KB, which is not merely
  expensive but badly shaped — a faithful Kotlin conversion guarded by characterization
  tests and a behaviour change are two different review questions. **9a** is
  characterization plus conversion with no behaviour change (46 KB); **9b** is the
  wiring (52 KB), and there every characterization test that goes red is a behaviour
  change to justify or a defect. Core therefore has 13 tasks, not 12.

- **Never point a dispatch at a plan file — point it at the brief (process, mine).**
  The per-task brief is a **byte-identical extract** of that task's plan section, so
  naming the plan as well is pure redundancy with a 100 000-token downside: the core
  plan is 412 KB (~103 k tokens), the audio plan 374 KB, while a typical brief is
  16–25 KB. Measured across the agents run so far: **zero** full reads of any plan
  and **146** targeted range reads — the agents were doing the right thing on their
  own, which is luck, not instruction. So the dispatch says: *the brief is the task;
  if you need another task's section — a downstream contract, a fixture another task
  will reuse — read that range with `sed -n 'A,Bp'` and never open the whole file.*
  The same arithmetic applies to what is growing: the spec is 104 KB (~26 k) and is
  read in full by every agent, which is worth it because every round has produced a
  finding from it; the stream ledgers are 35–77 KB and climbing, and "read it whole"
  will stop being the right instruction for them before it stops being right for the
  spec.

- **A freeze list must be diffed against the task's own Modify list (process, mine).**
  P7's brief said *Modify: `ChannelListFragment.kt`* and my standing rule in the same
  dispatch said that file must stay at null diff. The implementer executed the task,
  flagged the contradiction, and mitigated it — the fragment work in two individually
  revertable commits, the new tests in a new file, and the five files that had just
  cost 44 mutations at a proven null diff. That was the right call and the rule was
  mine to get wrong: a freeze exists to protect files a *previous* round paid for,
  and when it names a file the current task must change, it is the freeze that is
  stale. Check the two lists against each other before dispatching.

- **Bound and coalesce the observer queue (A, task 5).** `HumlaCallbacks`'s queue
  is unbounded. Task 2 wrote that down as a known limit and named "task 6" as the
  owner of the cap, but the Stream A plan's task 6 is UDP recovery and does not
  own `util/HumlaCallbacks.kt` — after task 2, no task in the plan does. The limit
  stopped being theoretical with task 4, which is what lets the protocol thread
  outrun the main thread: measured, a 5 000-channel sync parks 5 000 lambdas in
  the queue while main is busy, each one retaining a `Channel`. Task 5 takes this
  on because it is the task that makes `Channel`/`User` reads cheap, so it is
  already inside the objects the queue retains. A cap needs a policy for what to
  drop or fold, and events that are pure state refreshes for one user or channel
  are the ones that coalesce.
- **One lock across both audio streams (B, task 4 — DISCHARGED).**
  *Re-addressed and closed.* This entry named tasks 5 and 6, which **cannot**
  discharge it: they own `SpeexPreprocessor.kt`, `RnnoisePreprocessor.kt` and
  `WebRtcApmPreprocessor.kt`, three separate files, and the only shape those two
  tasks can produce on their own is exactly the two-independent-adapters shape
  this constraint forbids. The owner of the seam is task 4, which built it:
  `SingleHandleStage` holds one lock and one handle field, every stage extends it
  and writes no locking at all. Fourth instance of this section's own opening
  rule, and the first one committed by this section. Original text follows.
  — **One lock across both audio streams (B, tasks 5–6).** The WebRTC APM has two
  audio threads: `processRender` on the playback thread and `processCapture` on
  the capture thread. `AudioHandler.java:220-225,467-482` serialises `encode()`
  and `destroy()` through `mEncoderLock`, which does **not** cover the playback
  path. The release-versus-in-flight-process window spans `GetArrayLength` *and*
  `GetShortArrayElements` (`jni_webrtc_apm.cpp:55-58`), i.e. an array copy that
  can take a GC pause. The adapters must take **one** lock covering both streams
  and `destroy()` — not two independent adapters.
- **Native handles are not interchangeable (B, tasks 5–6).** `HandleTable::get()`
  dereferences without validating; only `release()` checks membership. Passing a
  handle to the bridge that did not issue it is a segfault or silent nonsense
  (measured). Adapters must never mix the RNNoise and APM handles.
- **Reset the toggle input mode on disconnect (A — DISCHARGED in task 4).**
  `mInputOn` (`ToggleInputMode.java:52`) is never cleared; `mToggleInputMode` is
  created once in `HumlaService.onCreate` (`:269`) and lives as long as the
  service. With stream P's media-key toggle a user can turn transmission on with
  the screen off, lose the network, and the auto-reconnect resumes transmitting
  with no key press and no visible indication. It must be cleared **in code** in
  `HumlaService.onConnectionDisconnected`: an observer-based reset is a no-op
  there, because `mConnectionState` is set to DISCONNECTED before
  `mCallbacks.onDisconnected(e)` fires, and both `isConnected()` (`:1112`) and
  `HumlaSession()` (`:747`) read that field. A reset in `onConnected()` still
  leaves a window, because `AudioHandler.initialize()` starts the input thread
  from `onConnectionSynchronized` (`:378-382`), before `onConnected()` (`:392`).
- **Verify which key action the media-button path delivers before building on it
  (P, task 4).** `MediaKeyHandler` acts on `ACTION_UP`. `MediaSessionCompat`'s
  default callback discards everything that is not `ACTION_DOWN`, and Media3
  ignores `ACTION_UP` before the app sees it. If our path behaves the same, the
  button does nothing at all and no unit test of that layer can show it. Measure
  first (log in `onMediaButtonEvent`, `adb shell input keyevent 79` and `85`, plus
  a real Bluetooth headset — adb alone is not enough, it goes through the input
  dispatcher rather than the AVRCP stack). If only DOWN arrives, switch to DOWN +
  `repeatCount == 0` + no `FLAG_CANCELED`, and swallow the matching UP.
- **Apply the host policy per redirect hop (D, task 6).** `HttpImageFetcher` sets
  `instanceFollowRedirects = true` and follows same-scheme redirects to any host
  without re-entering the gate. A loopback/LAN block that sits only in the gate is
  bypassed by a single 302.
- **Catch zero-sized bounds before calling the decoder (D, task 6).**
  `BoundedBitmapDecoder` throws `IllegalArgumentException` on a non-positive
  bound, and a not-yet-measured view legitimately reports 0 px. Skip the load
  instead. Do **not** relax the `require`: it is the only thing between a negative
  bound and a non-terminating loop (measured — the sample size doubles to
  `Int.MIN_VALUE`, then 0, and `1f/0 = +Inf` keeps the condition true forever).
- **Prove the send path with `createdFromBitmap`, not with the output size
  (D, task 10).** `ChannelChatFragment.java:306` decodes full size, rotates into a
  second full copy, and only then resizes — up to ~96 MB peak for a 12 MP photo.
  Robolectric does not implement `inJustDecodeBounds`, so a heap delta measures
  the opposite of what it claims. `shadowOf(bitmap).createdFromBitmap` yields the
  source instance a scaled bitmap was made from, which makes the chain
  sample → rotate → fit checkable link by link.

## 5. Ordering and integration

1. F1–F8 sequentially on `modernization` (F1 first, F2 next, then F3, F4, F5, F6, F7, F8).
2. A, B, D, P in parallel worktrees branched from the post-F8 commit
   (`wt/core`, `wt/audio`, `wt/chat`, `wt/platform`).
3. Integration order: A → B → P → D, each rebased on the integration head,
   full build + tests + lint green after every merge.
4. Final whole-branch review, one fix wave, done.

## 6. Acceptance (end state)

- All four user-reported symptoms have a regression test that fails on the
  old code path and passes now (Bluetooth after reconnect, no main-thread join
  in disconnect, foreground kept across reconnect, VAD on preprocessed signal).
- `./gradlew assembleFossDebug assembleGoogDebug test lint` green in `nix develop`.
- No `javacpp`, `spongycastle`, `guava`, `AsyncTask`, `StrictMode.permitAll`,
  `ndk-build` left in the tree.
- `NOTICE.md` lists opus, speex, speexdsp, celt, rnnoise, webrtc-audio-processing,
  BouncyCastle, minidns, jsoup, netcipher with licenses.
