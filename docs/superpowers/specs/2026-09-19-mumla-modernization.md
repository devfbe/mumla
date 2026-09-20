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
| P Platform & controls | `app/MumlaActivity` (permissions, MediaSession wiring), `channel/ChannelListFragment` (Bluetooth menu), `channel/ChannelListAdapter` (rebuild coalescing, see 4.1), `channel/ChannelFragment` (talk button), `service/MumlaOverlay` (talk button, see 4.1), new `service/MumlaMediaSession`, non-audio keys in `Settings.kt` (additive only), `res/xml/settings_general.xml`, `AndroidManifest.xml`, battery-optimization dialog |

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
    (NS + AEC3 + AGC2 + high-pass, VAD from level). Composition rule: WebRTC APM
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

**Mutate a compound condition clause by clause.** `if (a && b && c)` is three
guards wearing one pair of brackets, and removing the whole condition kills a test
while removing `b` alone may not. A sweep that treats the `if` as one unit reports
a clean result over a passenger. Done properly on one file here: 8 of 8
sub-clauses each killed a test on their own, which is the statement worth making —
not "the condition is covered".

And the tool: do not run the suite once. **Mutate each guard on its own and
require exactly one test to go red.** Here that costs about eleven seconds a run.

Removing state beats adding a guard. Twice in the same round a guard was replaced
by deleting the state that made the error expressible: the connection flags were
given a single writing thread rather than a corrected comment, and the host field
stopped being cleared on teardown rather than being null-checked — `getByName`
resolves both `null` and `""` to loopback, so only never resetting it is safe.

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
- **A removed guard can hang the suite instead of failing it.** Deleting a
  `count < 0` check in a native bridge does not produce a red test: `-1` becomes a
  four-billion unsigned count and the library runs. A mutation sweep without a
  per-test timeout stalls on the first such mutation and produces nothing — one
  here burned twenty minutes before anyone noticed. Pass `--timeout` to `ctest`,
  and treat a sweep that produces no output as a result to investigate rather than
  a run to repeat.
- **A naive SARIF reader counts ten lint errors this project does not have.**
  `MissingQuantity` is demoted to `warning` in the module's own config, but the
  *rule default* in the SARIF stays `error`. A script that falls back to the rule
  default reports ten errors per app variant. Read the `level` on each result, not
  the rule. Two rounds have reported lint numbers taken this way; the numbers
  happened to be right because `abortOnError = true` and the build passed, which is
  the stronger signal to use in the first place.
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
- **One lock across both audio streams (B, tasks 5–6).** The WebRTC APM has two
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
- **Reset the toggle input mode on disconnect (A, at or before task 11).**
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
