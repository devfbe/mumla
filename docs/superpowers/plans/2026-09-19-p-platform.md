# Stream P — Platform & Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Mumla controllable from headset/Bluetooth buttons with the screen off, make the Bluetooth headset choice persistent, bring the runtime-permission flows (RECORD_AUDIO rationale, POST_NOTIFICATIONS, BLUETOOTH_CONNECT) and the battery-optimization exemption prompt up to date, and audit the manifest for `targetSdk 36`.

**Architecture:** All new logic lives in small Kotlin classes with a seam towards the Humla session so that it is testable on the JVM: `MediaKeyHandler` (pure key→action mapping) is driven by `MumlaMediaSession` (a thin `MediaSessionCompat` wrapper that activates itself from `HumlaObserver.onConnected`/`onDisconnected`); `BluetoothScoToggle`, `ConnectPermissionFlow` and `BatteryOptimizationPrompt` hold the decision logic that `ChannelListFragment`, `GeneralSettingsFragment` and `MumlaActivity` only wire to dialogs and launchers. `MumlaService` (stream A) gets five hooks of a few lines each (S1–S5, listed at the end); everything else is in files stream P owns. The three Java files this stream changes non-trivially (`MumlaActivity`, `ChannelListFragment`, `GeneralSettingsFragment`) are converted to Kotlin in their own commits before the behavior change. All `MumlaMediaSession` state is main-thread-confined.

**Tech Stack:** Kotlin, `androidx.media:media:1.8.0` (`MediaSessionCompat`, `PlaybackStateCompat`), `androidx.activity` result contracts (`RequestPermission`), `androidx.core` (`ContextCompat`, `IntentCompat`), Material dialogs; tests: JUnit 4, Robolectric (`ShadowPowerManager`, `ShadowContextWrapper.grantPermissions`, `ShadowActivity.getLastRequestedPermission`, `Robolectric.buildActivity` to host a preference fragment, `ShadowMediaSession`), MockK (only for the `IHumlaSession` boundary), Google Truth.

**Spec:** `/home/becker/git/mumla/docs/superpowers/specs/2026-09-19-mumla-modernization.md` — §3.5 (P1..P5), §3 ownership table, §4 interfaces, §2 global constraints.

## Global Constraints

(Copied verbatim from spec §2. Every task's requirements implicitly include this section.)

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

---

## Starting state this plan assumes

This plan is executed in the worktree `wt/platform`, branched from the post-F8 commit (spec §5). Concretely it relies on:

- Kotlin plugin applied to `:app`; version catalog at `gradle/libs.versions.toml`; JUnit 4, Robolectric, MockK, Truth, coroutines-test on `testImplementation` of `:app`; `testOptions.unitTests.isIncludeAndroidResources = true` (F3). Robolectric's SDK level is pinned by Foundation in `app/src/test/resources/robolectric.properties`; tests below add an explicit `@Config(sdk = [...])` only where the behavior depends on the API level.
- `app/src/main/java/se/lublin/mumla/Settings.kt` exists with the same public API as today's `Settings.java` (`Settings.getInstance(context)`, `PREF_*` constants in the companion object, Java-style accessor functions such as `isPushToTalkToggle()`). Stream P only **adds** keys and accessors (spec §3).
- `MumlaService.java` (stream A) is still Java; this plan adds hooks of at most ~10 lines each and lists every one of them (see "Cross-stream touches" at the end).
- Paths keep today's layout: the app module is `app/`, Humla is `libraries/humla/`.

Verification of the code this plan was written against (all paths exist on branch `modernization` at commit `6f4f899`):

| File | What the plan uses |
|---|---|
| `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:131-135, 558-667` | permission constants, `connectToServer`, `connectToServerWithPerm`, `onRequestPermissionsResult` |
| `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:170-200` | `HumlaObserver.onConnected` (battery prompt hook point) |
| `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:463-479` | `onKeyDown`/`onKeyUp` PTT key handling (kept) |
| `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.java:144-150, 202-212, 233-251, 305-343` | Bluetooth SCO receiver, register/unregister, `onPrepareOptionsMenu` checked state, `menu_bluetooth` toggle |
| `app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java:211, 377` | `new ChannelListFragment()` — the fragment is constructed directly, so the Kotlin class keeps name and package |
| `app/src/main/java/se/lublin/mumla/channel/ChatTargetProvider.java:24-39` | `ChatTargetProvider` is an interface; its nested `class ChatTarget` (constructors `(IChannel)`, `(IUser)`) is therefore implicitly static |
| `app/src/main/java/se/lublin/mumla/preference/GeneralSettingsFragment.java:12-21` | `onCreatePreferences` (settings screen that hosts the new Bluetooth checkbox); `MumlaPreferenceFragment.java:14-66` base class |
| `app/src/main/java/se/lublin/mumla/preference/SettingsActivity.java:12-62` | hosts preference fragments in `R.id.settings_container`; used to host `GeneralSettingsFragment` under Robolectric |
| `app/src/main/res/xml/preference_headers.xml:6` | `app:fragment="se.lublin.mumla.preference.GeneralSettingsFragment"` — class name must survive the conversion |
| `app/src/main/java/se/lublin/mumla/service/MumlaService.java:119-160, 291-319, 326-350, 352-389, 414-496, 606-634` | observer, `onCreate`, `onDestroy`, `onConnectionSynchronized`, `onSharedPreferenceChanged`, `onTalkKeyDown/Up` |
| `app/src/main/java/se/lublin/mumla/service/IMumlaService.java:10-30` | `onTalkKeyDown()`, `onTalkKeyUp()` |
| `app/src/main/java/se/lublin/mumla/Settings.java:42-53, 168-176` | key naming convention, `getInstance` |
| `app/src/main/AndroidManifest.xml:25-38, 45-53, 85-89, 119-163` | permissions, `<application>` attributes, `MumlaService` declaration (`exported="true"`, `foregroundServiceType="microphone"`), certificate activities |
| `app/build.gradle:147-161` | `dependencies {}` — today literal coordinates (`implementation 'androidx.preference:preference:1.2.1'`), no `gradle/libs.versions.toml` exists yet at commit `6f4f899` |
| `app/src/main/res/xml/settings_general.xml` | general settings screen |
| `app/src/main/res/menu/fragment_channel_list.xml` | `menu_bluetooth` checkable item |
| `libraries/humla/src/main/java/se/lublin/humla/IHumlaSession.java:103-115, 153` | `getTransmitMode()`, `usingBluetoothSco()`, `enableBluetoothSco()`, `disableBluetoothSco()`, `isTalking()`, `setTalkingState()`, `setSelfMuteDeafState()` |
| `libraries/humla/src/main/java/se/lublin/humla/IHumlaService.java:38-87` | `registerObserver`, `unregisterObserver`, `isConnected`, `HumlaSession()` |
| `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java:905-935` | `enableBluetoothSco` / `disableBluetoothSco` / `usingBluetoothSco` semantics |
| `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java:407-436` | `onConnectionDisconnected` calls `mBluetoothReceiver.stopBluetoothSco()` (line 433) on *every* disconnect, including the ones auto-reconnect recovers from — the reason hook S4 re-enables SCO on synchronization |
| `libraries/humla/src/main/java/se/lublin/humla/util/HumlaCallbacks.java:33-53` | observers are invoked on the caller's thread, with no main-looper posting |
| `libraries/humla/src/main/java/se/lublin/humla/Constants.java:28-30` | `TRANSMIT_VOICE_ACTIVITY = 0`, `TRANSMIT_PUSH_TO_TALK = 1`, `TRANSMIT_CONTINUOUS = 2` |
| `libraries/humla/src/main/java/se/lublin/humla/util/HumlaObserver.java` | no-op base class for observers |
| `app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java:186-191` | `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` |

Upstream facts checked while writing this plan:

- `https://developer.android.com/jetpack/androidx/releases/media` — latest stable `androidx.media:media` is **1.8.0** (2026-05-06). The 1.8.0 notes deprecate `androidx.media` in favor of `androidx.media3`; the spec (§3.5 P1) explicitly asks for `MediaSessionCompat` from `androidx.media`, so this plan uses 1.8.0 and records the media3 migration as an open question.
- `https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat.Callback` — `public boolean onMediaButtonEvent(Intent mediaButtonEvent)`; the `KeyEvent` is in `Intent.EXTRA_KEY_EVENT`. The default implementation only handles double-tap on API < 27, so overriding it and returning `true` for our keys is the whole contract.
- `https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowMediaSession.java` — `ShadowMediaSession` shadows only the `MediaSession(Context, String)` constructor and *delegates to the real constructor* (`reflector(...).__constructor__(context, tag)`), then records the token's binder → package; nothing else (`setActive`, `setPlaybackState`, `getSessionToken`, `release`) is shadowed.
- `https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowServiceManager.java` — `Context.MEDIA_SESSION_SERVICE` is registered as `ISessionManager` with `BinderType.DEEP_PROXY` (`ReflectionHelpers.createDeepProxy`): `createSession()` returns a proxy `ISession` whose methods are no-ops and whose `getSessionToken()` returns a non-null proxy, so the framework token is non-null, `MediaSession.isActive()` reflects the local `mActive` field, and `MediaSessionCompat`'s in-process extra binder (`MediaSessionImplApi21.mExtraSession`) serves `getPlaybackState()` without touching the framework binder. This is why Task 4 asserts through `MumlaMediaSession.playbackState` (= `session.controller.playbackState`, the compat controller built from the same token) and never through a framework `MediaController` alone.
- `https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowMediaSessionManager.java` — `createSession` is not shadowed (only `getActiveSessions`/listeners), confirming the deep-proxy path above is the one taken.
- `https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowPowerManager.html` — `setIgnoringBatteryOptimizations(String packageName, boolean value)` and `isIgnoringBatteryOptimizations(String)` exist.
- `https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowActivity.html` — `ShadowContextWrapper.grantPermissions(String...)` / `denyPermissions(String...)`, `ShadowActivity.getLastRequestedPermission()`, `getNextStartedActivity()`.

## File structure

| File | Responsibility | Task |
|---|---|---|
| `app/src/main/java/se/lublin/mumla/Settings.kt` (add only) | keys + accessors: `pref_bluetooth_sco`, `media_button_action`, `battery_optimization_asked` | 1 |
| `app/src/main/java/se/lublin/mumla/MediaButtonAction.kt` (new) | enum of what the headset button does (`NONE`, `AUTO`, `MUTE`) | 1 |
| `app/src/main/AndroidManifest.xml` | permissions / exported / service types audit | 2 |
| `app/src/main/java/se/lublin/mumla/service/MediaKeyTarget.kt` (new) | seam: what a media key may do to the session | 3 |
| `app/src/main/java/se/lublin/mumla/service/HumlaMediaKeyTarget.kt` (new) | `MediaKeyTarget` over `IHumlaService` | 3 |
| `app/src/main/java/se/lublin/mumla/service/MediaKeyHandler.kt` (new) | `KeyEvent` → action, pure logic | 3 |
| `app/src/main/java/se/lublin/mumla/service/MumlaMediaSession.kt` (new) | `MediaSessionCompat` lifecycle bound to connection state and to `media_button_action != none`; main-thread-confined | 4 |
| `app/src/main/res/xml/settings_general.xml`, `res/values/preference.xml`, `res/values/preference_notranslate.xml` | "Controls" category: headset button action, Bluetooth headset | 5, 7 |
| `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt` | converted; Bluetooth menu toggles the preference and requests `BLUETOOTH_CONNECT` | 6, 7 |
| `app/src/main/java/se/lublin/mumla/channel/BluetoothScoToggle.kt` (new) | decision logic for the toggle + permission; `shouldRouteToBluetooth()` used by the service hooks | 7 |
| `app/src/main/java/se/lublin/mumla/preference/GeneralSettingsFragment.kt` | converted; the `pref_bluetooth_sco` checkbox requests `BLUETOOTH_CONNECT` before it persists `true` | 7 |
| `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt` | converted; wires permission flow, battery prompt | 8, 9, 10 |
| `app/src/main/java/se/lublin/mumla/app/ConnectPermissionFlow.kt` (new) | which permission to ask next before connecting | 9 |
| `app/src/main/java/se/lublin/mumla/app/BatteryOptimizationPrompt.kt` (new) | one-time battery-optimization offer | 10 |
| `app/src/main/res/values/strings.xml` | new user-facing strings | 5, 7, 9, 10 |

Tests live under `app/src/test/java/se/lublin/mumla/...` mirroring the package of the class under test.

Commands used throughout (run from the repo root):

```bash
# one test class
cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.SomeTest'
# the green gate required at every commit
cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest
```

---

### Task 1: Settings keys for Bluetooth, headset button and battery prompt

**Files:**
- Modify: `app/src/main/java/se/lublin/mumla/Settings.kt` (additive: 3 keys, 3 defaults, 6 accessors)
- Create: `app/src/main/java/se/lublin/mumla/MediaButtonAction.kt`
- Test: `app/src/test/java/se/lublin/mumla/SettingsPlatformKeysTest.kt`

**Interfaces:**
- Consumes: `Settings.getInstance(context)` and the `PREF_*`/`DEFAULT_*` naming convention from `Settings.kt` (F3).
- Produces (used by Tasks 3, 4, 7, 10 and by the `MumlaService` hooks):
  - `Settings.PREF_BLUETOOTH_SCO: String = "pref_bluetooth_sco"`, `fun isBluetoothScoEnabled(): Boolean`, `fun setBluetoothScoEnabled(enabled: Boolean)`
  - `Settings.PREF_MEDIA_BUTTON_ACTION: String = "media_button_action"`, `fun getMediaButtonAction(): MediaButtonAction`
  - `Settings.PREF_BATTERY_OPTIMIZATION_ASKED: String = "battery_optimization_asked"`, `fun isBatteryOptimizationAsked(): Boolean`, `fun setBatteryOptimizationAsked(asked: Boolean)`
  - `enum class MediaButtonAction(val prefValue: String) { NONE("none"), AUTO("auto"), MUTE("mute") }` with `companion fun fromPrefValue(value: String?): MediaButtonAction`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/se/lublin/mumla/SettingsPlatformKeysTest.kt`:

```kotlin
package se.lublin.mumla

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsPlatformKeysTest {
    private lateinit var context: Context
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        settings = Settings.getInstance(context)
    }

    @Test
    fun bluetoothScoIsOffByDefault() {
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun bluetoothScoIsPersistedUnderTheSpecKey() {
        settings.setBluetoothScoEnabled(true)

        val raw = PreferenceManager.getDefaultSharedPreferences(context)
        assertThat(raw.getBoolean("pref_bluetooth_sco", false)).isTrue()
        assertThat(Settings.getInstance(context).isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun mediaButtonActionDefaultsToAuto() {
        assertThat(settings.getMediaButtonAction()).isEqualTo(MediaButtonAction.AUTO)
    }

    @Test
    fun mediaButtonActionReadsStoredValue() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("media_button_action", "mute").commit()

        assertThat(settings.getMediaButtonAction()).isEqualTo(MediaButtonAction.MUTE)
    }

    @Test
    fun mediaButtonActionFallsBackToAutoForUnknownValue() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("media_button_action", "bogus").commit()

        assertThat(settings.getMediaButtonAction()).isEqualTo(MediaButtonAction.AUTO)
    }

    @Test
    fun batteryOptimizationAskedDefaultsToFalseAndPersists() {
        assertThat(settings.isBatteryOptimizationAsked()).isFalse()

        settings.setBatteryOptimizationAsked(true)

        assertThat(Settings.getInstance(context).isBatteryOptimizationAsked()).isTrue()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.SettingsPlatformKeysTest'`
Expected: compilation FAILS with `Unresolved reference: isBluetoothScoEnabled` (and `MediaButtonAction`, `getMediaButtonAction`, `isBatteryOptimizationAsked`).

- [ ] **Step 3: Add the enum and the keys**

Create `app/src/main/java/se/lublin/mumla/MediaButtonAction.kt`:

```kotlin
package se.lublin.mumla

/**
 * What a headset / Bluetooth media button (play-pause, headset hook) does while connected.
 * The [prefValue] strings are the entry values of `@array/mediaButtonActionValues`.
 */
enum class MediaButtonAction(val prefValue: String) {
    /** Media buttons are ignored. */
    NONE("none"),

    /** Toggle push-to-talk in PTT mode, toggle self-mute in every other transmit mode. */
    AUTO("auto"),

    /** Always toggle self-mute. */
    MUTE("mute");

    companion object {
        @JvmStatic
        fun fromPrefValue(value: String?): MediaButtonAction =
            entries.firstOrNull { it.prefValue == value } ?: AUTO
    }
}
```

In `app/src/main/java/se/lublin/mumla/Settings.kt`, add to the `companion object` (next to the other `PREF_*` constants):

```kotlin
        /** Route audio through a Bluetooth headset (SCO) whenever connected. Spec P2. */
        const val PREF_BLUETOOTH_SCO = "pref_bluetooth_sco"
        const val DEFAULT_BLUETOOTH_SCO = false

        /** Headset / AVRCP media button behavior, one of [MediaButtonAction.prefValue]. Spec P1. */
        const val PREF_MEDIA_BUTTON_ACTION = "media_button_action"
        const val DEFAULT_MEDIA_BUTTON_ACTION = "auto"

        /** True once the battery-optimization exemption has been offered. Spec P4. */
        const val PREF_BATTERY_OPTIMIZATION_ASKED = "battery_optimization_asked"
        const val DEFAULT_BATTERY_OPTIMIZATION_ASKED = false
```

and add to the class body (next to the other accessors; `preferences` is the `SharedPreferences` field F3 kept):

```kotlin
    fun isBluetoothScoEnabled(): Boolean =
        preferences.getBoolean(PREF_BLUETOOTH_SCO, DEFAULT_BLUETOOTH_SCO)

    fun setBluetoothScoEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_BLUETOOTH_SCO, enabled).apply()
    }

    fun getMediaButtonAction(): MediaButtonAction =
        MediaButtonAction.fromPrefValue(
            preferences.getString(PREF_MEDIA_BUTTON_ACTION, DEFAULT_MEDIA_BUTTON_ACTION)
        )

    fun isBatteryOptimizationAsked(): Boolean =
        preferences.getBoolean(PREF_BATTERY_OPTIMIZATION_ASKED, DEFAULT_BATTERY_OPTIMIZATION_ASKED)

    fun setBatteryOptimizationAsked(asked: Boolean) {
        preferences.edit().putBoolean(PREF_BATTERY_OPTIMIZATION_ASKED, asked).apply()
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.SettingsPlatformKeysTest'`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Run the green gate and commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/Settings.kt app/src/main/java/se/lublin/mumla/MediaButtonAction.kt app/src/test/java/se/lublin/mumla/SettingsPlatformKeysTest.kt
git commit -m "feat: add bluetooth, headset button and battery prompt settings"
```

---

### Task 2: Manifest audit (P5)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:25-38` (permissions), `:45-53` (application attributes), `:85-89` (`MumlaService`), `:119-163` (certificate activities) — line numbers as of commit `6f4f899`
- Test: `app/src/test/java/se/lublin/mumla/ManifestAuditTest.kt`

**Interfaces:**
- Consumes: the merged manifest as Robolectric exposes it through `PackageManager` (works because F3 enabled `isIncludeAndroidResources`).
- Produces: `MumlaService` declared with `foregroundServiceType="microphone|mediaPlayback"` and `exported="false"`; `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requested; legacy `BLUETOOTH`, `WRITE_EXTERNAL_STORAGE` (maxSdk 29), `BROADCAST_STICKY`, `BROADCAST_CLOSE_SYSTEM_DIALOGS`, `requestLegacyExternalStorage` gone.

Audit findings the test pins down (each line is a manifest fact today and what changes):

| Today | Why it changes | New |
|---|---|---|
| `BLUETOOTH` (line 36) | legacy permission, meaningless on API ≥ 31; SCO / communication-device selection needs `BLUETOOTH_CONNECT` | `BLUETOOTH_CONNECT` |
| `WRITE_EXTERNAL_STORAGE maxSdkVersion=29` (29) | below `minSdk 31` | removed |
| `READ_EXTERNAL_STORAGE maxSdkVersion=32` (30) | still used on API 31–32 by `ChannelChatFragment` image picking (stream D) | kept |
| `BROADCAST_STICKY` (37) | no `sendStickyBroadcast` anywhere in `app/` or `libraries/humla/` | removed |
| `BROADCAST_CLOSE_SYSTEM_DIALOGS` (33) | only used by `MumlaService.onOverlayToggled` under `SDK_INT < S`, dead at `minSdk 31` | removed (stream A deletes the dead branch when it touches the file) |
| `FOREGROUND_SERVICE_MICROPHONE android:minSdkVersion="34"` (26) | `android:minSdkVersion` is not a valid `<uses-permission>` attribute; the permission is harmless below 34 | attribute dropped |
| — | `mediaPlayback` service type requires it on API ≥ 34 | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` added |
| — | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Task 10) requires it | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` added |
| `android:requestLegacyExternalStorage="true"` (51) + comment (53) | comment says "if Android < 30" | removed |
| `MumlaService exported="true"`, `foregroundServiceType="microphone"` (85–89) | only started with explicit intents from inside the app (`ServerConnectTask`, `MumlaActivity.bindService`) | `exported="false"`, `foregroundServiceType="microphone|mediaPlayback"` |
| `Certificate*Activity`, `ServerCertificateClearActivity exported="true"` (119–163) | launched only via `<intent android:action=...>` from `res/xml/settings_authentication.xml` inside the app; an app may always resolve implicit intents to its own non-exported components | `exported="false"` |
| `MumlaActivity exported="true"` | launcher + `mumble://` VIEW + SEARCH must stay reachable | kept |
| `SettingsActivity exported="false"` | already correct | kept |
| `SYSTEM_ALERT_WINDOW`, `WAKE_LOCK`, `VIBRATE`, `MODIFY_AUDIO_SETTINGS`, `RECORD_AUDIO`, `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` | in use (`MumlaOverlay`/`MumlaHotCorner`, wake locks, reconnect notification vibrate, SCO, capture, network, notifications, foreground) | kept |

Note: the library manifest `libraries/humla/src/main/AndroidManifest.xml` declares `se.lublin.humla.HumlaService` with `exported="true"` and an intent-filter; that file is not owned by stream P (see "Cross-stream touches").

About the test below: the positive assertions (`BLUETOOTH_CONNECT`, both foreground-service types, the battery permission, `exported=false`) are the behavioral contract — a missing one breaks SCO on API 31+, media-button delivery on API 34+, or the battery dialog. The `doesNotContain(...)` assertions in `requestsBluetoothConnectInsteadOfLegacyBluetooth` and `obsoletePermissionsAreGone` are **deliberate audit guards**: they can only fail on a decision (someone re-adding a permission the audit removed), not on a bug, and are kept on purpose so the audit outcome is pinned in the test suite rather than in a review comment. A reviewer should not flag them as change detectors; the comment in the test says so. `WRITE_EXTERNAL_STORAGE` gets no assertion: its `android:maxSdkVersion="29"` makes the framework `PackageParser` drop it at `@Config(sdk = [35])` on the old manifest already, so an assertion could never fail.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/se/lublin/mumla/ManifestAuditTest.kt`:

```kotlin
package se.lublin.mumla

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.mumla.preference.CertificateExportActivity
import se.lublin.mumla.preference.CertificateGenerateActivity
import se.lublin.mumla.preference.CertificateImportActivity
import se.lublin.mumla.preference.CertificateSelectActivity
import se.lublin.mumla.preference.ServerCertificateClearActivity
import se.lublin.mumla.service.MumlaService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private fun requestedPermissions(): List<String> =
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()

    @Test
    fun requestsBluetoothConnectInsteadOfLegacyBluetooth() {
        val permissions = requestedPermissions()

        assertThat(permissions).contains(Manifest.permission.BLUETOOTH_CONNECT)
        // Audit guard (deliberate change detector): the legacy permission was removed in the
        // targetSdk 36 audit and must not come back with a merge.
        assertThat(permissions).doesNotContain(Manifest.permission.BLUETOOTH)
    }

    @Test
    fun requestsForegroundServiceTypesAndBatteryExemption() {
        val permissions = requestedPermissions()

        assertThat(permissions).contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        assertThat(permissions).contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK)
        assertThat(permissions).contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    /**
     * Audit guard (deliberate change detector): these two permissions were removed because
     * nothing in the tree uses them at minSdk 31; the test pins that audit decision.
     * WRITE_EXTERNAL_STORAGE (maxSdkVersion=29) is not asserted: the framework parser already
     * drops it at sdk 35, so an assertion could never fail.
     */
    @Test
    fun obsoletePermissionsAreGone() {
        val permissions = requestedPermissions()

        assertThat(permissions).doesNotContain(Manifest.permission.BROADCAST_STICKY)
        assertThat(permissions).doesNotContain("android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS")
    }

    @Test
    fun mumlaServiceIsNotExportedAndDeclaresMicrophoneAndMediaPlayback() {
        val info = pm.getServiceInfo(ComponentName(context, MumlaService::class.java), 0)

        // The microphone bit is declared today already; it comes first so that a failure of
        // this test on the old manifest proves Robolectric populates foregroundServiceType.
        assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        assertThat(info.exported).isFalse()
    }

    @Test
    fun certificateActivitiesAreNotExported() {
        val activities = listOf(
            CertificateSelectActivity::class.java,
            CertificateImportActivity::class.java,
            CertificateExportActivity::class.java,
            CertificateGenerateActivity::class.java,
            ServerCertificateClearActivity::class.java,
        )

        for (activity in activities) {
            val info = pm.getActivityInfo(ComponentName(context, activity), 0)
            assertWithMessage(activity.simpleName).that(info.exported).isFalse()
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.ManifestAuditTest'`
Expected: 5 tests, all FAIL, each on exactly the assertion named here (read the report under `app/build/reports/tests/testFossDebugUnitTest/`):

| Test | Fails on (old manifest) |
|---|---|
| `requestsBluetoothConnectInsteadOfLegacyBluetooth` | first assertion: the list lacks `android.permission.BLUETOOTH_CONNECT` |
| `requestsForegroundServiceTypesAndBatteryExemption` | second assertion: lacks `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (the first, `FOREGROUND_SERVICE_MICROPHONE`, passes — the parser ignores the invalid `android:minSdkVersion` attribute) |
| `obsoletePermissionsAreGone` | first assertion: the list contains `BROADCAST_STICKY` |
| `mumlaServiceIsNotExportedAndDeclaresMicrophoneAndMediaPlayback` | **second** assertion (media-playback bit missing). The first assertion must pass: today's manifest already declares `foregroundServiceType="microphone"` (line 89), so a pass there proves Robolectric's manifest parsing populates `ServiceInfo.foregroundServiceType`. If this test fails on the *first* assertion instead, stop: Robolectric is not populating the field, and the two bit assertions must be dropped from the test (keep `exported`) and the service type verified by `:app:lintFossDebug`'s `ForegroundServiceType` check in Step 5 instead. |
| `certificateActivitiesAreNotExported` | `CertificateSelectActivity` (`exported` is true) |

- [ ] **Step 3: Edit the manifest**

Replace the `<uses-permission>` block (`app/src/main/AndroidManifest.xml` lines 25–38, from `FOREGROUND_SERVICE` through `POST_NOTIFICATIONS`) with:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

In the `<application>` element remove the line `android:requestLegacyExternalStorage="true"` and the comment below it (`<!-- requestLegacyExternalStorage still true: ... -->`).

Replace the `MumlaService` declaration with:

```xml
        <service
            android:name=".service.MumlaService"
            android:exported="false"
            android:enabled="true"
            android:foregroundServiceType="microphone|mediaPlayback" />
```

For each of `.preference.CertificateSelectActivity`, `.preference.CertificateImportActivity`, `.preference.CertificateExportActivity`, `.preference.CertificateGenerateActivity`, `.preference.ServerCertificateClearActivity` change `android:exported="true"` to `android:exported="false"` (keep their `<intent-filter>` blocks unchanged; `settings_authentication.xml` launches them with in-app implicit intents, which resolve to non-exported components of the same package).

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.ManifestAuditTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Run lint on the manifest change**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:lintFossDebug`
Expected: `BUILD SUCCESSFUL` (no `ExportedService`/`MissingPermission` findings). If lint reports `ForegroundServiceType` because `MumlaConnectionNotification.startForeground` passes only `FOREGROUND_SERVICE_TYPE_MICROPHONE`, that is allowed (a subset of the declared types) — do not change stream A's file here; the recommended one-line change is listed under "Cross-stream touches".

- [ ] **Step 6: Run the green gate and commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/AndroidManifest.xml app/src/test/java/se/lublin/mumla/ManifestAuditTest.kt
git commit -m "fix: audit manifest permissions, exported flags and service types"
```

---

### Task 3: Media key handler (P1 logic)

**Files:**
- Create: `app/src/main/java/se/lublin/mumla/service/MediaKeyTarget.kt`
- Create: `app/src/main/java/se/lublin/mumla/service/HumlaMediaKeyTarget.kt`
- Create: `app/src/main/java/se/lublin/mumla/service/MediaKeyHandler.kt`
- Test: `app/src/test/java/se/lublin/mumla/service/MediaKeyHandlerTest.kt`
- Test: `app/src/test/java/se/lublin/mumla/service/HumlaMediaKeyTargetTest.kt`

**Interfaces:**
- Consumes: `Settings.getMediaButtonAction(): MediaButtonAction` (Task 1); `IHumlaService.isConnected()`, `IHumlaService.HumlaSession()`, `IHumlaSession.getTransmitMode()`, `isTalking()`, `setTalkingState(Boolean)`, `getSessionUser()`, `setSelfMuteDeafState(Boolean, Boolean)`; `Constants.TRANSMIT_PUSH_TO_TALK`.
- Produces:
  - `interface MediaKeyTarget { val isConnected: Boolean; val transmitMode: Int; val isTalking: Boolean; fun setTalking(talking: Boolean); fun toggleSelfMute() }`
  - `class HumlaMediaKeyTarget(service: IHumlaService) : MediaKeyTarget`
  - `class MediaKeyHandler(settings: Settings, target: MediaKeyTarget) { fun onKeyEvent(event: KeyEvent): Boolean }` — returns `true` when the key is one it owns and the session is connected and the action is not `NONE`; every `ACTION_DOWN` (including repeats) and every `ACTION_UP` of a handled key is then consumed. The action fires on an `ACTION_UP` whose `repeatCount == 0` and whose `FLAG_CANCELED` bit is clear; a repeated UP or a canceled UP is consumed without acting.

Design notes:
- Handled keys: `KEYCODE_HEADSETHOOK` (wired headsets), `KEYCODE_MEDIA_PLAY_PAUSE` (most Bluetooth headsets), `KEYCODE_MEDIA_PLAY` and `KEYCODE_MEDIA_PAUSE` (AVRCP headsets that track their own play state send these separately). Because `MumlaMediaSession` (Task 4) always reports `STATE_PLAYING`, an AVRCP headset keeps sending PAUSE, and each press toggles — consistent behavior.
- `AUTO`: in `TRANSMIT_PUSH_TO_TALK` toggle the talking state (a one-button headset cannot be held while the screen is off, so PTT via media key is always sticky), otherwise toggle self-mute. `MUTE`: always toggle self-mute. `NONE`: never act and never consume. Note that returning `false` from a `MediaSessionCompat.Callback` does **not** hand the key to another app — it only falls through to the default callback of *our* session — so `NONE` is really implemented in Task 4 by never holding an active session while `NONE` is selected; the handler's `NONE` branch is a defensive no-op for the window between a preference change and the session release.
- Long press: when the system consumes a long press of `KEYCODE_HEADSETHOOK` (voice assistant), the following `ACTION_UP` carries `KeyEvent.FLAG_CANCELED`. The handler consumes such an UP without toggling, so launching the assistant never also toggles PTT/mute. Repeated `ACTION_UP` events do not occur in practice (only DOWN repeats), but the `repeatCount == 0` guard makes the contract explicit and is tested.
- Mute toggle mirrors the action-bar mute button in `ChannelListFragment.onOptionsItemSelected` (`muted = !isSelfMuted; deafened = isSelfDeafened && muted`).

- [ ] **Step 1: Write the failing handler test**

Create `app/src/test/java/se/lublin/mumla/service/MediaKeyHandlerTest.kt`:

```kotlin
package se.lublin.mumla.service

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.Constants
import se.lublin.mumla.Settings

@RunWith(RobolectricTestRunner::class)
class MediaKeyHandlerTest {
    private class FakeTarget(
        override var isConnected: Boolean = true,
        override var transmitMode: Int = Constants.TRANSMIT_PUSH_TO_TALK,
    ) : MediaKeyTarget {
        override var isTalking: Boolean = false
        var muteToggles = 0

        override fun setTalking(talking: Boolean) {
            isTalking = talking
        }

        override fun toggleSelfMute() {
            muteToggles++
        }
    }

    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var target: FakeTarget
    private lateinit var handler: MediaKeyHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        settings = Settings.getInstance(context)
        target = FakeTarget()
        handler = MediaKeyHandler(settings, target)
    }

    private fun setAction(prefValue: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(Settings.PREF_MEDIA_BUTTON_ACTION, prefValue).commit()
    }

    private fun press(keyCode: Int): Boolean {
        val down = handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val up = handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return down && up
    }

    @Test
    fun headsetHookTogglesTalkingInPushToTalkMode() {
        assertThat(press(KeyEvent.KEYCODE_HEADSETHOOK)).isTrue()
        assertThat(target.isTalking).isTrue()

        press(KeyEvent.KEYCODE_HEADSETHOOK)
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun playPauseTogglesMuteInVoiceActivityMode() {
        target.transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY

        assertThat(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isTrue()

        assertThat(target.muteToggles).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun avrcpPlayAndPauseKeysBothToggle() {
        target.transmitMode = Constants.TRANSMIT_CONTINUOUS

        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)

        assertThat(target.muteToggles).isEqualTo(2)
    }

    @Test
    fun actionFiresOnceOnKeyUpOnly() {
        handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        assertThat(target.isTalking).isFalse()

        // a held key repeats ACTION_DOWN; repeats must not toggle
        handler.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 3))
        assertThat(target.isTalking).isFalse()

        handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
        assertThat(target.isTalking).isTrue()
    }

    @Test
    fun repeatedKeyUpIsConsumedWithoutToggling() {
        val repeatedUp = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 1)

        assertThat(handler.onKeyEvent(repeatedUp)).isTrue()

        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun canceledKeyUpAfterLongPressIsConsumedWithoutToggling() {
        // The system consumed the long press (voice assistant); the UP arrives with FLAG_CANCELED.
        val canceledUp = KeyEvent(
            0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0, 0, 0, 0, KeyEvent.FLAG_CANCELED,
        )

        assertThat(handler.onKeyEvent(canceledUp)).isTrue()

        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun muteSettingAlwaysTogglesMuteEvenInPushToTalkMode() {
        setAction("mute")

        press(KeyEvent.KEYCODE_HEADSETHOOK)

        assertThat(target.muteToggles).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun noneSettingDoesNotConsumeTheKey() {
        setAction("none")

        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun ignoredWhileDisconnected() {
        target.isConnected = false

        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun unrelatedMediaKeysAreNotConsumed() {
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))).isFalse()
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.service.MediaKeyHandlerTest'`
Expected: compilation FAILS with `Unresolved reference: MediaKeyTarget` / `MediaKeyHandler`.

- [ ] **Step 3: Implement the seam and the handler**

Create `app/src/main/java/se/lublin/mumla/service/MediaKeyTarget.kt`:

```kotlin
package se.lublin.mumla.service

/**
 * The part of a Mumla session a headset/media button may act on.
 * Kept tiny so [MediaKeyHandler] can be tested against a fake.
 */
interface MediaKeyTarget {
    /** True while the server session is synchronized (IHumlaService.isConnected). */
    val isConnected: Boolean

    /** One of se.lublin.humla.Constants.TRANSMIT_*. */
    val transmitMode: Int

    val isTalking: Boolean

    fun setTalking(talking: Boolean)

    /** Flip self-mute; deafen is cleared when unmuting, kept when muting (as the mute menu item does). */
    fun toggleSelfMute()
}
```

Create `app/src/main/java/se/lublin/mumla/service/MediaKeyHandler.kt`:

```kotlin
package se.lublin.mumla.service

import android.view.KeyEvent
import se.lublin.humla.Constants
import se.lublin.mumla.MediaButtonAction
import se.lublin.mumla.Settings

/**
 * Maps headset / AVRCP media key events to push-to-talk or mute toggles (spec P1).
 * Pure logic; [MumlaMediaSession] feeds it the events of the active MediaSession.
 */
class MediaKeyHandler(
    private val settings: Settings,
    private val target: MediaKeyTarget,
) {
    /**
     * @return true if the event was consumed. DOWN and repeated DOWN events of a handled key are
     * consumed without acting; the action fires on an UP event with repeatCount 0 that the
     * system did not cancel (a canceled UP follows a long press the system consumed itself).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in HANDLED_KEYS) return false
        if (!target.isConnected) return false
        val action = settings.getMediaButtonAction()
        if (action == MediaButtonAction.NONE) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        if (event.repeatCount != 0) return true
        if (event.flags and KeyEvent.FLAG_CANCELED != 0) return true

        when (action) {
            MediaButtonAction.AUTO ->
                if (target.transmitMode == Constants.TRANSMIT_PUSH_TO_TALK) {
                    target.setTalking(!target.isTalking)
                } else {
                    target.toggleSelfMute()
                }
            MediaButtonAction.MUTE -> target.toggleSelfMute()
            MediaButtonAction.NONE -> Unit // returned above
        }
        return true
    }

    companion object {
        val HANDLED_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
```

- [ ] **Step 4: Run the handler test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.service.MediaKeyHandlerTest'`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 5: Write the failing adapter test**

`IHumlaSession` has ~55 methods and is implemented only by `HumlaService`; the adapter's contract is *which* session calls it makes with *which* arguments, so this is the one place MockK is used. Only `toggleSelfMute()` derives something (the new muted/deafened pair from the user's current state), so only it gets tests; `isConnected`, `transmitMode`, `isTalking` and `setTalking` are one-line forwards and earn none.

Create `app/src/test/java/se/lublin/mumla/service/HumlaMediaKeyTargetTest.kt`:

```kotlin
package se.lublin.mumla.service

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser

class HumlaMediaKeyTargetTest {
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val service = mockk<IHumlaService> {
        every { HumlaSession() } returns session
    }
    private val target = HumlaMediaKeyTarget(service)

    @Test
    fun toggleMuteMutesAnUnmutedUserKeepingDeafenOff() {
        val self = mockk<IUser> {
            every { isSelfMuted } returns false
            every { isSelfDeafened } returns false
        }
        every { session.sessionUser } returns self

        target.toggleSelfMute()

        verify(exactly = 1) { session.setSelfMuteDeafState(true, false) }
    }

    @Test
    fun toggleMuteUnmutesAMutedAndDeafenedUserAndUndeafens() {
        val self = mockk<IUser> {
            every { isSelfMuted } returns true
            every { isSelfDeafened } returns true
        }
        every { session.sessionUser } returns self

        target.toggleSelfMute()

        verify(exactly = 1) { session.setSelfMuteDeafState(false, false) }
    }

    @Test
    fun toggleMuteDoesNothingWithoutSessionUser() {
        every { session.sessionUser } returns null

        target.toggleSelfMute()

        verify(exactly = 0) { session.setSelfMuteDeafState(any(), any()) }
    }
}
```

- [ ] **Step 6: Run the adapter test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.service.HumlaMediaKeyTargetTest'`
Expected: compilation FAILS with `Unresolved reference: HumlaMediaKeyTarget`.

- [ ] **Step 7: Implement the adapter**

Create `app/src/main/java/se/lublin/mumla/service/HumlaMediaKeyTarget.kt`:

```kotlin
package se.lublin.mumla.service

import se.lublin.humla.IHumlaService

/** [MediaKeyTarget] backed by the live Humla session of [service]. */
class HumlaMediaKeyTarget(private val service: IHumlaService) : MediaKeyTarget {
    override val isConnected: Boolean
        get() = service.isConnected

    override val transmitMode: Int
        get() = service.HumlaSession().transmitMode

    override val isTalking: Boolean
        get() = service.HumlaSession().isTalking

    override fun setTalking(talking: Boolean) {
        service.HumlaSession().setTalkingState(talking)
    }

    override fun toggleSelfMute() {
        val session = service.HumlaSession()
        val self = session.sessionUser ?: return
        val muted = !self.isSelfMuted
        val deafened = self.isSelfDeafened && muted
        session.setSelfMuteDeafState(muted, deafened)
    }
}
```

- [ ] **Step 8: Run both tests to verify they pass**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.service.MediaKeyHandlerTest' --tests 'se.lublin.mumla.service.HumlaMediaKeyTargetTest'`
Expected: `BUILD SUCCESSFUL`, 13 tests passed (10 handler + 3 adapter).

- [ ] **Step 9: Run the green gate and commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/service/MediaKeyTarget.kt app/src/main/java/se/lublin/mumla/service/MediaKeyHandler.kt app/src/main/java/se/lublin/mumla/service/HumlaMediaKeyTarget.kt app/src/test/java/se/lublin/mumla/service/MediaKeyHandlerTest.kt app/src/test/java/se/lublin/mumla/service/HumlaMediaKeyTargetTest.kt
git commit -m "feat: map headset media keys to push-to-talk and mute"
```

---

### Task 4: MumlaMediaSession and its service hooks (P1)

**Files:**
- Modify: `gradle/libs.versions.toml` (add `androidx-media = "1.8.0"` + library alias — Foundation-owned, 2 lines, see "Cross-stream touches") **or**, if F3 left `app/build.gradle` with literal coordinates, only `app/build.gradle`
- Modify: `app/build.gradle` `dependencies {}` (add one `implementation` line — Foundation-owned, 1 line)
- Create: `app/src/main/java/se/lublin/mumla/service/MumlaMediaSession.kt`
- Modify: `app/src/main/java/se/lublin/mumla/service/MumlaService.java:291-319` (`onCreate`), `:326-350` (`onDestroy`) — stream A file, hook of 4 lines (see "Cross-stream touches")
- Test: `app/src/test/java/se/lublin/mumla/service/MumlaMediaSessionTest.kt`

**Interfaces:**
- Consumes: `MediaKeyHandler`, `MediaKeyTarget` (Task 3); `Settings.getMediaButtonAction()`, `Settings.PREF_MEDIA_BUTTON_ACTION`, `MediaButtonAction.NONE` (Task 1); `HumlaObserver.onConnected()/onDisconnected(HumlaException)`; `IHumlaService.registerObserver/unregisterObserver`; `SharedPreferences.OnSharedPreferenceChangeListener` on the default preferences.
- Produces:
  - `class MumlaMediaSession(context: Context, target: MediaKeyTarget, settings: Settings)` with `fun attach(service: IHumlaService)`, `fun detach(service: IHumlaService)`, `fun activate()` (= "connected; hold a session unless the action is `NONE`"), `fun deactivate()`, `val isActive: Boolean`, `val callback: MediaSessionCompat.Callback` (exposed for tests), `val sessionToken: MediaSessionCompat.Token?`, `val playbackState: PlaybackStateCompat?` (what the session currently advertises; exposed for tests).
  - `MumlaService` field `mMediaSession` (private).
  - Threading contract: every public member is called on the main thread; the Humla observer posts to the main looper when invoked elsewhere.

Why a media session at all: with the screen off no Activity has focus, so hardware/AVRCP media keys are only delivered to the app that owns the *active* `MediaSession` whose playback state is `PLAYING`. The session is therefore active exactly while connected **and** the headset-button action is not `NONE` (`onConnected` → `activate()`, `onDisconnected` → `deactivate()`, a change of `media_button_action` while connected → re-evaluate), reports `STATE_PLAYING` with `ACTION_PLAY_PAUSE | ACTION_PLAY | ACTION_PAUSE`, and the `Callback.onMediaButtonEvent` override forwards the `KeyEvent` to `MediaKeyHandler`. Once a session is active with `STATE_PLAYING`, the system delivers play/pause to Mumla and to nobody else — returning `false` from `onMediaButtonEvent` only falls through to the default callback of our own session, there is no re-dispatch to another app. That is why `NONE` must mean "no active session": otherwise Mumla would steal play/pause from every music app for the whole connection. While `MumlaActivity` is in the foreground, `Activity.onKeyDown/onKeyUp` still see the key first (so a user who bound `KEYCODE_HEADSETHOOK` as PTT key keeps that behavior); unhandled keys fall through `PhoneWindow` to the session.

Threading: `HumlaCallbacks` (`HumlaCallbacks.java:33-53`) invokes observers on the caller's thread; today that is the main thread only because `HumlaConnection` posts there, and stream A may change that. `MediaSessionCompat(context, TAG)` and `setCallback` need a Looper, so the wrapper passes an explicit main-looper `Handler` to `setCallback` and its observer posts `activate()`/`deactivate()` to the main looper whenever `Looper.myLooper() != Looper.getMainLooper()`. All state of `MumlaMediaSession` is main-thread-confined; Robolectric's test thread *is* the main looper, so the tests exercise the direct path.

- [ ] **Step 1: Add the dependency**

First check which form `app/build.gradle` uses after F3:

Run: `cd /home/becker/git/mumla && grep -n "libs\.\|androidx.preference" app/build.gradle; ls gradle/libs.versions.toml`

**Case A — the catalog exists and `app/build.gradle` uses `libs.*` aliases.** In `gradle/libs.versions.toml` add under `[versions]`:

```toml
androidx-media = "1.8.0"
```

and under `[libraries]`:

```toml
androidx-media = { group = "androidx.media", name = "media", version.ref = "androidx-media" }
```

In `app/build.gradle` inside `dependencies { ... }` add:

```groovy
    implementation libs.androidx.media
```

**Case B — `app/build.gradle` still declares literal coordinates (as at commit `6f4f899`, `app/build.gradle:147-161`) or no `gradle/libs.versions.toml` exists.** Do not create the catalog; add one line next to `implementation 'androidx.preference:preference:1.2.1'` (or whatever version F4 set):

```groovy
    implementation 'androidx.media:media:1.8.0'
```

Either way this is a one-purpose edit to a Foundation-owned file; it is listed under "Cross-stream touches" and needs Foundation's acknowledgement at integration (spec §5), which is a formality because the line is additive.

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:dependencies --configuration fossDebugRuntimeClasspath | grep 'androidx.media:media'`
Expected: a line containing `androidx.media:media:1.8.0`.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/se/lublin/mumla/service/MumlaMediaSessionTest.kt`:

```kotlin
package se.lublin.mumla.service

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.Constants
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.Settings

@RunWith(RobolectricTestRunner::class)
class MumlaMediaSessionTest {
    private class FakeTarget : MediaKeyTarget {
        override var isConnected = true
        override var transmitMode = Constants.TRANSMIT_PUSH_TO_TALK
        override var isTalking = false
        override fun setTalking(talking: Boolean) { isTalking = talking }
        override fun toggleSelfMute() = Unit
    }

    private lateinit var context: Context
    private lateinit var target: FakeTarget
    private lateinit var mediaSession: MumlaMediaSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        target = FakeTarget()
        mediaSession = MumlaMediaSession(context, target, Settings.getInstance(context))
    }

    private fun mediaButtonIntent(action: Int, keyCode: Int): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))

    private fun setAction(prefValue: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(Settings.PREF_MEDIA_BUTTON_ACTION, prefValue).commit()
    }

    /** A service double that hands the registered observer back to the test. */
    private fun serviceCapturing(observer: (IHumlaObserver) -> Unit): IHumlaService =
        mockk {
            every { registerObserver(any()) } answers { observer(firstArg()) }
            every { unregisterObserver(any()) } returns Unit
        }

    @Test
    fun inactiveUntilActivated() {
        assertThat(mediaSession.isActive).isFalse()

        mediaSession.activate()

        assertThat(mediaSession.isActive).isTrue()
    }

    @Test
    fun activeSessionReportsPlayingSoMediaKeysAreRoutedToIt() {
        assertThat(mediaSession.playbackState).isNull()

        mediaSession.activate()

        val state = mediaSession.playbackState!!
        assertThat(state.state).isEqualTo(PlaybackStateCompat.STATE_PLAYING)
        assertThat(state.actions and PlaybackStateCompat.ACTION_PLAY_PAUSE).isNotEqualTo(0L)
    }

    @Test
    fun noneSettingKeepsSessionInactiveOnConnect() {
        setAction("none")
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })

        observer!!.onConnected()

        assertThat(mediaSession.isActive).isFalse()
        assertThat(mediaSession.sessionToken).isNull()
    }

    @Test
    fun switchingToNoneWhileConnectedReleasesSessionAndBackRestoresIt() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        assertThat(mediaSession.isActive).isTrue()

        setAction("none")
        assertThat(mediaSession.isActive).isFalse()

        setAction("mute")
        assertThat(mediaSession.isActive).isTrue()
    }

    @Test
    fun deactivateReleasesTheSession() {
        mediaSession.activate()

        mediaSession.deactivate()

        assertThat(mediaSession.isActive).isFalse()
        assertThat(mediaSession.sessionToken).isNull()
    }

    @Test
    fun activateTwiceKeepsOneSession() {
        mediaSession.activate()
        val first = mediaSession.sessionToken

        mediaSession.activate()

        assertThat(mediaSession.sessionToken).isEqualTo(first)
    }

    @Test
    fun callbackForwardsMediaButtonToHandler() {
        mediaSession.activate()

        val handledDown = mediaSession.callback.onMediaButtonEvent(
            mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        val handledUp = mediaSession.callback.onMediaButtonEvent(
            mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))

        assertThat(handledDown).isTrue()
        assertThat(handledUp).isTrue()
        assertThat(target.isTalking).isTrue()
    }

    @Test
    fun callbackIgnoresIntentWithoutKeyEvent() {
        assertThat(mediaSession.callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON))).isFalse()
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun attachActivatesOnConnectedAndDeactivatesOnDisconnected() {
        var observer: IHumlaObserver? = null

        mediaSession.attach(serviceCapturing { observer = it })
        assertThat(observer).isNotNull()
        assertThat(mediaSession.isActive).isFalse()

        observer!!.onConnected()
        assertThat(mediaSession.isActive).isTrue()

        observer!!.onDisconnected(null)
        assertThat(mediaSession.isActive).isFalse()
    }

    @Test
    fun detachUnregistersAndDeactivates() {
        var observer: IHumlaObserver? = null
        val service = serviceCapturing { observer = it }
        mediaSession.attach(service)
        observer!!.onConnected()

        mediaSession.detach(service)

        verify(exactly = 1) { service.unregisterObserver(observer!!) }
        assertThat(mediaSession.isActive).isFalse()
        // the preference listener is gone too: a later change must not resurrect the session
        setAction("mute")
        assertThat(mediaSession.isActive).isFalse()
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.service.MumlaMediaSessionTest'`
Expected: compilation FAILS with `Unresolved reference: MumlaMediaSession`.

- [ ] **Step 4: Implement the session wrapper**

Create `app/src/main/java/se/lublin/mumla/service/MumlaMediaSession.kt`:

```kotlin
package se.lublin.mumla.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.MediaButtonAction
import se.lublin.mumla.Settings

/**
 * Owns a [MediaSessionCompat] that is active exactly while Mumla is connected and the headset
 * button action is not [MediaButtonAction.NONE], so that headset and Bluetooth (AVRCP) media
 * buttons reach [MediaKeyHandler] even with the screen off (spec P1). With NONE no session is
 * held at all, because an active PLAYING session takes play/pause away from every other app.
 *
 * Create it in the service's onCreate and call [attach]; call [detach] in onDestroy.
 * All state is confined to the main thread: [attach], [detach], [activate], [deactivate] and the
 * properties must be called there; the Humla observer posts to the main looper if needed.
 */
class MumlaMediaSession(
    private val context: Context,
    target: MediaKeyTarget,
    private val settings: Settings,
) {
    private val handler = MediaKeyHandler(settings, target)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null
    private var connected = false

    /** Public for tests; the framework calls it on [mainHandler]. */
    val callback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(
                mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java,
            ) ?: return false
            return handler.onKeyEvent(event) || super.onMediaButtonEvent(mediaButtonEvent)
        }
    }

    private val observer = object : HumlaObserver() {
        override fun onConnected() = onMain { activate() }

        override fun onDisconnected(e: HumlaException?) = onMain { deactivate() }
    }

    // Strong reference: SharedPreferences keeps listeners weakly.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Settings.PREF_MEDIA_BUTTON_ACTION) applyState()
    }

    val isActive: Boolean
        get() = session?.isActive == true

    val sessionToken: MediaSessionCompat.Token?
        get() = session?.sessionToken

    /** The state the session advertises to the system (null while no session is held). */
    val playbackState: PlaybackStateCompat?
        get() = session?.controller?.playbackState

    fun attach(service: IHumlaService) {
        service.registerObserver(observer)
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun detach(service: IHumlaService) {
        service.unregisterObserver(observer)
        PreferenceManager.getDefaultSharedPreferences(context)
            .unregisterOnSharedPreferenceChangeListener(preferenceListener)
        deactivate()
    }

    /** We are connected: hold an active session unless the action is NONE. */
    fun activate() {
        connected = true
        applyState()
    }

    /** We are disconnected (or shutting down): release the session. */
    fun deactivate() {
        connected = false
        applyState()
    }

    private fun applyState() {
        val wanted = connected && settings.getMediaButtonAction() != MediaButtonAction.NONE
        if (wanted) ensureSession() else releaseSession()
    }

    private fun ensureSession() {
        if (session != null) return
        session = MediaSessionCompat(context, TAG).apply {
            setCallback(callback, mainHandler)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE,
                    )
                    .setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build(),
            )
            isActive = true
        }
    }

    private fun releaseSession() {
        session?.apply {
            isActive = false
            release()
        }
        session = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "MumlaMediaSession"
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.service.MumlaMediaSessionTest'`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

How the JVM round-trip works (see "Upstream facts"): `ShadowMediaSession` runs the real `MediaSession` constructor against a deep-proxy `ISessionManager`, so the framework token is non-null, `setActive`/`setPlaybackState` on the framework binder are no-ops, `MediaSession.isActive()` reads the local field, and `session.controller.playbackState` is answered by `MediaSessionCompat`'s in-process extra binder, which stores the `PlaybackStateCompat` the wrapper set.

If `activeSessionReportsPlayingSoMediaKeysAreRoutedToIt` nevertheless fails with `playbackState` being `null` or with an `IllegalArgumentException` from a framework `MediaController` constructor on the Robolectric version F3 pinned, the compat controller path is not available on that JVM. Then, and only then: change the `playbackState` getter to return the state the wrapper last set itself (`private var advertisedState: PlaybackStateCompat?`, assigned in `ensureSession()` from the same `Builder`, cleared in `releaseSession()`), keep the test unchanged, and note in the commit body that the getter reports the state Mumla set rather than what the controller reads back. Do not delete the test: `isActive` plus the `callback` round-trip do not cover the `STATE_PLAYING`/`ACTION_PLAY_PAUSE` contract, and that contract is what makes the system route keys to Mumla.

- [ ] **Step 6: Hook the session into MumlaService (stream A file, 4 lines)**

In `app/src/main/java/se/lublin/mumla/service/MumlaService.java`:

Add the field next to the other notification fields (after line 77 `private MumlaReconnectNotification mReconnectNotification;`):

```java
    /** Headset / AVRCP media buttons while connected (stream P). */
    private MumlaMediaSession mMediaSession;
```

In `onCreate()` after `mTalkReceiver = new TalkBroadcastReceiver(this);` (line 318):

```java
        mMediaSession = new MumlaMediaSession(this, new HumlaMediaKeyTarget(this), mSettings);
        mMediaSession.attach(this);
```

In `onDestroy()` before `unregisterObserver(mObserver);` (line 345):

```java
        mMediaSession.detach(this);
```

No import is needed (same package).

- [ ] **Step 7: Build and run the green gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
cd /home/becker/git/mumla
git add app/build.gradle app/src/main/java/se/lublin/mumla/service/MumlaMediaSession.kt app/src/main/java/se/lublin/mumla/service/MumlaService.java app/src/test/java/se/lublin/mumla/service/MumlaMediaSessionTest.kt
# Case A of Step 1 only (the catalog was edited):
git add gradle/libs.versions.toml
git commit -m "feat: handle headset media keys through a media session while connected"
```

---

### Task 5: Headset button action preference (P1 "configurable in settings")

**Files:**
- Modify: `app/src/main/res/xml/settings_general.xml` (append a "Controls" category)
- Modify: `app/src/main/res/values/preference.xml` (titles/summaries/entry names)
- Modify: `app/src/main/res/values/preference_notranslate.xml` (entry values array)
- Test: `app/src/test/java/se/lublin/mumla/MediaButtonActionResourcesTest.kt`

**Interfaces:**
- Consumes: `MediaButtonAction.prefValue` (Task 1), key `Settings.PREF_MEDIA_BUTTON_ACTION = "media_button_action"`.
- Produces: `R.array.mediaButtonActionNames`, `R.array.mediaButtonActionValues`, `R.string.controls`, `R.string.mediaButtonAction`, `R.string.mediaButtonActionSum`, and a `PreferenceCategory` with key `controls_settings` in the general screen (Task 7 adds the Bluetooth checkbox to the same category).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/se/lublin/mumla/MediaButtonActionResourcesTest.kt`:

```kotlin
package se.lublin.mumla

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The ListPreference entry values must be exactly the enum's prefValues, in the same order. */
@RunWith(RobolectricTestRunner::class)
class MediaButtonActionResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun entryValuesMatchEnumPrefValues() {
        val values = context.resources.getStringArray(R.array.mediaButtonActionValues).toList()

        assertThat(values).containsExactly("none", "auto", "mute").inOrder()
        assertThat(values).isEqualTo(MediaButtonAction.entries.map { it.prefValue })
    }

    @Test
    fun everyEntryValueHasAName() {
        val names = context.resources.getStringArray(R.array.mediaButtonActionNames)
        val values = context.resources.getStringArray(R.array.mediaButtonActionValues)

        assertThat(names).hasLength(values.size)
        assertThat(names.toList()).containsNoDuplicates()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.MediaButtonActionResourcesTest'`
Expected: compilation FAILS with `Unresolved reference: mediaButtonActionValues`.

- [ ] **Step 3: Add strings, arrays and the preference**

Append to `app/src/main/res/values/preference.xml` before `</resources>`:

```xml
    <!-- Controls (stream P) -->
    <string name="controls">Controls</string>
    <string name="mediaButtonAction">Headset button</string>
    <string name="mediaButtonActionSum">What the play/pause button of a wired or Bluetooth headset does while connected. Works with the screen off.</string>
    <string name="mediaButtonActionNone">Nothing</string>
    <string name="mediaButtonActionAuto">Toggle push-to-talk (mute in other transmit modes)</string>
    <string name="mediaButtonActionMute">Toggle mute</string>
```

Append to `app/src/main/res/values/preference_notranslate.xml` before `</resources>`:

```xml
    <string-array name="mediaButtonActionNames">
        <item>@string/mediaButtonActionNone</item>
        <item>@string/mediaButtonActionAuto</item>
        <item>@string/mediaButtonActionMute</item>
    </string-array>

    <string-array name="mediaButtonActionValues">
        <item>none</item>
        <item>auto</item>
        <item>mute</item>
    </string-array>
```

Append to `app/src/main/res/xml/settings_general.xml` before `</PreferenceScreen>` (after the `startUpInPinnedMode` checkbox):

```xml
    <PreferenceCategory
        android:key="controls_settings"
        android:title="@string/controls"
        app:iconSpaceReserved="false">
        <ListPreference
            android:defaultValue="auto"
            android:entries="@array/mediaButtonActionNames"
            android:entryValues="@array/mediaButtonActionValues"
            android:key="media_button_action"
            android:summary="@string/mediaButtonActionSum"
            android:title="@string/mediaButtonAction"
            app:iconSpaceReserved="false" />
    </PreferenceCategory>
```

(`android:defaultValue="auto"` must equal `Settings.DEFAULT_MEDIA_BUTTON_ACTION`; the comment in `Settings` lines 51–53 explains that the XML default is not picked up from code.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.MediaButtonActionResourcesTest'`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Run the green gate and commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/res/xml/settings_general.xml app/src/main/res/values/preference.xml app/src/main/res/values/preference_notranslate.xml app/src/test/java/se/lublin/mumla/MediaButtonActionResourcesTest.kt
git commit -m "feat: add headset button action preference"
```

---

### Task 6: Convert ChannelListFragment to Kotlin

**Files:**
- Delete: `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.java`
- Create: `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt`

**Interfaces:**
- Consumes (unchanged Java neighbours): `HumlaServiceFragment` (`getService()`, `onServiceBound`, `getServiceObserver`), `ChannelListAdapter(Context, IHumlaService, MumlaDatabase, FragmentManager, boolean, boolean)` with `setOnChannelClickListener`, `setOnUserClickListener`, `setService`, `updateChannels`, `updateUserStates(IUser, RecyclerView)`, `getChannelPosition`, `getUserPosition`, `setShowChannelUserCount`; `ChatTargetProvider`, `ChatTargetProvider.ChatTarget`, `ChatTargetActionModeCallback`; `ChannelSearchProvider.INTENT_DATA_CHANNEL/USER`; `DatabaseProvider`; `Settings`.
- Produces: the same public surface `ChannelListFragment` (`scrollToChannel(Int)`, `scrollToUser(Int)`), instantiated reflectively by `ChannelFragment`'s pager adapter — the class name and package stay the same.

This is a behavior-preserving conversion: the Bluetooth menu still calls `session.enableBluetoothSco()/disableBluetoothSco()` and reads `usingBluetoothSco()`; Task 7 changes that. Two API-floor clean-ups are made while touching the file, as the global constraints require: the `Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE` branch for `registerReceiver` is replaced by `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` (single code path, also silences lint `UnspecifiedRegisterReceiverFlag`), and the unused imports go.

- [ ] **Step 1: Write the Kotlin file**

Create `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt`:

```kotlin
/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.channel

import android.app.Activity
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.CursorWrapper
import android.graphics.PorterDuff
import android.media.AudioManager
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.util.HumlaServiceFragment

class ChannelListFragment : HumlaServiceFragment(), OnChannelClickListener, OnUserClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val serviceObserver: IHumlaObserver = object : HumlaObserver() {
        override fun onDisconnected(e: HumlaException?) {
            channelView.adapter = null
        }

        override fun onUserJoinedChannel(user: IUser, newChannel: IChannel, oldChannel: IChannel?) {
            channelListAdapter?.updateChannels()
            channelListAdapter?.notifyDataSetChanged()

            val service = service
            if (service == null || !service.isConnected) {
                return
            }

            val selfSession = try {
                service.HumlaSession().sessionId
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            }

            if (user.session == selfSession) {
                scrollToChannel(newChannel.id)
            }
        }

        override fun onChannelAdded(channel: IChannel) {
            channelListAdapter?.updateChannels()
            channelListAdapter?.notifyDataSetChanged()
        }

        override fun onChannelRemoved(channel: IChannel) {
            channelListAdapter?.updateChannels()
            channelListAdapter?.notifyDataSetChanged()
        }

        override fun onChannelStateUpdated(channel: IChannel) {
            channelListAdapter?.updateChannels()
            channelListAdapter?.notifyDataSetChanged()
        }

        override fun onUserConnected(user: IUser) {
            channelListAdapter?.updateChannels()
            channelListAdapter?.notifyDataSetChanged()
        }

        override fun onUserRemoved(user: IUser, reason: String?) {
            // If we are the user being removed, don't update the channel list.
            // We won't be in a synchronized state.
            val service = service
            if (service == null || !service.isConnected) {
                return
            }

            channelListAdapter?.updateChannels()
            channelListAdapter?.notifyDataSetChanged()
        }

        override fun onUserStateUpdated(user: IUser) {
            channelListAdapter?.updateUserStates(user, channelView)
            requireActivity().invalidateOptionsMenu() // Update self mute/deafen state
        }

        override fun onUserTalkStateUpdated(user: IUser) {
            channelListAdapter?.updateUserStates(user, channelView)
        }
    }

    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            activity?.invalidateOptionsMenu() // Update bluetooth menu item
        }
    }

    private lateinit var channelView: RecyclerView
    private var channelListAdapter: ChannelListAdapter? = null
    private lateinit var targetProvider: ChatTargetProvider
    private lateinit var databaseProvider: DatabaseProvider
    private var actionMode: ActionMode? = null
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    @Suppress("DEPRECATION")
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        targetProvider = parentFragment as? ChatTargetProvider
            ?: throw ClassCastException("$parentFragment must implement ChatTargetProvider")
        databaseProvider = activity as? DatabaseProvider
            ?: throw ClassCastException("$activity must implement DatabaseProvider")
        settings = Settings.getInstance(activity)
        PreferenceManager.getDefaultSharedPreferences(activity)
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_channel_list, container, false)
        channelView = view.findViewById(R.id.channelUsers)
        channelView.layoutManager = LinearLayoutManager(activity)
        return view
    }

    @Suppress("DEPRECATION")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        registerForContextMenu(channelView)
        ContextCompat.registerReceiver(
            requireActivity(),
            bluetoothReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDetach() {
        requireActivity().unregisterReceiver(bluetoothReceiver)
        super.onDetach()
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun getServiceObserver(): IHumlaObserver = serviceObserver

    override fun onServiceBound(service: IHumlaService) {
        try {
            val adapter = channelListAdapter
            if (adapter == null) {
                setupChannelList()
            } else {
                adapter.setService(service)
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        val muteItem = menu.findItem(R.id.menu_mute_button)
        val deafenItem = menu.findItem(R.id.menu_deafen_button)

        val service = service
        if (service != null && service.isConnected) {
            val session = service.HumlaSession()

            // Color the action bar icons to the primary text color of the theme, TODO move this elsewhere
            val foregroundColor = requireActivity().theme
                .obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimaryInverse))
                .getColor(0, -1)

            val self = session.sessionUser
            if (self != null) {
                muteItem.setIcon(if (self.isSelfMuted) R.drawable.ic_action_microphone_muted else R.drawable.ic_action_microphone)
                deafenItem.setIcon(if (self.isSelfDeafened) R.drawable.ic_action_audio_muted else R.drawable.ic_action_audio)
                muteItem.icon?.mutate()?.setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
                deafenItem.icon?.mutate()?.setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
            }

            val bluetoothItem = menu.findItem(R.id.menu_bluetooth)
            bluetoothItem.isChecked = session.usingBluetoothSco()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_channel_list, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchManager = requireActivity().getSystemService(Context.SEARCH_SERVICE) as SearchManager

        val searchView = searchItem.actionView as SearchView
        searchView.setSearchableInfo(searchManager.getSearchableInfo(requireActivity().componentName))
        searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(i: Int): Boolean = false

            override fun onSuggestionClick(i: Int): Boolean {
                val service = service
                if (service == null || !service.isConnected) return false
                val cursor = searchView.suggestionsAdapter.getItem(i) as CursorWrapper
                val typeColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA)
                val dataIdColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA)
                val itemType = cursor.getString(typeColumn)
                val itemId = cursor.getInt(dataIdColumn)

                val session = service.HumlaSession()
                return when (itemType) {
                    ChannelSearchProvider.INTENT_DATA_CHANNEL -> {
                        if (session.sessionChannel.id != itemId) {
                            session.joinChannel(itemId)
                        } else {
                            scrollToChannel(itemId)
                        }
                        true
                    }
                    ChannelSearchProvider.INTENT_DATA_USER -> {
                        scrollToUser(itemId)
                        true
                    }
                    else -> false
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val service = service
        if (service == null || !service.isConnected) {
            return super.onOptionsItemSelected(item)
        }
        val session = service.HumlaSession()

        return when (item.itemId) {
            R.id.menu_mute_button -> {
                session.sessionUser?.let { self ->
                    val muted = !self.isSelfMuted
                    val deafened = self.isSelfDeafened && muted // Undeafen if mute is off
                    session.setSelfMuteDeafState(muted, deafened)
                }
                requireActivity().invalidateOptionsMenu()
                true
            }
            R.id.menu_deafen_button -> {
                session.sessionUser?.let { self ->
                    val deafened = !self.isSelfDeafened
                    session.setSelfMuteDeafState(deafened, deafened)
                }
                requireActivity().invalidateOptionsMenu()
                true
            }
            R.id.menu_search -> false
            R.id.menu_bluetooth -> {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    session.enableBluetoothSco()
                } else {
                    session.disableBluetoothSco()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Throws(RemoteException::class)
    private fun setupChannelList() {
        val adapter = ChannelListAdapter(
            activity, service, databaseProvider.database, childFragmentManager,
            isShowingPinnedChannels(), settings.shouldShowUserCount(),
        )
        adapter.setOnChannelClickListener(this)
        adapter.setOnUserClickListener(this)
        channelView.adapter = adapter
        adapter.notifyDataSetChanged()
        channelListAdapter = adapter
    }

    /** Scrolls to the passed channel. */
    fun scrollToChannel(channelId: Int) {
        val adapter = channelListAdapter ?: return
        channelView.scrollToPosition(adapter.getChannelPosition(channelId))
    }

    /** Scrolls to the passed user. */
    fun scrollToUser(userId: Int) {
        val adapter = channelListAdapter ?: return
        channelView.scrollToPosition(adapter.getUserPosition(userId))
    }

    private fun isShowingPinnedChannels(): Boolean = requireArguments().getBoolean("pinned")

    override fun onChannelClick(channel: IChannel) {
        val current = targetProvider.chatTarget
        if (current != null && channel == current.channel && actionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            actionMode?.finish()
        } else {
            val cb = object : ChatTargetActionModeCallback(targetProvider, targetProvider.ChatTarget(channel)) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    this@ChannelListFragment.actionMode = null
                }
            }
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(cb)
        }
    }

    override fun onUserClick(user: IUser) {
        val current = targetProvider.chatTarget
        if (current != null && user == current.user && actionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            actionMode?.finish()
        } else {
            val cb = object : ChatTargetActionModeCallback(targetProvider, targetProvider.ChatTarget(user)) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    this@ChannelListFragment.actionMode = null
                }
            }
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(cb)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (Settings.PREF_SHOW_USER_COUNT == key) {
            channelListAdapter?.setShowChannelUserCount(settings.shouldShowUserCount())
        }
    }

    companion object {
        private val TAG: String = ChannelListFragment::class.java.name
    }
}
```

Notes for the converter:
- `ChatTargetProvider.ChatTarget` is a non-static inner class of the `ChatTargetProvider` interface (`ChatTargetProvider.java:29-47`); in Kotlin it is constructed as `targetProvider.ChatTarget(channel)`.
- `ChatTargetActionModeCallback` is a Java class with the two-argument constructor `(ChatTargetProvider, ChatTargetProvider.ChatTarget)` and an overridable `onDestroyActionMode(ActionMode)`.
- `ChannelListAdapter`'s constructor takes `(Context, IHumlaService, MumlaDatabase, FragmentManager, boolean, boolean)` (`ChannelListAdapter.java:84`).
- `service` inside the fragment is `HumlaServiceFragment.getService(): IMumlaService` seen as a Kotlin property.

- [ ] **Step 2: Delete the Java file and build**

```bash
cd /home/becker/git/mumla && git rm -q app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.java
```

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug`
Expected: `BUILD SUCCESSFUL`. If the compiler reports a signature mismatch against one of the Java neighbours (e.g. nullability of `onUserRemoved(IUser, String)`), adjust the Kotlin parameter nullability to the Java signature — do not change the Java neighbour.

- [ ] **Step 3: Run the green gate and commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt
git commit -m "refactor: convert ChannelListFragment to kotlin"
```

---

### Task 7: Bluetooth headset as a persistent setting with BLUETOOTH_CONNECT (P2, P3)

**Files:**
- Create: `app/src/main/java/se/lublin/mumla/channel/BluetoothScoToggle.kt`
- Modify: `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt` (from Task 6: menu toggle, checked state, permission launcher, drop the SCO broadcast receiver)
- Modify: `app/src/main/res/xml/settings_general.xml` (checkbox in `controls_settings`), `app/src/main/res/values/preference.xml`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/se/lublin/mumla/service/MumlaService.java:352-389` (`onConnectionSynchronized`), `:414-496` (`onSharedPreferenceChanged`) — stream A file, two hooks (see "Cross-stream touches")
- Test: `app/src/test/java/se/lublin/mumla/channel/BluetoothScoToggleTest.kt`

**Interfaces:**
- Consumes: `Settings.isBluetoothScoEnabled()/setBluetoothScoEnabled()`, `Settings.PREF_BLUETOOTH_SCO` (Task 1); `HumlaService.enableBluetoothSco()/disableBluetoothSco()` (public, `HumlaService.java:914-929`); `HumlaService.isSynchronized()` (`HumlaService.java:340`).
- Produces:
  - `class BluetoothScoToggle(context: Context, settings: Settings)` with `sealed interface Result { Enabled; Disabled; PermissionNeeded }`, `fun toggle(): Result`, `fun onPermissionResult(granted: Boolean): Boolean`, `val isEnabled: Boolean`, `companion fun hasPermission(context: Context): Boolean`.
  - `MumlaService` applies the preference: on synchronization and on every change of `pref_bluetooth_sco` while synchronized.

Design: the preference is the single source of truth. The action-bar item and the settings checkbox both write `pref_bluetooth_sco`; `MumlaService` observes the preference and calls `enableBluetoothSco()`/`disableBluetoothSco()` (which in stream A's design set `bluetoothScoWanted`; today they start/stop SCO directly, so the plan is green both before and after A lands). Turning the toggle on without `BLUETOOTH_CONNECT` first requests the permission and only persists `true` once granted, so SCO is never attempted without the permission. On connect the service also checks the permission so a preference set on a device that later revoked it does not try to route audio.

- [ ] **Step 1: Write the failing toggle test**

Create `app/src/test/java/se/lublin/mumla/channel/BluetoothScoToggleTest.kt`:

```kotlin
package se.lublin.mumla.channel

import android.Manifest
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.Settings

@RunWith(RobolectricTestRunner::class)
class BluetoothScoToggleTest {
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var toggle: BluetoothScoToggle

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        settings = Settings.getInstance(app)
        toggle = BluetoothScoToggle(app, settings)
    }

    @Test
    fun turningOnWithPermissionPersistsEnabled() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val result = toggle.toggle()

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Enabled)
        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun turningOnWithoutPermissionAsksAndDoesNotPersist() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val result = toggle.toggle()

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.PermissionNeeded)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun grantedPermissionResultEnables() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        toggle.toggle()

        val enabled = toggle.onPermissionResult(granted = true)

        assertThat(enabled).isTrue()
        assertThat(settings.isBluetoothScoEnabled()).isTrue()
    }

    @Test
    fun deniedPermissionResultLeavesItOff() {
        val enabled = toggle.onPermissionResult(granted = false)

        assertThat(enabled).isFalse()
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun turningOffNeverNeedsPermission() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val result = toggle.toggle()

        assertThat(result).isEqualTo(BluetoothScoToggle.Result.Disabled)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
    }

    @Test
    fun hasPermissionReflectsGrantState() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(BluetoothScoToggle.hasPermission(app)).isFalse()

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(BluetoothScoToggle.hasPermission(app)).isTrue()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.channel.BluetoothScoToggleTest'`
Expected: compilation FAILS with `Unresolved reference: BluetoothScoToggle`.

- [ ] **Step 3: Implement the toggle**

Create `app/src/main/java/se/lublin/mumla/channel/BluetoothScoToggle.kt`:

```kotlin
package se.lublin.mumla.channel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import se.lublin.mumla.Settings

/**
 * Decision logic behind the "Bluetooth" action-bar item (spec P2/P3): the item writes the
 * persistent preference [Settings.PREF_BLUETOOTH_SCO]; the service applies it. Turning it on
 * requires BLUETOOTH_CONNECT, which the caller requests when [Result.PermissionNeeded] comes back.
 */
class BluetoothScoToggle(
    private val context: Context,
    private val settings: Settings,
) {
    sealed interface Result {
        data object Enabled : Result
        data object Disabled : Result
        data object PermissionNeeded : Result
    }

    val isEnabled: Boolean
        get() = settings.isBluetoothScoEnabled()

    fun toggle(): Result {
        if (settings.isBluetoothScoEnabled()) {
            settings.setBluetoothScoEnabled(false)
            return Result.Disabled
        }
        if (!hasPermission(context)) {
            return Result.PermissionNeeded
        }
        settings.setBluetoothScoEnabled(true)
        return Result.Enabled
    }

    /** @return the new enabled state after the permission dialog. */
    fun onPermissionResult(granted: Boolean): Boolean {
        if (granted) {
            settings.setBluetoothScoEnabled(true)
        }
        return settings.isBluetoothScoEnabled()
    }

    companion object {
        @JvmStatic
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.channel.BluetoothScoToggleTest'`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Wire the fragment to the toggle**

Edit `app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt` (Task 6 version):

Add imports:

```kotlin
import android.Manifest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
```

Remove the imports `android.content.BroadcastReceiver`, `android.content.Intent`, `android.content.IntentFilter`, `android.media.AudioManager`, `androidx.core.content.ContextCompat` and delete the `bluetoothReceiver` property, the `ContextCompat.registerReceiver(...)` call in `onActivityCreated` and the `unregisterReceiver` line in `onDetach` (keep `super.onDetach()`); the SCO broadcast only served to refresh the menu from `usingBluetoothSco()`, which the preference now replaces.

Add properties after `private lateinit var settings: Settings`:

```kotlin
    private lateinit var bluetoothToggle: BluetoothScoToggle
    private val bluetoothPermissionRequester: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!bluetoothToggle.onPermissionResult(granted)) {
                Toast.makeText(requireContext(), R.string.grant_perm_bluetooth, Toast.LENGTH_LONG).show()
            }
            activity?.invalidateOptionsMenu()
        }
```

In `onAttach`, after `settings = Settings.getInstance(activity)`:

```kotlin
        bluetoothToggle = BluetoothScoToggle(activity.applicationContext, settings)
```

In `onPrepareOptionsMenu` replace

```kotlin
            bluetoothItem.isChecked = session.usingBluetoothSco()
```

with

```kotlin
            bluetoothItem.isChecked = bluetoothToggle.isEnabled
```

In `onOptionsItemSelected` replace the `R.id.menu_bluetooth` branch with:

```kotlin
            R.id.menu_bluetooth -> {
                when (bluetoothToggle.toggle()) {
                    BluetoothScoToggle.Result.Enabled -> item.isChecked = true
                    BluetoothScoToggle.Result.Disabled -> item.isChecked = false
                    BluetoothScoToggle.Result.PermissionNeeded ->
                        bluetoothPermissionRequester.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                true
            }
```

In `onSharedPreferenceChanged` add a second branch so the settings checkbox and the menu stay in sync:

```kotlin
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            Settings.PREF_SHOW_USER_COUNT ->
                channelListAdapter?.setShowChannelUserCount(settings.shouldShowUserCount())
            Settings.PREF_BLUETOOTH_SCO -> activity?.invalidateOptionsMenu()
        }
    }
```

- [ ] **Step 6: Add the settings checkbox and strings**

In `app/src/main/res/xml/settings_general.xml`, inside the `controls_settings` category (before the `media_button_action` ListPreference):

```xml
        <CheckBoxPreference
            android:defaultValue="false"
            android:key="pref_bluetooth_sco"
            android:summary="@string/bluetoothScoSum"
            android:title="@string/bluetoothSco"
            app:iconSpaceReserved="false" />
```

Append to `app/src/main/res/values/preference.xml` (inside the "Controls" block from Task 5):

```xml
    <string name="bluetoothSco">Bluetooth headset</string>
    <string name="bluetoothScoSum">Route voice through a connected Bluetooth headset while connected. Needs the Nearby devices permission.</string>
```

Append to `app/src/main/res/values/strings.xml` after `grant_perm_draw_over_apps`:

```xml
    <string name="grant_perm_bluetooth">Please grant the Nearby devices (Bluetooth) permission to use a Bluetooth headset.</string>
```

Turning the checkbox on from the settings screen does not go through `BluetoothScoToggle`, so the service-side permission check (Step 7) is what protects that path; the summary tells the user which permission is needed.

- [ ] **Step 7: Apply the preference in MumlaService (stream A file, two hooks)**

In `app/src/main/java/se/lublin/mumla/service/MumlaService.java`:

Add import:

```java
import se.lublin.mumla.channel.BluetoothScoToggle;
```

In `onConnectionSynchronized()` after the proximity-sensor block (line 388, before the closing brace):

```java
        // Stream P: Bluetooth headset is a persistent preference (spec P2).
        if (mSettings.isBluetoothScoEnabled() && BluetoothScoToggle.hasPermission(this)) {
            enableBluetoothSco();
        }
```

In `onSharedPreferenceChanged()` add a case to the `switch (key)` before `case Settings.PREF_CERT_ID:`:

```java
            case Settings.PREF_BLUETOOTH_SCO:
                if (isSynchronized()) {
                    if (mSettings.isBluetoothScoEnabled() && BluetoothScoToggle.hasPermission(this)) {
                        enableBluetoothSco();
                    } else {
                        disableBluetoothSco();
                    }
                }
                break;
```

- [ ] **Step 8: Build, run all tests, commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/channel/BluetoothScoToggle.kt app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.kt app/src/main/java/se/lublin/mumla/service/MumlaService.java app/src/main/res/xml/settings_general.xml app/src/main/res/values/preference.xml app/src/main/res/values/strings.xml app/src/test/java/se/lublin/mumla/channel/BluetoothScoToggleTest.kt
git commit -m "feat: persist bluetooth headset setting and request bluetooth permission"
```

---

### Task 8: Convert MumlaActivity to Kotlin

**Files:**
- Delete: `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java`
- Create: `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt`

**Interfaces:**
- Consumes (unchanged Java neighbours): `FavouriteServerListFragment.ServerConnectHandler` (`connectToServer(Server)`, `connectToPublicServer(PublicServer)`), `HumlaServiceProvider` (`getService()`, `addServiceFragment`, `removeServiceFragment`), `DatabaseProvider.getDatabase()`, `DrawerAdapter.DrawerDataProvider` (`isConnected()`, `getConnectedServerName()`), `ServerEditFragment.ServerEditListener.onServerEdited(Action, Server)`, `MumlaService.MumlaBinder.getService()`, `IMumlaService`, `ServerConnectTask`, `MumlaCertificateGenerateTask`, `StartupAction` (flavor source sets), `DialogUtils`.
- Produces: the same public surface: `MumlaActivity.EXTRA_DRAWER_FRAGMENT`, `connectToServer(Server)`, `connectToServerWithPerm()`, `connectToPublicServer(PublicServer)`.

Conversion rules applied (behavior-preserving; verified by the green gate — the activity has no unit tests today and its collaborators are bound at runtime):
- Accessor spellings of `Settings.kt` are written as the Java-style functions of today's `Settings.java` (`settings.isFirstRun()`, `settings.getPushToTalkKey()`). If F3 exposed some of them as Kotlin properties, use the property spelling; the first build reports every mismatch.
- The `Hex` import is BouncyCastle's (`org.bouncycastle.util.encoders.Hex`) because F5 replaced Spongycastle; if the branch still carries `org.spongycastle`, keep whatever import the Java file has at that commit.
- `Fragment.instantiate` (deprecated) becomes `supportFragmentManager.fragmentFactory.instantiate`; `DrawerLayout.setDrawerListener` becomes `addDrawerListener`. Nothing else changes.
- `isPortOpen` keeps its blocking `Thread` (it is existing behavior, not a new thread; replacing the Tor pre-check with a coroutine is not in stream P's scope).
- Interface methods that Kotlin would otherwise turn into property accessors (`getService`, `getDatabase`, `isConnected`, `getConnectedServerName`) stay functions; the backing fields are named `boundService` and `db` to avoid accessor clashes.

- [ ] **Step 1: Write the Kotlin file**

Create `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt`:

```kotlin
/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import info.guardianproject.netcipher.proxy.OrbotHelper
import org.bouncycastle.util.encoders.Hex
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.Server
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.MumbleURLParser
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.AccessTokenFragment
import se.lublin.mumla.channel.ChannelFragment
import se.lublin.mumla.channel.ServerInfoFragment
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaSQLiteDatabase
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.preference.MumlaCertificateGenerateTask
import se.lublin.mumla.preference.SettingsActivity
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.PublicServerListFragment
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider
import se.lublin.mumla.util.MumlaTrustStore
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Socket
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

class MumlaActivity : AppCompatActivity(), AdapterView.OnItemClickListener,
    FavouriteServerListFragment.ServerConnectHandler, HumlaServiceProvider, DatabaseProvider,
    SharedPreferences.OnSharedPreferenceChangeListener, DrawerAdapter.DrawerDataProvider,
    ServerEditFragment.ServerEditListener {

    private var boundService: IMumlaService? = null
    private lateinit var db: MumlaDatabase
    private lateinit var settings: Settings

    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerAdapter: DrawerAdapter

    private var serverPendingPerm: Server? = null
    private var permPostNotificationsAsked = false

    private var connectingDialog: AlertDialog? = null
    private var errorDialog: AlertDialog? = null

    /** List of fragments to be notified about service state changes. */
    private val serviceFragments = ArrayList<HumlaServiceFragment>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val bound = (service as MumlaService.MumlaBinder).service
            boundService = bound
            bound.setSuppressNotifications(true)
            bound.registerObserver(observer)
            bound.clearChatNotifications() // Clear chat notifications on resume.
            drawerAdapter.notifyDataSetChanged()

            for (fragment in serviceFragments) {
                fragment.setServiceBound(true)
            }

            // Re-show server list if we're showing a fragment that depends on the service.
            if (supportFragmentManager.findFragmentById(R.id.content_frame) is HumlaServiceFragment &&
                !bound.isConnected
            ) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            updateConnectionState(bound)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
        }
    }

    private val observer = object : HumlaObserver() {
        override fun onConnected() {
            if (settings.shouldStartUpInPinnedMode()) {
                loadDrawerFragment(DrawerAdapter.ITEM_PINNED_CHANNELS)
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_SERVER)
            }

            drawerAdapter.notifyDataSetChanged()
            invalidateOptionsMenu()

            boundService?.let { updateConnectionState(it) }
        }

        override fun onConnecting() {
            boundService?.let { updateConnectionState(it) }
        }

        override fun onDisconnected(e: HumlaException?) {
            // Re-show server list if we're showing a fragment that depends on the service.
            if (supportFragmentManager.findFragmentById(R.id.content_frame) is HumlaServiceFragment) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            drawerAdapter.notifyDataSetChanged()
            invalidateOptionsMenu()

            boundService?.let { updateConnectionState(it) }
        }

        override fun onTLSHandshakeFailed(chain: Array<X509Certificate>) {
            if (chain.isEmpty()) {
                return
            }
            val lastServer = boundService?.targetServer ?: return
            try {
                val x509 = chain[0]
                val layout = layoutInflater.inflate(R.layout.certificate_info, null)
                val textView = layout.findViewById<TextView>(R.id.certificate_info_text)
                try {
                    val digest1 = MessageDigest.getInstance("SHA-1")
                    val digest2 = MessageDigest.getInstance("SHA-256")
                    val hexDigest1 = String(Hex.encode(digest1.digest(x509.encoded)))
                        .replace("(..)".toRegex(), "$1:")
                    val hexDigest2 = String(Hex.encode(digest2.digest(x509.encoded)))
                        .replace("(..)".toRegex(), "$1:")

                    textView.text = getString(
                        R.string.certificate_info,
                        x509.subjectDN.name,
                        x509.notBefore.toString(),
                        x509.notAfter.toString(),
                        hexDigest1.substring(0, hexDigest1.length - 1),
                        hexDigest2.substring(0, hexDigest2.length - 1),
                    )
                } catch (e: NoSuchAlgorithmException) {
                    e.printStackTrace()
                    textView.text = x509.toString()
                }
                MaterialAlertDialogBuilder(this@MumlaActivity)
                    .setTitle(R.string.untrusted_certificate)
                    .setView(layout)
                    .setPositiveButton(R.string.allow) { _, _ ->
                        // Try to add to trust store
                        try {
                            val alias = lastServer.host
                            val trustStore = MumlaTrustStore.getTrustStore(this@MumlaActivity)
                            trustStore.setCertificateEntry(alias, x509)
                            MumlaTrustStore.saveTrustStore(this@MumlaActivity, trustStore)
                            Toast.makeText(this@MumlaActivity, R.string.trust_added, Toast.LENGTH_LONG).show()
                            connectToServer(lastServer)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this@MumlaActivity, R.string.trust_add_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: CertificateException) {
                e.printStackTrace()
            }
        }

        override fun onPermissionDenied(reason: String?) {
            MaterialAlertDialogBuilder(this@MumlaActivity)
                .setTitle(R.string.perm_denied)
                .setMessage(reason)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings.getInstance(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val service = boundService
                if (service != null && service.isConnected) {
                    MaterialAlertDialogBuilder(this@MumlaActivity)
                        .setMessage(getString(R.string.disconnectSure, service.targetServer.name))
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            service.disconnect()
                            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setStayAwake(settings.shouldStayAwake())

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)

        db = MumlaSQLiteDatabase(this) // TODO add support for cloud storage
        db.open()

        drawerLayout = findViewById(R.id.drawer_layout)
        val drawerList = findViewById<ListView>(R.id.left_drawer)

        val headerView = layoutInflater.inflate(R.layout.list_drawer_headerlogo, drawerList, false)
        drawerList.addHeaderView(headerView, null, false)

        if (BuildConfig.FLAVOR == "foss") {
            val layoutResId = resources.getIdentifier("list_drawer_headerdonate_foss", "xml", packageName)
            val stringResId = resources.getIdentifier("donate_link_foss", "string", packageName)
            if (layoutResId != 0 && stringResId != 0) {
                val footerView = layoutInflater.inflate(layoutResId, drawerList, false)
                drawerList.addHeaderView(footerView, null, true)
                footerView.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(stringResId))))
                    drawerLayout.closeDrawers()
                }
            }
        }

        drawerList.onItemClickListener = this
        drawerAdapter = DrawerAdapter(this, this)
        drawerList.adapter = drawerAdapter
        drawerToggle = object : ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close,
        ) {
            override fun onDrawerClosed(drawerView: View) {
                invalidateOptionsMenu()
            }

            override fun onDrawerStateChanged(newState: Int) {
                super.onDrawerStateChanged(newState)
                // Prevent push to talk from getting stuck on when the drawer is opened.
                val service = boundService
                if (service != null && service.isConnected) {
                    val session = service.HumlaSession()
                    if (session.isTalking && !settings.isPushToTalkToggle()) {
                        session.setTalkingState(false)
                    }
                }
            }

            override fun onDrawerOpened(drawerView: View) {
                invalidateOptionsMenu()
            }
        }

        drawerLayout.addDrawerListener(drawerToggle)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        if (savedInstanceState == null) {
            val startIntent = intent
            if (startIntent != null && startIntent.hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(startIntent.getIntExtra(EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_FAVOURITES))
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
        }

        // If we're given a Mumble URL to show, open up a server edit fragment.
        if (intent != null && Intent.ACTION_VIEW == intent.action) {
            val url = intent.dataString
            try {
                val server = MumbleURLParser.parseURL(url)

                // Open a dialog prompting the user to connect to the Mumble server.
                val fragment = ServerEditFragment.createServerEditDialog(
                    this, server, ServerEditFragment.Action.CONNECT_ACTION, true,
                )
                fragment.show(supportFragmentManager, "url_edit")
            } catch (e: MalformedURLException) {
                Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }

        volumeControlStream = if (settings.isHandsetMode()) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

        if (savedInstanceState == null) {
            // Got no instance bundle: this is run only on real app startup -- not when Android
            // recreates the activity on configuration change, like screen rotation.
            if (settings.isFirstRun()) {
                showFirstRunGuide()
            } else {
                StartupAction().execute(this)
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onResume() {
        super.onResume()
        bindService(Intent(this, MumlaService::class.java), connection, 0)
    }

    override fun onPause() {
        super.onPause()
        errorDialog?.dismiss()
        connectingDialog?.dismiss()

        boundService?.let { service ->
            for (fragment in serviceFragments) {
                fragment.setServiceBound(false)
            }
            service.unregisterObserver(observer)
            service.setSuppressNotifications(false)
        }
        unbindService(connection)
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        db.close()
        super.onDestroy()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val disconnectButton = menu.findItem(R.id.action_disconnect)
        disconnectButton.isVisible = boundService?.isConnected == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.mumla, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        if (item.itemId == R.id.action_disconnect) {
            boundService?.disconnect()
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val service = boundService
        if (service != null && keyCode == settings.getPushToTalkKey()) {
            service.onTalkKeyDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val service = boundService
        if (service != null && keyCode == settings.getPushToTalkKey()) {
            service.onTalkKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        drawerLayout.closeDrawers()
        loadDrawerFragment(id.toInt())
    }

    private fun showFirstRunGuide() {
        // Prompt the user to generate a certificate.
        if (settings.isUsingCertificate()) {
            settings.setFirstRun(false)
            return
        }
        var msg = getString(R.string.first_run_generate_certificate)
        if (BuildConfig.FLAVOR == "donation") {
            msg = getString(R.string.donation_thanks) + "\n\n" + msg
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_generate_certificate_title)
            .setMessage(msg)
            .setPositiveButton(R.string.generate) { _, _ ->
                val generateTask = object : MumlaCertificateGenerateTask(this@MumlaActivity) {
                    override fun onPostExecute(result: DatabaseCertificate?) {
                        super.onPostExecute(result)
                        if (result != null) settings.setDefaultCertificateId(result.id)
                    }
                }
                generateTask.execute()
                settings.setFirstRun(false)
            }
            .show()
    }

    /** Loads a fragment from the drawer. */
    private fun loadDrawerFragment(fragmentId: Int) {
        val args = Bundle()
        val fragmentClass: Class<out Fragment> = when (fragmentId) {
            DrawerAdapter.ITEM_SERVER -> ChannelFragment::class.java
            DrawerAdapter.ITEM_INFO -> ServerInfoFragment::class.java
            DrawerAdapter.ITEM_ACCESS_TOKENS -> {
                val connectedServer = boundService?.targetServer ?: return
                args.putLong("server", connectedServer.id)
                args.putStringArrayList("access_tokens", ArrayList(db.getAccessTokens(connectedServer.id)))
                AccessTokenFragment::class.java
            }
            DrawerAdapter.ITEM_PINNED_CHANNELS -> {
                args.putBoolean("pinned", true)
                ChannelFragment::class.java
            }
            DrawerAdapter.ITEM_FAVOURITES -> FavouriteServerListFragment::class.java
            DrawerAdapter.ITEM_PUBLIC -> PublicServerListFragment::class.java
            DrawerAdapter.ITEM_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }
            else -> return
        }
        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, fragmentClass.name)
            .apply { arguments = args }
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, fragment, fragmentClass.name)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .commit()
        requireNotNull(supportActionBar).title = drawerAdapter.getItemWithId(fragmentId).title
    }

    override fun connectToServer(server: Server) {
        serverPendingPerm = server
        connectToServerWithPerm()
    }

    fun connectToServerWithPerm() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSIONS_REQUEST_RECORD_AUDIO,
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permPostNotificationsAsked) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSIONS_REQUEST_POST_NOTIFICATIONS,
                )
                return
            }
        }

        val server = serverPendingPerm
        if (server == null) {
            Log.w(TAG, "No pending server after getting permissions")
            return
        }
        serverPendingPerm = null

        // Check if we're already connected to a server; if so, inform user.
        val service = boundService
        if (service != null && service.isConnected) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reconnect_dialog_message)
                .setPositiveButton(R.string.connect) { _, _ ->
                    // Register an observer to reconnect to the new server once disconnected.
                    service.registerObserver(object : HumlaObserver() {
                        override fun onDisconnected(e: HumlaException?) {
                            connectToServer(server)
                            service.unregisterObserver(this)
                        }
                    })
                    service.disconnect()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (settings.isTorEnabled()) {
            if (!OrbotHelper.isOrbotInstalled(this)) {
                settings.disableTor()
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.orbot_not_installed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            } else if (!isPortOpen(HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT, 2000)) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.orbot_tor_failed, HumlaConnection.TOR_PORT))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
        }

        ServerConnectTask(this, db).execute(server)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isEmpty()) {
            return
        }

        when (requestCode) {
            PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm()
                } else {
                    Toast.makeText(this, getString(R.string.grant_perm_microphone), Toast.LENGTH_LONG).show()
                }
            }
            PERMISSIONS_REQUEST_POST_NOTIFICATIONS -> {
                permPostNotificationsAsked = true
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    // This is inspired by https://stackoverflow.com/a/34612503
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                        Toast.makeText(this, getString(R.string.grant_perm_notifications), Toast.LENGTH_LONG).show()
                    }
                }
                connectToServerWithPerm()
            }
        }
    }

    private fun isPortOpen(host: String, port: Int, timeout: Int): Boolean {
        val open = AtomicBoolean(false)
        try {
            val thread = Thread {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), timeout)
                    socket.close()
                    open.set(true)
                } catch (e: Exception) {
                    Log.d(TAG, "isPortOpen() run()$e")
                }
            }
            thread.start()
            thread.join()
            return open.get()
        } catch (e: Exception) {
            Log.d(TAG, "isPortOpen() $e")
        }
        return false
    }

    override fun connectToPublicServer(server: PublicServer) {
        val usernameField = EditText(this)
        usernameField.hint = settings.getDefaultUsername()
        val layout = FrameLayout(this)
        layout.addView(usernameField)
        val horizontalPadding = resources.getDimension(R.dimen.padding_medium).toInt()
        layout.setPadding(horizontalPadding, 0, horizontalPadding, 0)
        MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setTitle(R.string.connectToServer)
            .setPositiveButton(R.string.connect) { _, _ ->
                val typed = usernameField.text.toString()
                server.username = if (typed.isEmpty()) settings.getDefaultUsername() else typed
                connectToServer(server)
            }
            .show()
    }

    private fun setStayAwake(stayAwake: Boolean) {
        if (stayAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Updates the activity to represent the connection state of the given service.
     * Will show reconnecting dialog if reconnecting, dismiss otherwise, etc.
     * Basically, this service will do catch-up if the activity wasn't bound to receive
     * connection state updates.
     *
     * @param service A bound IHumlaService.
     */
    private fun updateConnectionState(service: IHumlaService) {
        connectingDialog?.dismiss()
        errorDialog?.dismiss()

        val mumlaService = boundService ?: return
        when (mumlaService.connectionState) {
            se.lublin.humla.HumlaService.ConnectionState.CONNECTING -> {
                val server = service.targetServer
                // SRV lookup is done later, so we no longer show the port in the connection
                // progress dialog (and only the configured hostname)
                connectingDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.connecting_to_server, server.host) + if (settings.isTorEnabled()) " (Tor)" else "")
                    .setView(R.layout.dialog_progress)
                    .setCancelable(true)
                    .setOnCancelListener {
                        mumlaService.disconnect()
                        Toast.makeText(this, R.string.cancelled, Toast.LENGTH_SHORT).show()
                    }
                    .create()
                connectingDialog?.show()
            }
            se.lublin.humla.HumlaService.ConnectionState.CONNECTION_LOST -> {
                // Only bother the user if the error hasn't already been shown.
                if (!mumlaService.isErrorShown) {
                    val builder = MaterialAlertDialogBuilder(this)
                    builder.setTitle(getString(R.string.connectionRefused) + if (settings.isTorEnabled()) " (Tor)" else "")
                    val error = mumlaService.connectionError
                    if (error != null && mumlaService.isReconnecting) {
                        builder.setMessage(
                            error.message + "\n\n" + getString(
                                R.string.attempting_reconnect,
                                error.cause?.message ?: "unknown",
                            ),
                        )
                        builder.setPositiveButton(R.string.cancel_reconnect) { _, _ ->
                            boundService?.let {
                                it.cancelReconnect()
                                it.markErrorShown()
                            }
                        }
                    } else if (error != null &&
                        error.reason == HumlaException.HumlaDisconnectReason.REJECT &&
                        (error.reject.type == Mumble.Reject.RejectType.WrongUserPW ||
                            error.reject.type == Mumble.Reject.RejectType.WrongServerPW)
                    ) {
                        val passwordField = EditText(this)
                        passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        passwordField.setHint(R.string.password)
                        builder.setTitle(R.string.invalid_password)
                        builder.setMessage(error.message)
                        builder.setView(passwordField)
                        builder.setPositiveButton(R.string.reconnect) { _, _ ->
                            val server1 = boundService?.targetServer ?: return@setPositiveButton
                            server1.password = passwordField.text.toString()
                            if (server1.isSaved) {
                                db.updateServer(server1)
                            }
                            connectToServer(server1)
                        }
                        builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                            boundService?.markErrorShown()
                        }
                    } else {
                        builder.setMessage(error?.message ?: getString(R.string.unknown))
                        builder.setPositiveButton(android.R.string.ok) { _, _ ->
                            boundService?.markErrorShown()
                        }
                    }
                    builder.setCancelable(false)
                    errorDialog = builder.show()
                }
            }
            else -> Unit
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    override fun getService(): IMumlaService? = boundService

    override fun getDatabase(): MumlaDatabase = db

    override fun addServiceFragment(fragment: HumlaServiceFragment) {
        serviceFragments.add(fragment)
    }

    override fun removeServiceFragment(fragment: HumlaServiceFragment) {
        serviceFragments.remove(fragment)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            Settings.PREF_STAY_AWAKE -> setStayAwake(settings.shouldStayAwake())
            Settings.PREF_HANDSET_MODE ->
                volumeControlStream = if (settings.isHandsetMode()) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        }
    }

    override fun isConnected(): Boolean = boundService?.isConnected == true

    override fun getConnectedServerName(): String {
        val service = boundService
        if (service != null && service.isConnected) {
            val server = service.targetServer
            return if (server.name.isEmpty()) server.host else server.name
        }
        if (BuildConfig.DEBUG) {
            throw RuntimeException("getConnectedServerName should only be called if connected!")
        }
        return ""
    }

    override fun onServerEdited(action: ServerEditFragment.Action, server: Server) {
        when (action) {
            ServerEditFragment.Action.ADD_ACTION -> {
                db.addServer(server)
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            ServerEditFragment.Action.EDIT_ACTION -> {
                db.updateServer(server)
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES)
            }
            ServerEditFragment.Action.CONNECT_ACTION -> connectToServer(server)
        }
    }

    companion object {
        private val TAG: String = MumlaActivity::class.java.name

        /** If specified, the provided integer drawer fragment ID is shown when the activity is created. */
        const val EXTRA_DRAWER_FRAGMENT = "drawer_fragment"

        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
        private const val PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2
    }
}
```

- [ ] **Step 2: Delete the Java file and build**

```bash
cd /home/becker/git/mumla && git rm -q app/src/main/java/se/lublin/mumla/app/MumlaActivity.java
```

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug assembleGoogDebug`
Expected: `BUILD SUCCESSFUL` for both flavors (`StartupAction` differs per flavor; both must still resolve). Fix only accessor spellings / nullability the compiler reports against the Java neighbours; do not change the neighbours.

- [ ] **Step 3: Run the green gate and commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt
git commit -m "refactor: convert MumlaActivity to kotlin"
```

---

### Task 9: RECORD_AUDIO rationale and POST_NOTIFICATIONS flow (P3)

**Files:**
- Create: `app/src/main/java/se/lublin/mumla/app/ConnectPermissionFlow.kt`
- Modify: `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt` (Task 8 version: `connectToServerWithPerm`, `onRequestPermissionsResult`, companion constants)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/se/lublin/mumla/app/ConnectPermissionFlowTest.kt`

**Interfaces:**
- Consumes: `ContextCompat.checkSelfPermission`, `ActivityCompat.shouldShowRequestPermissionRationale`, `ActivityResultContracts.RequestPermission`.
- Produces: `class ConnectPermissionFlow(context: Context, needsRationale: (String) -> Boolean, sdkInt: Int = Build.VERSION.SDK_INT)` with `sealed interface Step { data class RequestRecordAudio(val showRationale: Boolean); data object RequestPostNotifications; data object Proceed }`, `var postNotificationsAsked: Boolean`, `fun nextStep(): Step`.

Behavior (same order as today's `connectToServerWithPerm`, `MumlaActivity.java:563-582`, plus the rationale): (1) without `RECORD_AUDIO` → ask; if the system says a rationale should be shown (user denied once before) show a dialog explaining why first; (2) on API ≥ 33, without `POST_NOTIFICATIONS` and not yet asked this process lifetime → ask once; (3) otherwise proceed. Denying notifications never blocks connecting.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/se/lublin/mumla/app/ConnectPermissionFlowTest.kt`:

```kotlin
package se.lublin.mumla.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ConnectPermissionFlowTest {
    private lateinit var app: Application
    private var rationaleFor: Set<String> = emptySet()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun flow(sdkInt: Int = 35) =
        ConnectPermissionFlow(app, needsRationale = { it in rationaleFor }, sdkInt = sdkInt)

    @Test
    fun asksForRecordAudioFirstWithoutRationale() {
        assertThat(flow().nextStep())
            .isEqualTo(ConnectPermissionFlow.Step.RequestRecordAudio(showRationale = false))
    }

    @Test
    fun asksForRecordAudioWithRationaleAfterAPreviousDenial() {
        rationaleFor = setOf(Manifest.permission.RECORD_AUDIO)

        assertThat(flow().nextStep())
            .isEqualTo(ConnectPermissionFlow.Step.RequestRecordAudio(showRationale = true))
    }

    @Test
    fun asksForNotificationsOnceMicrophoneIsGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertThat(flow().nextStep()).isEqualTo(ConnectPermissionFlow.Step.RequestPostNotifications)
    }

    @Test
    fun proceedsWhenBothGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        assertThat(flow().nextStep()).isEqualTo(ConnectPermissionFlow.Step.Proceed)
    }

    @Test
    fun proceedsAfterNotificationsWereAskedAndDenied() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val flow = flow()
        assertThat(flow.nextStep()).isEqualTo(ConnectPermissionFlow.Step.RequestPostNotifications)

        flow.postNotificationsAsked = true

        assertThat(flow.nextStep()).isEqualTo(ConnectPermissionFlow.Step.Proceed)
    }

    @Test
    fun neverAsksForNotificationsBelowApi33() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertThat(flow(sdkInt = 32).nextStep()).isEqualTo(ConnectPermissionFlow.Step.Proceed)
    }

    @Test
    fun microphoneStillComesFirstEvenIfNotificationsWereAsked() {
        val flow = flow()
        flow.postNotificationsAsked = true

        assertThat(flow.nextStep())
            .isEqualTo(ConnectPermissionFlow.Step.RequestRecordAudio(showRationale = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.app.ConnectPermissionFlowTest'`
Expected: compilation FAILS with `Unresolved reference: ConnectPermissionFlow`.

- [ ] **Step 3: Implement the flow**

Create `app/src/main/java/se/lublin/mumla/app/ConnectPermissionFlow.kt`:

```kotlin
package se.lublin.mumla.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Decides which runtime permission to ask for before connecting (spec P3):
 * RECORD_AUDIO (with a rationale dialog after a previous denial), then POST_NOTIFICATIONS once
 * per process on API 33+, then proceed. Pure decision logic; MumlaActivity owns the launchers.
 */
class ConnectPermissionFlow(
    private val context: Context,
    private val needsRationale: (permission: String) -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    sealed interface Step {
        data class RequestRecordAudio(val showRationale: Boolean) : Step
        data object RequestPostNotifications : Step
        data object Proceed : Step
    }

    /** Set by the caller once the notification permission dialog has been answered (either way). */
    var postNotificationsAsked: Boolean = false

    fun nextStep(): Step {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            return Step.RequestRecordAudio(showRationale = needsRationale(Manifest.permission.RECORD_AUDIO))
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            !postNotificationsAsked &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return Step.RequestPostNotifications
        }
        return Step.Proceed
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.app.ConnectPermissionFlowTest'`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Add the rationale strings**

Append to `app/src/main/res/values/strings.xml` after `grant_perm_bluetooth` (Task 7):

```xml
    <string name="record_audio_rationale_title">Microphone access</string>
    <string name="record_audio_rationale_message">Mumla sends your voice to the server through the microphone. Without this permission you can only listen.</string>
```

- [ ] **Step 6: Wire MumlaActivity to the flow with activity-result launchers**

In `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt`:

Add imports:

```kotlin
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
```

Remove the now-unused imports `android.content.pm.PackageManager` and `android.os.Build`.

Replace the two fields

```kotlin
    private var serverPendingPerm: Server? = null
    private var permPostNotificationsAsked = false
```

with

```kotlin
    private var serverPendingPerm: Server? = null
    private lateinit var permissionFlow: ConnectPermissionFlow

    private val recordAudioRequester: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                connectToServerWithPerm()
            } else {
                Toast.makeText(this, getString(R.string.grant_perm_microphone), Toast.LENGTH_LONG).show()
            }
        }

    private val postNotificationsRequester: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionFlow.postNotificationsAsked = true
            if (!granted &&
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                Toast.makeText(this, getString(R.string.grant_perm_notifications), Toast.LENGTH_LONG).show()
            }
            connectToServerWithPerm()
        }
```

In `onCreate`, right after `settings = Settings.getInstance(this)`:

```kotlin
        permissionFlow = ConnectPermissionFlow(this, needsRationale = { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        })
```

Replace the beginning of `connectToServerWithPerm()` (everything from `if (ContextCompat.checkSelfPermission(...RECORD_AUDIO)` through the end of the `POST_NOTIFICATIONS` block) with:

```kotlin
    fun connectToServerWithPerm() {
        when (val step = permissionFlow.nextStep()) {
            is ConnectPermissionFlow.Step.RequestRecordAudio -> {
                if (step.showRationale) {
                    showRecordAudioRationale()
                } else {
                    recordAudioRequester.launch(Manifest.permission.RECORD_AUDIO)
                }
                return
            }
            ConnectPermissionFlow.Step.RequestPostNotifications -> {
                postNotificationsRequester.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            ConnectPermissionFlow.Step.Proceed -> Unit
        }

        val server = serverPendingPerm
        // ... the rest of the method is unchanged from Task 8 ...
```

Add the rationale dialog next to `showFirstRunGuide()`:

```kotlin
    private fun showRecordAudioRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.record_audio_rationale_title)
            .setMessage(R.string.record_audio_rationale_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                recordAudioRequester.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                Toast.makeText(this, getString(R.string.grant_perm_microphone), Toast.LENGTH_LONG).show()
            }
            .show()
    }
```

Delete the whole `onRequestPermissionsResult(...)` override and the two constants `PERMISSIONS_REQUEST_RECORD_AUDIO` / `PERMISSIONS_REQUEST_POST_NOTIFICATIONS` from the companion object. `ContextCompat` stays imported only if still used elsewhere in the file (it is not; remove the import too).

- [ ] **Step 7: Build, run the green gate, commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/app/ConnectPermissionFlow.kt app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt app/src/main/res/values/strings.xml app/src/test/java/se/lublin/mumla/app/ConnectPermissionFlowTest.kt
git commit -m "feat: add microphone rationale and notification permission flow"
```

---

### Task 10: One-time battery-optimization exemption offer (P4)

**Files:**
- Create: `app/src/main/java/se/lublin/mumla/app/BatteryOptimizationPrompt.kt`
- Modify: `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt` (`observer.onConnected`)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/se/lublin/mumla/app/BatteryOptimizationPromptTest.kt`

**Interfaces:**
- Consumes: `Settings.isBatteryOptimizationAsked()/setBatteryOptimizationAsked()` (Task 1); `PowerManager.isIgnoringBatteryOptimizations`; manifest permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Task 2).
- Produces: `class BatteryOptimizationPrompt(context: Context, settings: Settings)` with `fun shouldOffer(): Boolean`, `fun markOffered()`, `fun requestIntent(): Intent` (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI), `fun fallbackIntent(): Intent` (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, for OEM builds without the request dialog).

Why: Doze and OEM battery managers suspend the process while the screen is off, which is the platform half of the "microphone silent while the screen is off" report (spec §1 goal 1). The exemption is offered exactly once, right after the first successful connection (that is when the user has seen the app work and is most likely to accept), is dismissable ("Not now"), and is never repeated — `pref battery_optimization_asked` is set as soon as the dialog is shown, regardless of the answer.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/se/lublin/mumla/app/BatteryOptimizationPromptTest.kt`:

```kotlin
package se.lublin.mumla.app

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.Settings

@RunWith(RobolectricTestRunner::class)
class BatteryOptimizationPromptTest {
    private lateinit var app: Application
    private lateinit var settings: Settings
    private lateinit var prompt: BatteryOptimizationPrompt

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        settings = Settings.getInstance(app)
        prompt = BatteryOptimizationPrompt(app, settings)
    }

    private fun setIgnoring(value: Boolean) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, value)
    }

    @Test
    fun offersWhenNotExemptAndNeverAsked() {
        setIgnoring(false)

        assertThat(prompt.shouldOffer()).isTrue()
    }

    @Test
    fun doesNotOfferWhenAlreadyExempt() {
        setIgnoring(true)

        assertThat(prompt.shouldOffer()).isFalse()
    }

    @Test
    fun offersOnlyOnce() {
        setIgnoring(false)

        prompt.markOffered()

        assertThat(prompt.shouldOffer()).isFalse()
        assertThat(BatteryOptimizationPrompt(app, Settings.getInstance(app)).shouldOffer()).isFalse()
    }

    @Test
    fun requestIntentTargetsThisPackage() {
        val intent = prompt.requestIntent()

        assertThat(intent.action).isEqualTo(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(intent.data.toString()).isEqualTo("package:" + app.packageName)
    }

    @Test
    fun fallbackIntentOpensTheOptimizationList() {
        assertThat(prompt.fallbackIntent().action)
            .isEqualTo(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.app.BatteryOptimizationPromptTest'`
Expected: compilation FAILS with `Unresolved reference: BatteryOptimizationPrompt`.

- [ ] **Step 3: Implement the prompt logic**

Create `app/src/main/java/se/lublin/mumla/app/BatteryOptimizationPrompt.kt`:

```kotlin
package se.lublin.mumla.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import se.lublin.mumla.Settings

/**
 * Offers the battery-optimization exemption once, after the first successful connection
 * (spec P4). The activity shows the dialog; this class decides whether and builds the intents.
 */
class BatteryOptimizationPrompt(
    private val context: Context,
    private val settings: Settings,
) {
    fun shouldOffer(): Boolean {
        if (settings.isBatteryOptimizationAsked()) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Call as soon as the dialog is shown, so it is shown at most once. */
    fun markOffered() {
        settings.setBatteryOptimizationAsked(true)
    }

    /** System dialog asking to exempt this app (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    fun requestIntent(): Intent =
        Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName))

    /** The full list of optimized apps, for devices whose ROM lacks the request dialog. */
    fun fallbackIntent(): Intent = Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.app.BatteryOptimizationPromptTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Add the dialog strings**

Append to `app/src/main/res/values/strings.xml` after `record_audio_rationale_message`:

```xml
    <string name="battery_optimization_title">Keep Mumla running with the screen off</string>
    <string name="battery_optimization_message">Android may pause Mumla while the screen is off, which interrupts voice and Bluetooth audio. Exclude Mumla from battery optimization? You can change this later in the system settings.</string>
    <string name="battery_optimization_exclude">Exclude</string>
    <string name="battery_optimization_not_now">Not now</string>
```

- [ ] **Step 6: Show the dialog after the first successful connection**

In `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt`, add the import:

```kotlin
import android.content.ActivityNotFoundException
```

In `observer.onConnected()`, after `boundService?.let { updateConnectionState(it) }`:

```kotlin
            maybeOfferBatteryOptimization()
```

Add next to `showRecordAudioRationale()`:

```kotlin
    private fun maybeOfferBatteryOptimization() {
        val prompt = BatteryOptimizationPrompt(this, settings)
        if (!prompt.shouldOffer()) return
        prompt.markOffered()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_exclude) { _, _ ->
                try {
                    startActivity(prompt.requestIntent())
                } catch (e: ActivityNotFoundException) {
                    startActivity(prompt.fallbackIntent())
                }
            }
            .setNegativeButton(R.string.battery_optimization_not_now, null)
            .show()
    }
```

- [ ] **Step 7: Build, run the green gate and lint, commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug`
Expected: `BUILD SUCCESSFUL`. Lint's `BatteryLife` check is a warning tied to the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission; `abortOnError` only fails on errors, and the spec mandates this flow (P4), so do not disable the check.

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/app/BatteryOptimizationPrompt.kt app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt app/src/main/res/values/strings.xml app/src/test/java/se/lublin/mumla/app/BatteryOptimizationPromptTest.kt
git commit -m "feat: offer battery optimization exemption after first connection"
```

---

## Hooks into MumlaService and MumlaActivity (complete list)

`MumlaService.java` is owned by stream A; every line stream P adds there is listed here (each hook is a call into a stream-P class, ≤ 10 lines):

| # | Task | Location (today's line numbers) | Lines added | What |
|---|---|---|---|---|
| S1 | 4 | field block after `mReconnectNotification` (`:77`) | 2 | `private MumlaMediaSession mMediaSession;` |
| S2 | 4 | `onCreate()` after `mTalkReceiver = new TalkBroadcastReceiver(this);` (`:318`) | 2 | `mMediaSession = new MumlaMediaSession(this, new HumlaMediaKeyTarget(this), mSettings); mMediaSession.attach(this);` |
| S3 | 4 | `onDestroy()` before `unregisterObserver(mObserver);` (`:345`) | 1 | `mMediaSession.detach(this);` |
| S4 | 7 | `onConnectionSynchronized()` end (`:388`) | 4 | apply `pref_bluetooth_sco` (+ permission check) via `enableBluetoothSco()` |
| S5 | 7 | `onSharedPreferenceChanged()` new `case Settings.PREF_BLUETOOTH_SCO` (`:476`) | 9 | enable/disable SCO while synchronized |
| — | 7 | imports | 1 | `import se.lublin.mumla.channel.BluetoothScoToggle;` |

`MumlaActivity` is owned by stream P; the touch points inside it, for reviewers:

| # | Task | Method | What |
|---|---|---|---|
| A1 | 8 | whole file | Kotlin conversion, behavior preserved |
| A2 | 9 | fields, `onCreate`, `connectToServerWithPerm`, new `showRecordAudioRationale` | `ConnectPermissionFlow` + `RequestPermission` launchers replace `ActivityCompat.requestPermissions`/`onRequestPermissionsResult` |
| A3 | 10 | `observer.onConnected`, new `maybeOfferBatteryOptimization` | one-time battery dialog |
| — | — | `onKeyDown`/`onKeyUp` | unchanged: the user-chosen PTT key keeps working in the foreground; media keys not consumed here fall through to `MumlaMediaSession` |

## Cross-stream touches

| File (owner) | Task | Lines | Why |
|---|---|---|---|
| `gradle/libs.versions.toml` (F) | 4 | +2 (`androidx-media = "1.8.0"` version + library alias) | `MediaSessionCompat` lives in `androidx.media:media`; the spec mandates `MediaSessionCompat` (P1) |
| `app/build.gradle` (F) | 4 | +1 (`implementation libs.androidx.media`) | same |
| `app/src/main/java/se/lublin/mumla/service/MumlaService.java` (A) | 4, 7 | S1–S5 above, 19 lines total, ≤ 10 per hook | session lifecycle and Bluetooth preference must be applied by the service, which owns the connection |
| `app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java:188` (A) — **recommended, not done by P** | 2 | 1 (`FOREGROUND_SERVICE_TYPE_MICROPHONE \| FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`) | the manifest now declares both types; passing both at `startForeground` lets Android keep delivering media buttons to a backgrounded app on API 34+. The declared superset is valid without it, so P's build stays green either way; stream A applies it when it rewrites `startForeground` (A6). |
| `libraries/humla/src/main/AndroidManifest.xml:26-32` (F/A) — **recommended, not done by P** | 2 | 1 (`android:exported="false"` on `.HumlaService`) | the merged manifest still exports `se.lublin.humla.HumlaService` with an intent-filter for `se.lublin.humla.ACTION_CONNECT`; Mumla starts only `MumlaService` explicitly. Out of P's ownership; flagged for the audit. |

`res/values/strings.xml`, `res/values/preference.xml`, `res/values/preference_notranslate.xml` are shared string sources per spec §2 ("User-facing strings go to …"); stream P only appends.

## Integration notes (for the A → B → P → D rebase, spec §5)

- Stream A introduces `HumlaService.EXTRAS_BLUETOOTH_WANTED` (spec §4) and `bluetoothScoWanted`. After rebasing onto A, hook S4 is equivalent to passing `EXTRAS_BLUETOOTH_WANTED = settings.isBluetoothScoEnabled()` in `ServerConnectTask`; keep S4 (it also carries the permission check) unless A's `configureExtras` already checks `BLUETOOTH_CONNECT`, in which case replace S4 by the one-line extra in `ServerConnectTask.doInBackground` and delete S4.
- Stream A's `SessionState.ConnectionLost` keeps foreground/wake-lock across auto-reconnect; `MumlaMediaSession` deactivates on `onDisconnected` and reactivates on `onConnected`, which is correct for both today's and A's observer semantics (media keys have no meaningful target while disconnected).
- Stream A's A4 changes `enableBluetoothSco()` to set the wanted flag and use `setCommunicationDevice`; P's calls in S4/S5 keep working unchanged.
- Stream B's `CaptureState.Silenced` warning (B7) complements the battery prompt (P4): both target "microphone silent while the screen is off".

## Self-review against the spec

- **P1** MediaSession push-to-talk: Tasks 3, 4 (session active while connected, `HEADSETHOOK`/`MEDIA_PLAY_PAUSE`/AVRCP `PLAY`/`PAUSE`, PTT toggle in PTT mode, mute otherwise), Task 5 (configurable in settings), screen-off delivery via active session with `STATE_PLAYING` + `mediaPlayback` service type (Task 2). ✔
- **P2** Bluetooth as persistent setting `pref_bluetooth_sco` default off, menu toggle writes the preference, initialized on connect: Tasks 1, 7 (hooks S4/S5). ✔
- **P3** `BLUETOOTH_CONNECT` before SCO (Task 7), `POST_NOTIFICATIONS` flow kept and `RECORD_AUDIO` rationale (Task 9). ✔
- **P4** battery exemption offered once, dismissable, after the first successful connection via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Task 10 (+ permission in Task 2). ✔
- **P5** manifest `foregroundServiceType="microphone|mediaPlayback"`, `exported` audit, legacy `BLUETOOTH` removed: Task 2. ✔
- **Global constraints**: all new files Kotlin; both non-trivially modified Java files converted first as their own `refactor:` commits (Tasks 6, 8); no new `AsyncTask`/`Thread`; every behavior change test-first on the JVM; commit messages are trailer-free Conventional Commits; each task ends with the green gate; API < 31 branches deleted in the touched files (`registerReceiver` SDK branch, `requestLegacyExternalStorage`, `BROADCAST_CLOSE_SYSTEM_DIALOGS` permission); `androidx.media` is Apache-2.0 (a first-party AndroidX artifact, not a third-party component for `NOTICE.md`).
- **Type consistency** (names used across tasks): `Settings.PREF_BLUETOOTH_SCO`/`isBluetoothScoEnabled()`/`setBluetoothScoEnabled()`, `Settings.PREF_MEDIA_BUTTON_ACTION`/`getMediaButtonAction()`, `Settings.isBatteryOptimizationAsked()`/`setBatteryOptimizationAsked()` (Task 1 → 3, 4, 7, 10); `MediaKeyTarget`, `MediaKeyHandler.onKeyEvent(KeyEvent)`, `HumlaMediaKeyTarget(IHumlaService)` (Task 3 → 4); `MumlaMediaSession.attach/detach/activate/deactivate/isActive/sessionToken/callback` (Task 4 → hooks S1–S3); `BluetoothScoToggle.toggle()/onPermissionResult()/isEnabled/hasPermission()` and `Result.Enabled/Disabled/PermissionNeeded` (Task 7 → S4/S5); `ConnectPermissionFlow.Step.RequestRecordAudio(showRationale)/RequestPostNotifications/Proceed`, `postNotificationsAsked` (Task 9); `BatteryOptimizationPrompt.shouldOffer()/markOffered()/requestIntent()/fallbackIntent()` (Task 10).

## Open questions

1. `androidx.media` 1.8.0 is deprecated upstream in favor of `androidx.media3` (`MediaSession` + `MediaSessionService`). The spec names `MediaSessionCompat`; `MumlaMediaSession` isolates the dependency to one file so a later switch to media3 is a single-file change plus the catalog entry.
2. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted by Google Play policy for the `goog` flavor (allowed for apps whose core function breaks under Doze, which a voice client is; a policy declaration may be needed at release). If Play rejects it, keep the dialog and use only `fallbackIntent()` (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, no permission) in the `goog` flavor.
3. Whether Foundation exposed `Settings.kt` accessors as functions (`isFirstRun()`) or properties (`isFirstRun`) determines the spelling in Tasks 6–10; the plan uses the Java-style function spelling and notes the substitution.
4. `MumlaCertificateGenerateTask` is still an `AsyncTask` subclass used from `MumlaActivity.showFirstRunGuide`; the conversion keeps that call. The spec's end state has no `AsyncTask` in the tree, but the file is not in P's ownership table (nor explicitly in F's); the stream that removes it must update the two-line call site in `MumlaActivity.kt`.
5. `libraries/humla/src/main/AndroidManifest.xml` exports `HumlaService`; recommended change listed under cross-stream touches, owner to confirm.
