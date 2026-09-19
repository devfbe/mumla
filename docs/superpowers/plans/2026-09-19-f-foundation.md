# Stream F — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Mumla's build, dependencies, crypto, native code, test infrastructure, CI and README up to date so that streams A, B, D and P can branch from a green, Kotlin-first, CMake-built repository.

**Architecture:** The `libraries/humla` git submodule is inlined into the main repository (F2); the build moves to Gradle 9.7.1 / AGP 9.4.1 with a version catalog and AGP's built-in Kotlin (F3); dependencies are updated, guava removed and protobuf generated at build time (F4); Spongycastle is replaced by stock BouncyCastle behind one small `Pkcs12Certificates` loader (F5); the ndk-build/javacpp native layer is replaced by one `CMakeLists.txt`, hand-written JNI and Kotlin `external fun` wrappers with fakeable interfaces (F6); CI runs inside the Nix dev shell (F7); the README is rewritten (F8).

**Tech Stack:** Gradle 9.7.1, AGP 9.4.1 (built-in Kotlin — the Kotlin compiler is the one AGP bundles, no separate KGP on the build classpath), JDK 21, Android SDK 36, NDK 29.0.14206865, SDK CMake 4.1.2, build-tools 36.1.0, JUnit 4.13.2, Robolectric 4.17, MockK 1.14.11, Truth 1.4.5, kotlinx-coroutines 1.11.0, BouncyCastle 1.86, protobuf 4.36.2 (+ protobuf-gradle-plugin 0.10.0), opus 1.6.1, speex 1.2.1, speexdsp 1.2.1, CELT 0.7.1 / 0.11.1.

**Spec:** `/home/becker/git/mumla/docs/superpowers/specs/2026-09-19-mumla-modernization.md` (§2 global constraints, §3.1 stream F, §5 ordering, §6 acceptance). The spec wins over this plan wherever they disagree.

## Global Constraints

Copied verbatim from the spec §2; every task's requirements implicitly include this section.

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

## Conventions used in this plan

- All paths are relative to `/home/becker/git/mumla` unless absolute.
- `GRADLE` below means `cd /home/becker/git/mumla && nix develop --command ./gradlew`.
- The **green gate** is `GRADLE assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`; every task's last verification step runs it.
- Lint is part of the gate **from Task 2 on** (Task 1 still builds the old AGP 8.13 / minSdk 21 tree and only moves files around). Both modules already set `lint { abortOnError = true }` and `.gitlab-ci.yml` has its `lintDebug` job commented out, so Task 2 is the first task that has to make lint pass; every later task then fixes only the findings it creates itself instead of piling them up for F7.
- Kotlin test sources live under `src/test/java/` (AGP's built-in Kotlin compiles `.kt` files in the `java` source directory, so no extra source set configuration is needed); Kotlin main sources likewise go under `src/main/java/`.
- Test names use backticks; run a single test class with `--tests 'fully.qualified.ClassName'`.
- Versions were verified on 2026-09-19 (see "Version research" at the end of this plan for URLs and findings).

## File structure (what is created or moved by this stream)

| Path | Responsibility |
|---|---|
| `gradle/libs.versions.toml` | single source of truth for plugin and dependency versions |
| `settings.gradle`, `build.gradle`, `gradle.properties` | root build wiring (plugin management, AGP aliases, JVM args) |
| `app/build.gradle`, `libraries/humla/build.gradle` | module builds (AGP 9 DSL, test options, CMake, protobuf) |
| `app/src/main/java/se/lublin/mumla/Settings.kt` | app preferences facade (replaces `Settings.java`) |
| `app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.kt` | certificate export via SAF only |
| `libraries/humla/src/main/java/se/lublin/humla/net/Pkcs12Certificates.kt` | the one place that reads PKCS#12 blobs (Mumble style and generated) |
| `libraries/humla/src/main/cpp/CMakeLists.txt` | the one native build file: opus, speex, speexdsp, celt 0.7/0.11, five JNI shared libs |
| `libraries/humla/src/main/cpp/jni_{opus,speex,speexdsp,celt7,celt11}.cpp` | hand-written JNI, thin pass-through |
| `libraries/humla/src/main/cpp/third_party/{opus,speex,speexdsp,celt-0.7.0,celt-0.11.0}` | git submodules at upstream release tags |
| `libraries/humla/src/main/cpp/celt-0.7.0-build/config.h`, `.../celt-0.11.0-build/config.h` | the pre-generated autoconf headers (moved from `src/main/jni`) |
| `libraries/humla/src/main/java/se/lublin/humla/audio/native/*.kt` | `external fun` wrappers + fakeable `*Api` interfaces |
| `libraries/humla/src/main/java/se/lublin/humla/audio/{PacketBytes,OpusDecoder,CELT7Decoder,CELT11Decoder,SpeexDecoder,SpeexJitterBuffer}.kt` | packet-copy helper and the Kotlin codec objects used by `AudioOutputSpeech.kt` |
| `libraries/humla/src/main/java/se/lublin/humla/audio/encoder/*.kt` | Kotlin encoders (converted from Java) |
| `NOTICE.md`, `README.md`, `.gitlab-ci.yml`, `flake.nix` | licensing, docs, CI, dev shell |

## Cross-stream touches

Every hook below is the minimum needed to keep the build green; the owning stream may reshape the code later.

| File (owner) | Lines today | Task | Why / what |
|---|---|---|---|
| `libraries/humla/src/main/java/se/lublin/humla/model/Server.java` (A) | 24, 187 | Task 5 | guava removal: `com.google.common.net.InetAddresses.isInetAddress` → `android.net.InetAddresses.isNumericAddress` (2 lines) |
| `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java` (A) | 79–82 | Task 7 | delete the static `Security.insertProviderAt(new org.spongycastle...BouncyCastleProvider(), 1)` block and the `java.security.Security` import; all call sites pass the provider explicitly (4 lines) |
| `libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java` (A) | 28, 516–521 | Task 7 | replace the inline `KeyStore.getInstance("PKCS12", new BouncyCastleProvider())` + `load` with `Pkcs12Certificates.load(mCertificate, mCertificatePassword)` (≈5 lines) |
| `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java` (P) | 67 | Task 7 | `import org.spongycastle.util.encoders.Hex` → `org.bouncycastle.util.encoders.Hex` (1 line) |
| `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java` (A) | 49, 356 | Task 9 | `CELT7.getBitstreamVersion()` → `CELT7Encoder.getBitstreamVersion()` (Java cannot import a package named `native`, so the Kotlin facade lives on the encoder) (2 lines) |
| `app/src/goog/java/se/lublin/mumla/app/StartupAction.java` (unowned, goog flavor only) | 207–216 | Task 5 | Play Billing 9 changed `ProductDetailsResponseListener` to deliver a `QueryProductDetailsResult` (≈4 lines) |
| `libraries/humla/src/main/java/se/lublin/humla/audio/encoder/{OpusEncoder,CELT7Encoder,CELT11Encoder,PreprocessingEncoder,ResamplingEncoder}.java` (B) | whole files | Task 8 | deleted and re-created as `.kt` with identical Java-visible constructors and methods: F6 puts the native wrappers in the package `se.lublin.humla.audio.native`, which Java cannot even name (`native` is a keyword) |
| `libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.java` (B) | whole file | Task 8 | same reason; re-created as `AudioOutputSpeech.kt`, same Java-visible API (`AudioOutput.java` compiles unchanged) |
| `libraries/humla/src/main/java/se/lublin/humla/audio/javacpp/{Opus,Speex,CELT7,CELT11}.java` (B) | whole files | Task 9 | javacpp is removed (spec §2); replaced by `audio/native/*.kt` and `audio/{PacketBytes,OpusDecoder,CELT7Decoder,CELT11Decoder,SpeexDecoder,SpeexJitterBuffer}.kt` |
| `libraries/humla/src/main/java/se/lublin/humla/audio/encoder/*.kt`, `audio/AudioOutputSpeech.kt` (B) | whole files | Task 9 | rewired from javacpp to the `*Api`/`*Native` wrappers; every constructor gains a trailing `@JvmOverloads` `api` parameter |
| `libraries/humla/src/main/cpp/**` (B after Foundation) | new files | Task 9 | spec §3 gives F the initial `CMakeLists.txt`; the five `jni_*.cpp` files and the third-party submodules come with it |
| `libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java` (B) | 47, 79 | Task 2 | lint `MissingPermission`: two `@RequiresPermission(RECORD_AUDIO)` annotations plus imports (4 lines) |
| `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java` (B) | 132 | Task 2 | lint `MissingPermission`: refuse to build the input when RECORD_AUDIO is not granted, which ends the annotation chain (6 lines) |
| `app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java` (A) | 190–195 | Task 2 | lint `Range`: `getColumnIndex` → `getColumnIndexOrThrow` (6 lines) |
| `app/src/main/java/se/lublin/mumla/service/MumlaService.java` (A) | 376–380 | Task 2 | lint `UnspecifiedRegisterReceiverFlag`: one `ContextCompat.registerReceiver(…, RECEIVER_EXPORTED)` replaces the `SDK_INT` branch (5 lines) |
| `app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java` (A) | 108–112 | Task 2 | same, with `RECEIVER_NOT_EXPORTED` (5 lines) |
| `app/src/main/java/se/lublin/mumla/service/MumlaReconnectNotification.java` (A) | 85–89, 135 | Task 2 | same, plus a POST_NOTIFICATIONS guard before `notify` (lint `MissingPermission`) (9 lines) |
| `app/src/main/java/se/lublin/mumla/service/MumlaMessageNotification.java` (D) | 107 | Task 2 | lint `MissingPermission`: POST_NOTIFICATIONS guard before `notify` (5 lines) |
| `app/src/main/AndroidManifest.xml` (P) | 33 | Task 2 | lint `ProtectedPermissions`: delete `BROADCAST_CLOSE_SYSTEM_DIALOGS`, whose only sender is unreachable at minSdk 31 (1 line) |
| `app/src/main/res/layout*/` (D/P) | 14 attributes in 10 files | Task 2 | lint `UseAppTint`: `android:tint` → `app:tint` on AppCompat image views |

Stream B branches from the post-F8 commit, so B1, B2, B9 and B11 are written against the handed-over Kotlin files — `PreprocessingEncoder.kt` with a `SpeexPreprocessApi` seam rather than `PreprocessingEncoder.java` with `Speex.SpeexPreprocessState`, `AudioOutputSpeech.kt`, `audio/native/*.kt` — not against the Java files listed above.

Not touched by this stream at those lines, although they contain `SDK_INT` checks below 31 (their owners delete them when they touch the files; where a file appears in the table above, this stream edits it only at the lines named there): `libraries/humla/.../net/HumlaTCP.java:85` (A), `app/.../service/MumlaService.java:531,537` (A), `app/.../service/MumlaHotCorner.java:60,99` (P), `app/.../service/MumlaOverlay.java:198` (P), `app/.../service/Mumla*Notification.java` (A/D), `app/.../channel/ChannelChatFragment.java:182` (D), `app/.../app/MumlaActivity.java:573` (P).

---

### Task 1: F2 — Inline the humla submodule

**Files:**
- Delete: `.gitmodules` entry `libraries/humla`, `.git/modules/libraries/humla`
- Create (copied tree): `libraries/humla/**` (ordinary files, no `.git`)
- Delete inside the copy: `libraries/humla/.gitmodules`, `libraries/humla/.gitlab-ci.yml`, `libraries/humla/gradlew`, `libraries/humla/gradlew.bat`, `libraries/humla/gradle/`, `libraries/humla/libs/`
- Modify: `libraries/humla/build.gradle:37-43` (spongycastle jars), `.gitlab-ci.yml:24-35`
- Re-add as submodules of the main repo: `libraries/humla/src/main/jni/opus`, `.../speex`, `.../celt-0.7.0-src`, `.../celt-0.11.0-src`

**Interfaces:**
- Consumes: today's submodule state (recorded in step 1).
- Produces: `libraries/humla` as a plain directory; four submodules registered in the root `.gitmodules` at the exact commits `65471dd567beb6b1156dc292858c0a28ca55ca3e` (opus v1.1), `a6d05eb5ff9d5062852cdf7df574bec728921ef9` (speex, pre-split tree), `6c79a9325c328f86fa048bf124ff6a8912a60a3e` (celt v0.7.1), `e3d39fec7c44d1841e817d3b1986bfdc4d0863a9` (celt v0.11.1). Later tasks rely on these paths until Task 9 moves them.

Background: `libs/humla-spongycastle` (branch `pkcs12-keybag-fixes`, HEAD `12e49a23`) is the Spongycastle 1.51 fork whose only functional change is `73a06452 Added PKCS12 keybag handling in unencrypted data block` + `eb802529 DRY out SafeBag creation` in `prov/.../PKCS12KeyStoreSpi.java`: it teaches `engineLoad` to accept a plain `keyBag` (unencrypted PKCS#8 `PrivateKeyInfo`) inside an *unencrypted* `data` ContentInfo, which is exactly what Mumble's `Cert.cpp` writes with `PKCS12_create("", "Mumble Identity", pkey, x509, certs, -1, -1, 0, 0, 0)`. Between this task and Task 7 the build uses the unpatched Maven artifacts `com.madgag.spongycastle:{core,prov,pkix}:1.51.0.0` (verified present on Maven Central), so importing a Mumble-generated certificate does not work on the integration branch for those few commits; Task 7 restores it with stock BouncyCastle, which has had the same keyBag handling upstream since 1.52 (see Version research).

- [ ] **Step 1: Record the current submodule commits**

Run:
```bash
cd /home/becker/git/mumla && git submodule status --recursive
```
Expected (exactly these SHAs; the plan hard-codes them below):
```
 15839dc1ee844475b9cbeefe2c6adbb4916b60a8 libraries/humla (remotes/origin/minidns-113-g15839dc)
 12e49a233023855a9f58651a70bbe7e24bfd5f39 libraries/humla/libs/humla-spongycastle (heads/pkcs12-keybag-fixes)
 e3d39fec7c44d1841e817d3b1986bfdc4d0863a9 libraries/humla/src/main/jni/celt-0.11.0-src (v0.11.1)
 6c79a9325c328f86fa048bf124ff6a8912a60a3e libraries/humla/src/main/jni/celt-0.7.0-src (v0.7.1)
 65471dd567beb6b1156dc292858c0a28ca55ca3e libraries/humla/src/main/jni/opus (v1.1)
 a6d05eb5ff9d5062852cdf7df574bec728921ef9 libraries/humla/src/main/jni/speex (speex-1.2beta2-263-ga6d05eb)
```

- [ ] **Step 2: Export humla's tracked files without `.git` and without nested submodules**

`git archive` writes only tracked blobs of the given commit; gitlinks (submodules) and the `.git` file are not part of the archive.

```bash
set -eu
cd /home/becker/git/mumla
SCRATCH=/tmp/claude-1000/-home-becker-git-mumla/humla-inline
rm -rf "$SCRATCH" && mkdir -p "$SCRATCH"
git -C libraries/humla archive --format=tar HEAD | tar -x -C "$SCRATCH"
ls -a "$SCRATCH"
```
Expected: `. .. .gitignore .gitlab-ci.yml .gitmodules LICENSE README.md build.gradle gradle gradlew gradlew.bat protobuf-update-and-compile.sh src tools` (no `libs/`, no `src/main/jni/opus` etc.).

- [ ] **Step 3: Remove the submodule from the main repository**

```bash
cd /home/becker/git/mumla
git submodule deinit -f libraries/humla        # empties the working tree, unregisters submodule.libraries/humla in .git/config
git rm -f libraries/humla                      # removes the gitlink from the index and the entry from .gitmodules
rm -rf .git/modules/libraries                  # drops the cached repositories of humla and its nested submodules
git config --remove-section submodule.libraries/humla 2>/dev/null || true
cat .gitmodules; git status --short
```
Expected: `.gitmodules` is empty (still tracked, shown as ` M .gitmodules`), `D  libraries/humla` staged, `.git/modules` no longer contains `libraries`.

- [ ] **Step 4: Put the exported tree in place and drop humla's own repo scaffolding**

```bash
set -eu
cd /home/becker/git/mumla
# Each step runs in its own shell, so SCRATCH is assigned again here (it must never be empty:
# `cp -a /. libraries/humla/` would copy the whole root filesystem into the repository).
SCRATCH=/tmp/claude-1000/-home-becker-git-mumla/humla-inline
test -f "$SCRATCH/build.gradle"
mkdir -p libraries/humla
cp -a "$SCRATCH"/. libraries/humla/
rm -f  libraries/humla/.gitmodules libraries/humla/.gitlab-ci.yml libraries/humla/gradlew libraries/humla/gradlew.bat
rm -rf libraries/humla/gradle libraries/humla/libs
ls -la libraries/humla
```
Expected: `.gitignore LICENSE README.md build.gradle protobuf-update-and-compile.sh src tools` and **no** `.git` file. Keep `libraries/humla/.gitignore` (it ignores `src/main/libs/` and `src/main/obj/`, the ndk-build outputs, until Task 9 removes ndk-build).

- [ ] **Step 5: Re-register opus, speex and the two celt trees as submodules of the main repo**

```bash
cd /home/becker/git/mumla
git submodule add https://github.com/xiph/opus libraries/humla/src/main/jni/opus
git -C libraries/humla/src/main/jni/opus checkout --detach 65471dd567beb6b1156dc292858c0a28ca55ca3e
git submodule add https://github.com/xiph/speex libraries/humla/src/main/jni/speex
git -C libraries/humla/src/main/jni/speex checkout --detach a6d05eb5ff9d5062852cdf7df574bec728921ef9
git submodule add --name celt-0.7.0 https://gitlab.com/quite/celt.git libraries/humla/src/main/jni/celt-0.7.0-src
git -C libraries/humla/src/main/jni/celt-0.7.0-src checkout --detach 6c79a9325c328f86fa048bf124ff6a8912a60a3e
git submodule add --name celt-0.11.0 https://gitlab.com/quite/celt.git libraries/humla/src/main/jni/celt-0.11.0-src
git -C libraries/humla/src/main/jni/celt-0.11.0-src checkout --detach e3d39fec7c44d1841e817d3b1986bfdc4d0863a9
git add .gitmodules libraries/humla
git submodule status
```
Expected `.gitmodules` (four entries):
```
[submodule "libraries/humla/src/main/jni/opus"]
	path = libraries/humla/src/main/jni/opus
	url = https://github.com/xiph/opus
[submodule "libraries/humla/src/main/jni/speex"]
	path = libraries/humla/src/main/jni/speex
	url = https://github.com/xiph/speex
[submodule "celt-0.7.0"]
	path = libraries/humla/src/main/jni/celt-0.7.0-src
	url = https://gitlab.com/quite/celt.git
[submodule "celt-0.11.0"]
	path = libraries/humla/src/main/jni/celt-0.11.0-src
	url = https://gitlab.com/quite/celt.git
```
Expected `git submodule status` shows the four SHAs from step 1. The explicit `--name celt-0.7.0` / `--name celt-0.11.0` keep the `.git/modules/` directories short and stable: a submodule's *name* defaults to its path, and Task 9 moves both paths, which would otherwise leave the cached repositories under the old deep `libraries/humla/src/main/jni/…` names.

- [ ] **Step 6: Replace the locally built spongycastle jars with the Maven artifacts**

In `libraries/humla/build.gradle` replace lines 37–43 (line 36 is `dependencies {` and stays):
```groovy
    api 'com.google.protobuf:protobuf-java:3.11.4'
    api 'com.madgag.spongycastle:core:1.51.0.0'

    // Custom PKCS12 keybag parse modifications to support Mumble unencrypted certificates
    // Source: https://github.com/Morlunk/spongycastle/tree/pkcs12-keybag-fixes
    api files('libs/humla-spongycastle/prov/build/libs/prov-1.51.0.0.jar',
              'libs/humla-spongycastle/pkix/build/libs/pkix-1.51.0.0.jar')
```
with:
```groovy
    api 'com.google.protobuf:protobuf-java:3.11.4'
    // Stock Spongycastle 1.51 from Maven Central. The former libs/humla-spongycastle fork
    // (PKCS#12 plain keyBag support for Mumble certificates) is gone; Task 7 of the
    // foundation plan replaces all of this with BouncyCastle, which reads those files.
    api 'com.madgag.spongycastle:core:1.51.0.0'
    api 'com.madgag.spongycastle:prov:1.51.0.0'
    api 'com.madgag.spongycastle:pkix:1.51.0.0'
```

- [ ] **Step 7: Stop CI from building humla-spongycastle**

Replace the `assembleDebug` job in `.gitlab-ci.yml` (lines 24–35, from `assembleDebug:` through `expire_in: 3 months`) with:
```yaml
assembleDebug:
  stage: build
  script:
    # ANDROID_SDK_ROOT is already set in the android-sdk-ndk container image.
    - ./gradlew assembleDebug
  artifacts:
    paths:
    - app/build/outputs/apk/
    expire_in: 3 months
```
(Task 11 rewrites the whole file for Nix.)

- [ ] **Step 8: Verify the build is green**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `ls libraries/humla/src/main/libs/arm64-v8a` lists `libjniopus.so libjnispeex.so libjnicelt7.so libjnicelt11.so` (ndk-build still runs from the inlined `src/main/jni`).

- [ ] **Step 9: Commit**

```bash
cd /home/becker/git/mumla
git add -A .gitmodules .gitlab-ci.yml libraries/humla
git status --short | grep -v '^A\|^M\|^D\|^R' ; # expected: no output (nothing untracked left behind)
git commit -m "build: inline humla library into main repo" -m "libraries/humla is now an ordinary directory; opus, speex and the two celt trees are submodules of this repository at the same commits as before. The humla-spongycastle fork is dropped in favour of the stock 1.51.0.0 Maven artifacts until BouncyCastle lands.

Known regression: importing a Mumble-generated .p12 certificate does not work between this commit and the BouncyCastle migration, because stock Spongycastle 1.51 cannot read an unencrypted PKCS#12 keyBag."
```

---

### Task 2: F3 (part 1) — Gradle 9.7.1, AGP 9.4.1, version catalog, Kotlin, test infrastructure, lint

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties:3`
- Create: `gradle/libs.versions.toml`
- Rewrite: `settings.gradle`, `build.gradle`, `gradle.properties`, `app/build.gradle`, `libraries/humla/build.gradle`
- Create: `libraries/humla/src/test/java/se/lublin/humla/model/ServerParcelTest.kt`, `app/src/test/java/se/lublin/mumla/AppResourcesSmokeTest.kt`
- Modify (lint, steps 13–16): `libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java:47,79`, `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java:132`, `app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java:190-195`, `app/src/main/java/se/lublin/mumla/service/MumlaService.java:376-380`, `app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java:108-112`, `app/src/main/java/se/lublin/mumla/service/MumlaReconnectNotification.java:85-89,135`, `app/src/main/java/se/lublin/mumla/service/MumlaMessageNotification.java:107`, `app/src/main/AndroidManifest.xml:33`, fourteen `android:tint` attributes in ten files under `app/src/main/res/layout*/`

**Interfaces:**
- Consumes: Task 1 layout.
- Produces: version catalog accessors used by every later task (`libs.plugins.android.application`, `libs.plugins.android.library`, `libs.bundles.unit.test`, `libs.kotlinx.coroutines.android`, one accessor per dependency listed below); `testOptions.unitTests.includeAndroidResources = true` in both modules; Robolectric runnable from `src/test/java` in both modules; `minSdk = 31`; a lint-clean tree, so that every later task only has to keep its own lint findings at zero.

Design notes (verified, see Version research): AGP 9.x ships **built-in Kotlin** and refuses `org.jetbrains.kotlin.android`. The Kotlin compiler is the KGP that AGP itself resolves on the plugin classpath of the module applying `com.android.application` / `com.android.library` (AGP 9.4.1 depends on KGP ≥ 2.2.10). Spec F3 asks for "Kotlin Gradle plugin (latest)"; with AGP 9 that is **not** something a build chooses by adding a `kotlin-gradle-plugin` coordinate to the root `buildscript { }` classpath — that is a different resolution scope from the plugin classpath, so the entry is at best a no-op and at worst puts two KGPs on the build classpath. Google documents `kotlin { compilerOptions { … } }` as the configuration surface for built-in Kotlin instead (developer.android.com/build/migrate-to-built-in-kotlin). This plan therefore uses AGP 9.4.1's built-in Kotlin unmodified, records which KGP that actually is (step 10), and leaves `kotlin { compilerOptions { languageVersion = … } }` as the documented lever for any later stream that needs a newer language level. `jvmTarget` follows `compileOptions.targetCompatibility` (21). AGP 9's new DSL removed `applicationVariants`, `minSdkVersion`, `flavorDimensions "x"` and `android.ndkDirectory`; the rewrites below use `androidComponents`, `minSdk =`, `flavorDimensions += [...]`, and `androidComponents.sdkComponents.ndkDirectory`. The Windows branch of the old `ndkBuild` task is dropped (the Nix dev shell is the supported environment and Task 9 deletes ndk-build anyway).

- [ ] **Step 1: Write one failing Robolectric smoke test per module**

Spec F3 wants "one passing Robolectric smoke test per module", so both modules get one here — the humla one exercises a real Android class, the app one proves that `includeAndroidResources = true` really hands the merged resources to unit tests.

Create `libraries/humla/src/test/java/se/lublin/humla/model/ServerParcelTest.kt`:
```kotlin
package se.lublin.humla.model

import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Proves that Robolectric + Truth work in this module by exercising a real Android class. */
@RunWith(RobolectricTestRunner::class)
class ServerParcelTest {

    @Test
    fun `server survives a parcel round trip`() {
        val original = Server(7L, "Home", "mumble.example.org", 64738, "alice", "s3cret")
        val parcel = Parcel.obtain()
        original.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)

        val copy = Server.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertThat(copy.id).isEqualTo(7L)
        assertThat(copy.name).isEqualTo("Home")
        assertThat(copy.host).isEqualTo("mumble.example.org")
        assertThat(copy.port).isEqualTo(64738)
        assertThat(copy.username).isEqualTo("alice")
        assertThat(copy.password).isEqualTo("s3cret")
    }
}
```

Create `app/src/test/java/se/lublin/mumla/AppResourcesSmokeTest.kt`:
```kotlin
package se.lublin.mumla

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves that Robolectric runs in the app module and that
 * `testOptions.unitTests.includeAndroidResources = true` is in effect: without merged
 * resources, resolving a string resource fails instead of returning its value.
 */
@RunWith(RobolectricTestRunner::class)
class AppResourcesSmokeTest {

    @Test
    fun `the app module's merged resources are available to unit tests`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.packageName).isEqualTo("se.lublin.mumla")
        assertThat(context.getString(R.string.app_name)).isEqualTo("Mumla")
    }
}
```

- [ ] **Step 2: Run both to see them fail for the right reason**

Run:
```bash
cd /home/becker/git/mumla
nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.model.ServerParcelTest'
nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.AppResourcesSmokeTest'
```
Expected: both FAIL — neither module has Kotlin support yet, so Gradle reports either "Unresolved reference" errors or that the `.kt` file was not compiled (no such test class). Either way: red.

- [ ] **Step 3: Update the Gradle wrapper**

Edit `gradle/wrapper/gradle-wrapper.properties` line 3 to:
```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip
```

- [ ] **Step 4: Create the version catalog**

Create `gradle/libs.versions.toml` (dependency versions here are the *current* ones; Task 5 bumps them):
```toml
[versions]
# The Kotlin compiler is AGP's built-in Kotlin, so there is no `kotlin` version here and no
# kotlin-gradle-plugin entry: see the design notes above.
agp = "9.4.1"
coroutines = "1.11.0"

androidx-appcompat = "1.7.1"
androidx-activity = "1.13.0"
androidx-cardview = "1.0.0"
androidx-core = "1.19.0"
androidx-documentfile = "1.1.0"
androidx-exifinterface = "1.4.2"
androidx-fragment = "1.8.9"
androidx-preference = "1.2.1"
androidx-recyclerview = "1.4.0"
material = "1.13.0"
jsoup = "1.13.1"
netcipher = "2.1.0"
billing = "7.1.1"

protobuf = "3.11.4"
spongycastle = "1.51.0.0"
javacpp = "0.7"
jetbrains-annotations = "18.0.0"
minidns = "0.3.4"
guava = "28.2-android"

junit = "4.13.2"
robolectric = "4.17"
mockk = "1.14.11"
truth = "1.4.5"
androidx-test-core = "1.7.0"

[libraries]
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "androidx-appcompat" }
androidx-activity = { module = "androidx.activity:activity", version.ref = "androidx-activity" }
androidx-cardview = { module = "androidx.cardview:cardview", version.ref = "androidx-cardview" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidx-core" }
androidx-documentfile = { module = "androidx.documentfile:documentfile", version.ref = "androidx-documentfile" }
androidx-exifinterface = { module = "androidx.exifinterface:exifinterface", version.ref = "androidx-exifinterface" }
androidx-fragment = { module = "androidx.fragment:fragment", version.ref = "androidx-fragment" }
androidx-preference = { module = "androidx.preference:preference", version.ref = "androidx-preference" }
androidx-recyclerview = { module = "androidx.recyclerview:recyclerview", version.ref = "androidx-recyclerview" }
material = { module = "com.google.android.material:material", version.ref = "material" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
netcipher = { module = "info.guardianproject.netcipher:netcipher", version.ref = "netcipher" }
billing = { module = "com.android.billingclient:billing", version.ref = "billing" }

protobuf-java = { module = "com.google.protobuf:protobuf-java", version.ref = "protobuf" }
spongycastle-core = { module = "com.madgag.spongycastle:core", version.ref = "spongycastle" }
spongycastle-prov = { module = "com.madgag.spongycastle:prov", version.ref = "spongycastle" }
spongycastle-pkix = { module = "com.madgag.spongycastle:pkix", version.ref = "spongycastle" }
javacpp = { module = "com.googlecode.javacpp:javacpp", version.ref = "javacpp" }
jetbrains-annotations = { module = "org.jetbrains:annotations", version.ref = "jetbrains-annotations" }
minidns-hla = { module = "org.minidns:minidns-hla", version.ref = "minidns" }
minidns-android21 = { module = "org.minidns:minidns-android21", version.ref = "minidns" }
guava = { module = "com.google.guava:guava", version.ref = "guava" }

junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }

[bundles]
unit-test = ["junit", "robolectric", "mockk", "truth", "kotlinx-coroutines-test", "androidx-test-core"]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
```

- [ ] **Step 5: Rewrite `settings.gradle`**

Replace the whole file (keep the GPL header comment block from lines 1–16) with:
```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mumla"
include ':libraries:humla', ':app'
```

- [ ] **Step 6: Rewrite the root `build.gradle`**

Replace everything after the GPL header with:
```groovy
// AGP 9 compiles Kotlin itself ("built-in Kotlin"): the Kotlin compiler comes from the KGP that
// AGP resolves on the plugin classpath, so there is no buildscript { } block and no
// kotlin-gradle-plugin dependency here. Do NOT apply org.jetbrains.kotlin.android anywhere -
// AGP 9 rejects it. To change the Kotlin language level, use kotlin { compilerOptions { ... } }
// in the module that needs it.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
```

- [ ] **Step 7: Rewrite `gradle.properties`**

```
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
```
(`android.enableJetifier` is gone: every dependency is AndroidX-native.)

- [ ] **Step 8: Rewrite `app/build.gradle`**

Replace everything after the GPL header with:
```groovy
plugins {
    alias(libs.plugins.android.application)
}

def signingFile = file 'signing.gradle'
if (signingFile.exists()) apply from: signingFile
def signingBetaFile = file 'signing-beta.gradle'
if (signingBetaFile.exists()) apply from: signingBetaFile

tasks.withType(JavaCompile).configureEach {
    // TODO include deprecations at some point, but currently they are *many*
    options.compilerArgs << "-Xlint:all" << "-Xlint:-deprecation" << "-Xlint:-dep-ann"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

android {
    namespace = 'se.lublin.mumla'
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    base {
        archivesName = "mumla"
    }

    buildFeatures {
        buildConfig = true
    }

    def gitDescribe = providers.exec {
        // Need --always because there are no tags in gitlab ci (right?)
        commandLine "git", "describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--always"
    }.standardOutput.asText.get().trim()

    defaultConfig {
        minSdk = 31
        targetSdk = 36

        applicationId = "se.lublin.mumla"
        // Remember: app_news_items_vX_Y_Z in src/main/res/values/strings.xml
        // and NEWS_ITEMS in src/main/res/java/se/lublin/mumla/app/DialogUtils.java
        //     code:XYYZZbb (bb for build)
        versionCode = 3070300
        versionName = gitDescribe

        buildConfigField "long", "TIMESTAMP", System.currentTimeMillis() + "L"
        buildConfigField "String", "VERSIONTAG", "\"" + gitDescribe.split("-")[0] + "\""
    }

    flavorDimensions += ["release"]
    productFlavors {
        goog {
            dimension = "release"
            applicationId = "se.lublin.mumla"
        }
        foss {
            dimension = "release"
            applicationId = "se.lublin.mumla"
        }
        donation {
            dimension = "release"
            applicationId = "se.lublin.mumla.donation"
        }
        beta {
            dimension = "release"
            applicationId = "se.lublin.mumla.beta"
        }
    }

    buildTypes {
        // signingConfig beta will override
        release {
            if (android.hasProperty("signingConfigs")) {
                if (signingConfigs.hasProperty("release")) {
                    signingConfig = signingConfigs.release
                }
                if (signingConfigs.hasProperty("beta")) {
                    signingConfig = signingConfigs.beta
                }
            }
        }
        debug {
            versionNameSuffix = "-debug"
            if (android.hasProperty("signingConfigs")) {
                if (signingConfigs.hasProperty("beta")) {
                    signingConfig = signingConfigs.beta
                }
            }
        }
    }

    testOptions {
        unitTests {
            includeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        // InvalidPackage: the crypto provider references javax.naming / java.awt classes that
        // Android does not ship and that are never reached at runtime.
        disable 'InvalidPackage', 'MissingTranslation'
        explainIssues = true
        ignoreWarnings = false
        quiet = false
    }
}

// betas may be released every minute
androidComponents {
    onVariants(selector().withFlavor("release", "beta")) { variant ->
        variant.outputs.each { output ->
            output.versionCode.set((int) (System.currentTimeMillis() / 60000L))
        }
    }
}

dependencies {
    implementation project(":libraries:humla")
    implementation libs.kotlinx.coroutines.android
    implementation libs.androidx.appcompat
    implementation libs.androidx.activity
    implementation libs.androidx.cardview
    implementation libs.androidx.core.ktx
    implementation libs.androidx.documentfile
    implementation libs.androidx.fragment
    implementation libs.androidx.recyclerview
    implementation libs.androidx.exifinterface
    implementation libs.material
    implementation libs.androidx.preference
    implementation libs.jsoup
    implementation libs.netcipher

    googImplementation libs.billing

    testImplementation libs.bundles.unit.test
}
```
If AGP rejects `withFlavor("release", "beta")` with "no signature of method", use the pair overload: `selector().withFlavor(new kotlin.Pair("release", "beta"))`.

- [ ] **Step 9: Rewrite `libraries/humla/build.gradle`**

Replace everything after the GPL header with:
```groovy
plugins {
    alias(libs.plugins.android.library)
}

tasks.withType(JavaCompile).configureEach {
    // TODO include deprecations at some point, but currently they are *many*
    options.compilerArgs << "-Xlint:all" << "-Xlint:-deprecation" << "-Xlint:-dep-ann"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

android {
    namespace = 'se.lublin.humla'

    compileSdk = 36
    ndkVersion = '26.1.10909125'

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // ndk-build output (until the CMake migration)
    sourceSets.main.jniLibs.srcDirs += ['src/main/libs']

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 31
        testApplicationId = "se.lublin.humla.test"
    }

    testOptions {
        targetSdk = 36
        unitTests {
            includeAndroidResources = true
        }
    }

    lint {
        targetSdk = 36
        abortOnError = true
        // InvalidPackage: see app/build.gradle
        disable 'InvalidPackage', 'MissingTranslation'
        explainIssues = true
        ignoreWarnings = false
        quiet = false
    }
}

// Trigger ndk-build before anything is compiled or packaged (removed by the CMake migration).
def ndkDirectory = androidComponents.sdkComponents.ndkDirectory
tasks.register('ndkBuild', Exec) {
    def jniDir = file('src/main/jni').absolutePath
    doFirst {
        commandLine "${ndkDirectory.get().asFile.absolutePath}/ndk-build", '-C', jniDir
    }
}
tasks.named('preBuild') { dependsOn 'ndkBuild' }

dependencies {
    api libs.protobuf.java
    api libs.spongycastle.core
    api libs.spongycastle.prov
    api libs.spongycastle.pkix

    implementation libs.kotlinx.coroutines.android
    implementation libs.javacpp
    implementation libs.jetbrains.annotations
    implementation libs.minidns.hla
    implementation libs.minidns.android21
    implementation libs.guava

    testImplementation libs.bundles.unit.test
}
```

- [ ] **Step 10: Regenerate the wrapper scripts, run both smoke tests and record the Kotlin version**

Run:
```bash
cd /home/becker/git/mumla && nix develop --command ./gradlew wrapper --gradle-version 9.7.1 --distribution-type bin
nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.model.ServerParcelTest'
nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.AppResourcesSmokeTest'
nix develop --command ./gradlew :libraries:humla:buildEnvironment | grep -i 'kotlin-gradle-plugin'
```
Expected: the wrapper task succeeds (updates `gradle/wrapper/gradle-wrapper.jar` and `gradlew`); each test run prints `BUILD SUCCESSFUL` with 1 test passed — a compiling and passing `.kt` test is what proves built-in Kotlin works. The `buildEnvironment` grep must print **exactly one** `org.jetbrains.kotlin:kotlin-gradle-plugin:<version>` coordinate, pulled in by `com.android.tools.build:gradle:9.4.1`, with `<version>` at least `2.2.10`. Two different versions, or none, means the built-in Kotlin wiring is wrong — stop and fix it before continuing.

- [ ] **Step 11: Run the build-and-test gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; the existing humla JUnit-3 style tests (`ModelTest`, `MixerTest`, `URLParserTest`, `WhisperTargetListTest`) still run under the JUnit 4 runner and pass. (Lint is not part of the gate yet — steps 13–16 make it pass and add it.)

- [ ] **Step 12: Commit**

```bash
cd /home/becker/git/mumla
git add gradle/libs.versions.toml gradle/wrapper gradlew gradlew.bat settings.gradle build.gradle gradle.properties app/build.gradle libraries/humla/build.gradle libraries/humla/src/test/java/se/lublin/humla/model/ServerParcelTest.kt app/src/test/java/se/lublin/mumla/AppResourcesSmokeTest.kt
git commit -m "build: move to gradle 9.7.1, agp 9.4.1, version catalog and kotlin" -m "minSdk 31, Java/Kotlin 21 toolchain, AGP's built-in Kotlin compiler with no separate KGP on the build classpath, no Jetifier, non-transitive R classes, JUnit 4 + Robolectric + MockK + Truth + coroutines-test in both modules, one Robolectric smoke test per module."
```

- [ ] **Step 13: Run lint for the first time and compare against the recorded baseline**

Both modules already carry `lint { abortOnError = true }`, but `.gitlab-ci.yml` has its `lintDebug` job commented out, so lint has not gated anything for a long time. Run it now:

```bash
cd /home/becker/git/mumla
nix develop --command ./gradlew --console=plain :app:lintFossDebug :libraries:humla:lintDebug --continue
```

Expected: FAIL. The baseline measured on this repository on 2026-09-19 (before this task's changes) is **30 errors**, and every one of them is listed in step 14:

| Module | Check | Count | Where |
|---|---|---|---|
| humla | `MissingPermission` | 2 | `audio/AudioInput.java:87` (`new AudioRecord(...)`) |
| app | `Range` | 6 | `db/MumlaSQLiteDatabase.java:190-195` (`getColumnIndex` may return -1) |
| app | `UseAppTint` | 14 | `android:tint` on AppCompat image views in 10 layout files |
| app | `UnspecifiedRegisterReceiverFlag` | 3 | `service/MumlaService.java:379`, `service/MumlaConnectionNotification.java:111`, `service/MumlaReconnectNotification.java:88` |
| app | `MissingPermission` | 2 | `service/MumlaMessageNotification.java:107`, `service/MumlaReconnectNotification.java:135` (`notify` without POST_NOTIFICATIONS) |
| app | `ProtectedPermissions` | 1 | `AndroidManifest.xml:33` (`BROADCAST_CLOSE_SYSTEM_DIALOGS`) |
| app | `MissingQuantity` | 2 | `res/values-cs/strings.xml:5,10` (Czech `many` plural) |

Warnings (11 in humla, 312 in app) do not fail the build and are left alone. AGP 9's lint is newer than the one that produced this table, so it may report findings that are not listed: fix each of those at its source too, and if the fix is in a file another stream owns, keep it to the smallest change that removes the finding and add a row to **Cross-stream touches**. The full reports are written to `app/build/intermediates/lint_intermediate_text_report/fossDebug/lintReportFossDebug/lint-results-fossDebug.txt` and the humla equivalent.

- [ ] **Step 14: Fix every error**

The humla module declares no androidx dependency today, so first add one. In `gradle/libs.versions.toml` add `androidx-annotation = "1.8.1"` under `[versions]` — the version the app module already resolves transitively, so it changes nothing that is on the classpath — and `androidx-annotation = { module = "androidx.annotation:annotation", version.ref = "androidx-annotation" }` under `[libraries]`; in `libraries/humla/build.gradle` add `implementation libs.androidx.annotation` next to the other `implementation` lines. It is deliberately pinned to what the graph already contains, so this adds a declaration and not a new artifact; re-check it whenever AndroidX is next bumped.

`libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java` — add `import android.Manifest;` and `import androidx.annotation.RequiresPermission;`, then annotate both the constructor and the factory so the requirement is declared where the platform call happens:
```java
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public AudioInput(AudioInputListener listener, int audioSource, int targetSampleRate,
```
```java
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private static AudioRecord setupAudioRecord(int sampleRate, int audioSource) throws AudioInitializationException {
```

`libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java` — the chain ends here, where a `Context` is available. Replace line 132
```java
        mInput = new AudioInput(this, mAudioSource, mSampleRate, mEchoCancellationMethod);
```
with
```java
        if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new AudioInitializationException("RECORD_AUDIO permission not granted");
        }
        mInput = new AudioInput(this, mAudioSource, mSampleRate, mEchoCancellationMethod);
```
and add the imports `android.Manifest` and `android.content.pm.PackageManager`. `Context.checkSelfPermission` exists since API 23, so no compat class and no androidx.core dependency is needed. An explicit `checkSelfPermission` guard in the calling method is what satisfies lint's `@RequiresPermission` analysis, and it turns a later `SecurityException` into the `AudioException` that `HumlaService` already handles.

`app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java` — in lines 190–195 replace every `c.getColumnIndex(X)` with `c.getColumnIndexOrThrow(X)`; the columns are all in the `SELECT`, so the throw never fires and the index can no longer be -1.

`app/src/main/java/se/lublin/mumla/service/MumlaService.java` — replace lines 376–380
```java
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK));
        }
```
with
```java
        ContextCompat.registerReceiver(this, mTalkReceiver,
                new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), ContextCompat.RECEIVER_EXPORTED);
```
(`RECEIVER_EXPORTED` is deliberate: `se.lublin.mumla.action.TALK` is the documented way other apps toggle talking.) Add `import androidx.core.content.ContextCompat;`.

`app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java` — replace lines 108–112 (the `if/else` inside the `try`) with
```java
            ContextCompat.registerReceiver(mService, mNotificationReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
```
and `app/src/main/java/se/lublin/mumla/service/MumlaReconnectNotification.java` lines 85–89 with
```java
            ContextCompat.registerReceiver(mContext, mNotificationReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
```
(both receivers only listen to this app's own `b_*` actions). Add `import androidx.core.content.ContextCompat;` to both and drop the now-unused `android.os.Build` import where it becomes unused.

`app/src/main/java/se/lublin/mumla/service/MumlaMessageNotification.java` — replace line 107 `manager.notify(NOTIFICATION_ID, notification);` with
```java
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            manager.notify(NOTIFICATION_ID, notification);
        }
```
and the same guard around `nmc.notify(NOTIFICATION_ID, builder.build());` in `MumlaReconnectNotification.java:135`; add the imports `android.Manifest`, `android.content.pm.PackageManager` and `androidx.core.content.ContextCompat` (the app module already depends on androidx.core). Posting a notification the user has refused is a no-op anyway, so this only makes the existing behavior explicit; stream P owns asking for the permission (P3).

`app/src/main/AndroidManifest.xml` — delete line 33
```xml
    <uses-permission android:name="android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS" />
```
It is a signature permission a normal app never gets, and its only user is `MumlaService.onOverlayToggled`'s `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)` branch, which is unreachable at minSdk 31 (stream A deletes that branch when it next touches the file).

Layouts — replace `android:tint` with `app:tint` on the fourteen AppCompat image views lint names, all under `app/src/main/res/`: `layout/channel_row.xml:34,61,71`, `layout/channel_user_row.xml:65`, `layout/fragment_channel.xml:61`, `layout-sw600dp/fragment_channel.xml:84`, `layout-sw720dp/fragment_channel.xml:83`, `layout/fragment_chat.xml:76`, `layout/fragment_tokens.xml:56`, `layout/public_server_list_row.xml:123,143`, `layout/server_list_row.xml:123,143`, `layout/token_row.xml:40`. Every one of those files already declares `xmlns:app` (they use `app:srcCompat`), so this is a pure attribute rename.

`app/build.gradle` — in the `lint { }` block, below the existing `disable` line, add
```groovy
        // Plural forms in translated resources belong to Weblate; spec section 2 forbids editing
        // translation files, so this check reports but does not fail the build. It stays enabled
        // for the English source, where it would be a real error we can fix.
        warning 'MissingQuantity'
```

- [ ] **Step 15: Run lint and the full green gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: `BUILD SUCCESSFUL`; both lint runs report 0 errors. From here on this command is the green gate for every task.

- [ ] **Step 16: Commit the lint fixes separately**

```bash
cd /home/becker/git/mumla
git add -A app/src libraries/humla/src app/build.gradle
git add gradle/libs.versions.toml libraries/humla/build.gradle
git commit -m "fix(lint): resolve the errors that block abortOnError" -m "Lint had not gated a build for a long time (the CI job was commented out) and reported 30 errors: missing RECORD_AUDIO/POST_NOTIFICATIONS declarations, getColumnIndex without OrThrow, registerReceiver without an exported flag, a signature-only permission and android:tint on AppCompat views. MissingQuantity is downgraded to a warning because it only fires in Weblate-managed translations."
```

---

### Task 3: F3 (part 2) — `Settings.java` → `Settings.kt` with mapping tests

**Files:**
- Create: `app/src/test/java/se/lublin/mumla/SettingsTest.kt`
- Delete: `app/src/main/java/se/lublin/mumla/Settings.java`
- Create: `app/src/main/java/se/lublin/mumla/Settings.kt`

**Interfaces:**
- Consumes: Robolectric + Truth from Task 2; `se.lublin.humla.Constants.TRANSMIT_VOICE_ACTIVITY = 0`, `TRANSMIT_PUSH_TO_TALK = 1`, `TRANSMIT_CONTINUOUS = 2`.
- Produces: `Settings.kt` with the identical Java-visible API (`Settings.getInstance(Context)`, every `PREF_*`/`DEFAULT_*`/`ARRAY_*` constant as a static field, every method with its current name and signature). Streams B and P only add keys and accessors to this file.

The tests are written first and run against the Java implementation (they pass: they are characterization tests that guard the conversion); the conversion commit then keeps them green. The one deliberate change: `addNewsShownVersions` no longer mutates the caller's list (it filtered empties out of the passed list in place); callers pass their own fresh lists, so nothing observes this.

- [ ] **Step 1: Write the characterization tests**

Create `app/src/test/java/se/lublin/mumla/SettingsTest.kt`:
```kotlin
package se.lublin.mumla

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        settings = Settings.getInstance(context)
    }

    @Test
    fun `voice activity maps to humla transmit mode 0`() {
        prefs.edit().putString("audioInputMethod", "voiceActivity").commit()
        assertThat(settings.getHumlaInputMethod()).isEqualTo(0)
    }

    @Test
    fun `push to talk maps to humla transmit mode 1`() {
        prefs.edit().putString("audioInputMethod", "ptt").commit()
        assertThat(settings.getHumlaInputMethod()).isEqualTo(1)
    }

    @Test
    fun `continuous maps to humla transmit mode 2`() {
        prefs.edit().putString("audioInputMethod", "continuous").commit()
        assertThat(settings.getHumlaInputMethod()).isEqualTo(2)
    }

    @Test
    fun `an unknown stored input method falls back to voice activity`() {
        prefs.edit().putString("audioInputMethod", "handset").commit()
        assertThat(settings.getInputMethod()).isEqualTo("voiceActivity")
        assertThat(settings.getHumlaInputMethod()).isEqualTo(0)
    }

    @Test
    fun `setInputMethod rejects unknown values without writing them`() {
        assertThrows(RuntimeException::class.java) { settings.setInputMethod("handset") }
        assertThat(prefs.contains("audioInputMethod")).isFalse()
    }

    @Test
    fun `setInputMethod stores a valid value`() {
        settings.setInputMethod("ptt")
        assertThat(prefs.getString("audioInputMethod", null)).isEqualTo("ptt")
    }

    @Test
    fun `detection threshold is the stored percentage divided by 100`() {
        prefs.edit().putInt("vadThreshold", 35).commit()
        assertThat(settings.getDetectionThreshold()).isWithin(1e-6f).of(0.35f)
    }

    @Test
    fun `detection threshold defaults to one half`() {
        assertThat(settings.getDetectionThreshold()).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `amplitude boost is the stored percentage divided by 100`() {
        prefs.edit().putInt("inputVolume", 250).commit()
        assertThat(settings.getAmplitudeBoostMultiplier()).isWithin(1e-6f).of(2.5f)
    }

    @Test
    fun `input sample rate and frames per packet are parsed from their string preferences`() {
        prefs.edit().putString("input_quality", "16000").putString("audio_per_packet", "6").commit()
        assertThat(settings.getInputSampleRate()).isEqualTo(16000)
        assertThat(settings.getFramesPerPacket()).isEqualTo(6)
    }

    @Test
    fun `hot corner gravity maps each corner and is zero when disabled`() {
        val cases = mapOf(
            "none" to 0,
            "topLeft" to (Gravity.LEFT or Gravity.TOP),
            "topRight" to (Gravity.RIGHT or Gravity.TOP),
            "bottomLeft" to (Gravity.LEFT or Gravity.BOTTOM),
            "bottomRight" to (Gravity.RIGHT or Gravity.BOTTOM),
        )
        for ((stored, gravity) in cases) {
            prefs.edit().putString("hotCorner", stored).commit()
            assertThat(settings.getHotCornerGravity()).isEqualTo(gravity)
            assertThat(settings.isHotCornerEnabled()).isEqualTo(stored != "none")
        }
    }

    @Test
    fun `certificate id below zero means no certificate is used`() {
        assertThat(settings.getDefaultCertificate()).isEqualTo(-1L)
        assertThat(settings.isUsingCertificate()).isFalse()
        settings.setDefaultCertificateId(3L)
        assertThat(settings.getDefaultCertificate()).isEqualTo(3L)
        assertThat(settings.isUsingCertificate()).isTrue()
        settings.disableCertificate()
        assertThat(settings.isUsingCertificate()).isFalse()
    }

    @Test
    fun `deafened implies muted`() {
        settings.setMutedAndDeafened(false, true)
        assertThat(settings.isMuted()).isTrue()
        assertThat(settings.isDeafened()).isTrue()
        settings.setMutedAndDeafened(false, false)
        assertThat(settings.isMuted()).isFalse()
    }

    @Test
    fun `news shown versions accumulate and skip empty entries`() {
        settings.addNewsShownVersions(mutableListOf("3.6.0", ""))
        settings.addNewsShownVersions(mutableListOf("3.7.0"))
        assertThat(settings.getNewsShownVersions()).containsExactly("3.6.0", "3.7.0")
        settings.resetNewsShownVersion()
        assertThat(settings.getNewsShownVersions()).isEmpty()
    }

    @Test
    fun `push to talk button is shown unless hidden`() {
        assertThat(settings.isPushToTalkButtonShown()).isTrue()
        prefs.edit().putBoolean("hidePtt", true).commit()
        assertThat(settings.isPushToTalkButtonShown()).isFalse()
    }
}
```

- [ ] **Step 2: Run the tests against the Java implementation**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.SettingsTest'`
Expected: `BUILD SUCCESSFUL`, 15 tests passed (characterization: they document today's behavior, so they pass before the conversion). If any fails, the test is wrong about today's behavior — fix the test, not the code.

- [ ] **Step 3: Convert to Kotlin**

`git rm app/src/main/java/se/lublin/mumla/Settings.java` and create `app/src/main/java/se/lublin/mumla/Settings.kt`:
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

package se.lublin.mumla

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.preference.PreferenceManager
import se.lublin.humla.Constants

/**
 * Settings class for universal access to the app's preferences.
 *
 * Streams B (audio) and P (platform) add keys and accessors here; keep the Java-visible
 * API (static constants, `getInstance`, method names) stable because Java callers remain.
 */
class Settings private constructor(context: Context) {

    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun getInputMethod(): String {
        val method = preferences.getString(PREF_INPUT_METHOD, ARRAY_INPUT_METHOD_VOICE)
        // Set default method for users who used to use handset mode before removal.
        return if (method != null && method in ARRAY_INPUT_METHODS) method else ARRAY_INPUT_METHOD_VOICE
    }

    /**
     * Converts the preference input method value to the one used to connect to a server via Humla.
     * @return An input method value used to instantiate a Humla service.
     */
    fun getHumlaInputMethod(): Int = when (val inputMethod = getInputMethod()) {
        ARRAY_INPUT_METHOD_VOICE -> Constants.TRANSMIT_VOICE_ACTIVITY
        ARRAY_INPUT_METHOD_PTT -> Constants.TRANSMIT_PUSH_TO_TALK
        ARRAY_INPUT_METHOD_CONTINUOUS -> Constants.TRANSMIT_CONTINUOUS
        else -> throw RuntimeException("Could not convert input method '$inputMethod' to a Humla input method id!")
    }

    fun setInputMethod(inputMethod: String) {
        if (inputMethod in ARRAY_INPUT_METHODS) {
            preferences.edit().putString(PREF_INPUT_METHOD, inputMethod).apply()
        } else {
            throw RuntimeException("Invalid input method $inputMethod")
        }
    }

    fun getInputSampleRate(): Int = preferences.getString(PREF_INPUT_RATE, DEFAULT_RATE)!!.toInt()

    fun getInputQuality(): Int = preferences.getInt(PREF_INPUT_QUALITY, DEFAULT_INPUT_QUALITY)

    fun getAmplitudeBoostMultiplier(): Float =
        preferences.getInt(PREF_AMPLITUDE_BOOST, DEFAULT_AMPLITUDE_BOOST).toFloat() / 100

    fun getDetectionThreshold(): Float =
        preferences.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD).toFloat() / 100

    fun getPushToTalkKey(): Int = preferences.getInt(PREF_PUSH_KEY, DEFAULT_PUSH_KEY)

    fun getHotCorner(): String = preferences.getString(PREF_HOT_CORNER_KEY, DEFAULT_HOT_CORNER)!!

    /** @return true if a hot corner should be shown. */
    fun isHotCornerEnabled(): Boolean = ARRAY_HOT_CORNER_NONE != getHotCorner()

    /** @return A [Gravity] value, or 0 if the hot corner is disabled. */
    fun getHotCornerGravity(): Int = when (getHotCorner()) {
        ARRAY_HOT_CORNER_BOTTOM_LEFT -> Gravity.LEFT or Gravity.BOTTOM
        ARRAY_HOT_CORNER_BOTTOM_RIGHT -> Gravity.RIGHT or Gravity.BOTTOM
        ARRAY_HOT_CORNER_TOP_LEFT -> Gravity.LEFT or Gravity.TOP
        ARRAY_HOT_CORNER_TOP_RIGHT -> Gravity.RIGHT or Gravity.TOP
        else -> 0
    }

    /** @return the height of the PTT button */
    fun getPTTButtonHeight(): Int = preferences.getInt(PREF_PTT_BUTTON_HEIGHT, DEFAULT_PTT_BUTTON_HEIGHT)

    /**
     * Returns a database identifier for the default certificate, or a negative number if there is
     * no default certificate set.
     */
    fun getDefaultCertificate(): Long = preferences.getLong(PREF_CERT_ID, -1)

    fun getDefaultUsername(): String = preferences.getString(PREF_DEFAULT_USERNAME, DEFAULT_DEFAULT_USERNAME)!!

    fun isPushToTalkToggle(): Boolean = preferences.getBoolean(PREF_PTT_TOGGLE, DEFAULT_PTT_TOGGLE)

    fun isPushToTalkButtonShown(): Boolean = !preferences.getBoolean(PREF_PUSH_BUTTON_HIDE_KEY, DEFAULT_PUSH_BUTTON_HIDE)

    fun isChatNotifyEnabled(): Boolean = preferences.getBoolean(PREF_CHAT_NOTIFY, DEFAULT_CHAT_NOTIFY)

    fun isTextToSpeechEnabled(): Boolean = preferences.getBoolean(PREF_USE_TTS, DEFAULT_USE_TTS)

    fun isShortTextToSpeechMessagesEnabled(): Boolean =
        preferences.getBoolean(PREF_SHORT_TTS_MESSAGES, DEFAULT_SHORT_TTS_MESSAGES)

    fun isAutoReconnectEnabled(): Boolean = preferences.getBoolean(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)

    fun isTcpForced(): Boolean = preferences.getBoolean(PREF_FORCE_TCP, DEFAULT_FORCE_TCP)

    fun isOpusDisabled(): Boolean = preferences.getBoolean(PREF_DISABLE_OPUS, DEFAULT_DISABLE_OPUS)

    fun isTorEnabled(): Boolean = preferences.getBoolean(PREF_USE_TOR, DEFAULT_USE_TOR)

    fun disableTor() {
        preferences.edit().putBoolean(PREF_USE_TOR, false).apply()
    }

    fun isMuted(): Boolean = preferences.getBoolean(PREF_MUTED, DEFAULT_MUTED)

    fun isDeafened(): Boolean = preferences.getBoolean(PREF_DEAFENED, DEFAULT_DEAFENED)

    fun isFirstRun(): Boolean = preferences.getBoolean(PREF_FIRST_RUN, DEFAULT_FIRST_RUN)

    fun shouldLoadExternalImages(): Boolean = preferences.getBoolean(PREF_LOAD_IMAGES, DEFAULT_LOAD_IMAGES)

    fun setMutedAndDeafened(muted: Boolean, deafened: Boolean) {
        preferences.edit()
            .putBoolean(PREF_MUTED, muted || deafened)
            .putBoolean(PREF_DEAFENED, deafened)
            .apply()
    }

    fun setFirstRun(run: Boolean) {
        preferences.edit().putBoolean(PREF_FIRST_RUN, run).apply()
    }

    fun getFramesPerPacket(): Int = preferences.getString(PREF_FRAMES_PER_PACKET, DEFAULT_FRAMES_PER_PACKET)!!.toInt()

    fun isHalfDuplex(): Boolean = preferences.getBoolean(PREF_HALF_DUPLEX, DEFAULT_HALF_DUPLEX)

    fun isHandsetMode(): Boolean = preferences.getBoolean(PREF_HANDSET_MODE, DEFAULT_HANDSET_MODE)

    fun isPttSoundEnabled(): Boolean = preferences.getBoolean(PREF_PTT_SOUND, DEFAULT_PTT_SOUND)

    fun isPreprocessorEnabled(): Boolean = preferences.getBoolean(PREF_PREPROCESSOR_ENABLED, DEFAULT_PREPROCESSOR_ENABLED)

    fun getEchoCancellationMethod(): String =
        preferences.getString(PREF_ECHO_CANCELLATION_METHOD, DEFAULT_ECHO_CANCELLATION_METHOD)!!

    fun shouldStayAwake(): Boolean = preferences.getBoolean(PREF_STAY_AWAKE, DEFAULT_STAY_AWAKE)

    fun setDefaultCertificateId(defaultCertificateId: Long) {
        preferences.edit().putLong(PREF_CERT_ID, defaultCertificateId).apply()
    }

    fun disableCertificate() {
        preferences.edit().putLong(PREF_CERT_ID, -1).apply()
    }

    fun isUsingCertificate(): Boolean = getDefaultCertificate() >= 0

    /** @return true if the user count should be shown next to channels. */
    fun shouldShowUserCount(): Boolean = preferences.getBoolean(PREF_SHOW_USER_COUNT, DEFAULT_SHOW_USER_COUNT)

    fun shouldStartUpInPinnedMode(): Boolean =
        preferences.getBoolean(PREF_START_UP_IN_PINNED_MODE, DEFAULT_START_UP_IN_PINNED_MODE)

    fun getNewsShownVersions(): Set<String> =
        preferences.getStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet())!!

    fun addNewsShownVersions(versions: List<String>) {
        // Copy: getStringSet docs state that the returned set must not be modified.
        val shownVersions = HashSet(preferences.getStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet())!!)
        val added = shownVersions.addAll(versions.filter { it.isNotEmpty() })
        if (added) {
            preferences.edit().putStringSet(PREF_NEWS_SHOWN_VERSIONS, shownVersions).apply()
        }
    }

    fun resetNewsShownVersion() {
        preferences.edit().putStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet()).apply()
    }

    companion object {
        const val PREF_INPUT_METHOD = "audioInputMethod"
        /** Voice activity transmits depending on the amplitude of user input. */
        const val ARRAY_INPUT_METHOD_VOICE = "voiceActivity"
        /** Push to talk transmits on command. */
        const val ARRAY_INPUT_METHOD_PTT = "ptt"
        /** Continuous transmits always. */
        const val ARRAY_INPUT_METHOD_CONTINUOUS = "continuous"
        @JvmField
        val ARRAY_INPUT_METHODS: Set<String> = setOf(ARRAY_INPUT_METHOD_VOICE, ARRAY_INPUT_METHOD_PTT, ARRAY_INPUT_METHOD_CONTINUOUS)

        // NOTE: When changing DEFAULTs, the default value in the corresponding
        // widget in settings_PAGE.xml must also be changed. It doesn't pick this
        // up itself...

        const val PREF_THRESHOLD = "vadThreshold"
        const val DEFAULT_THRESHOLD = 50

        const val PREF_PUSH_KEY = "talkKey"
        const val DEFAULT_PUSH_KEY = -1

        const val PREF_HOT_CORNER_KEY = "hotCorner"
        const val ARRAY_HOT_CORNER_NONE = "none"
        const val ARRAY_HOT_CORNER_TOP_LEFT = "topLeft"
        const val ARRAY_HOT_CORNER_BOTTOM_LEFT = "bottomLeft"
        const val ARRAY_HOT_CORNER_TOP_RIGHT = "topRight"
        const val ARRAY_HOT_CORNER_BOTTOM_RIGHT = "bottomRight"
        const val DEFAULT_HOT_CORNER = ARRAY_HOT_CORNER_NONE

        const val PREF_PUSH_BUTTON_HIDE_KEY = "hidePtt"
        const val DEFAULT_PUSH_BUTTON_HIDE = false

        const val PREF_PTT_TOGGLE = "togglePtt"
        const val DEFAULT_PTT_TOGGLE = false

        const val PREF_INPUT_RATE = "input_quality"
        const val DEFAULT_RATE = "48000"

        const val PREF_INPUT_QUALITY = "input_bitrate"
        const val DEFAULT_INPUT_QUALITY = 40000

        const val PREF_AMPLITUDE_BOOST = "inputVolume"
        const val DEFAULT_AMPLITUDE_BOOST = 100

        const val PREF_CHAT_NOTIFY = "chatNotify"
        const val DEFAULT_CHAT_NOTIFY = true

        const val PREF_USE_TTS = "useTts"
        const val DEFAULT_USE_TTS = true

        const val PREF_SHORT_TTS_MESSAGES = "shortTtsMessages"
        const val DEFAULT_SHORT_TTS_MESSAGES = false

        const val PREF_AUTO_RECONNECT = "autoReconnect"
        const val DEFAULT_AUTO_RECONNECT = true

        const val PREF_THEME = "theme"
        const val PREF_LANGUAGE = "language"

        const val PREF_PTT_BUTTON_HEIGHT = "pttButtonHeight"
        const val DEFAULT_PTT_BUTTON_HEIGHT = 150

        /** The DB identifier for the default certificate. @see se.lublin.mumla.db.DatabaseCertificate */
        const val PREF_CERT_ID = "certificateId"

        const val PREF_DEFAULT_USERNAME = "defaultUsername"
        const val DEFAULT_DEFAULT_USERNAME = "Mumla_User" // funny var name

        const val PREF_FORCE_TCP = "forceTcp"
        const val DEFAULT_FORCE_TCP = false

        const val PREF_USE_TOR = "useTor"
        const val DEFAULT_USE_TOR = false

        const val PREF_DISABLE_OPUS = "disableOpus"
        const val DEFAULT_DISABLE_OPUS = false

        const val PREF_MUTED = "muted"
        const val DEFAULT_MUTED = false

        const val PREF_DEAFENED = "deafened"
        const val DEFAULT_DEAFENED = false

        const val PREF_FIRST_RUN = "firstRun"
        const val DEFAULT_FIRST_RUN = true

        const val PREF_LOAD_IMAGES = "load_images"
        const val DEFAULT_LOAD_IMAGES = true

        const val PREF_FRAMES_PER_PACKET = "audio_per_packet"
        const val DEFAULT_FRAMES_PER_PACKET = "2"

        const val PREF_HALF_DUPLEX = "half_duplex"
        const val DEFAULT_HALF_DUPLEX = false

        const val PREF_HANDSET_MODE = "handset_mode"
        const val DEFAULT_HANDSET_MODE = false

        const val PREF_PTT_SOUND = "ptt_sound"
        const val DEFAULT_PTT_SOUND = false

        const val PREF_PREPROCESSOR_ENABLED = "preprocessor_enabled"
        const val DEFAULT_PREPROCESSOR_ENABLED = true

        const val PREF_ECHO_CANCELLATION_METHOD = "echo_cancellation_method"
        const val DEFAULT_ECHO_CANCELLATION_METHOD = "none"

        const val PREF_STAY_AWAKE = "stay_awake"
        const val DEFAULT_STAY_AWAKE = false

        const val PREF_SHOW_USER_COUNT = "show_user_count"
        const val DEFAULT_SHOW_USER_COUNT = false

        const val PREF_START_UP_IN_PINNED_MODE = "startUpInPinnedMode"
        const val DEFAULT_START_UP_IN_PINNED_MODE = false

        const val PREF_NEWS_SHOWN_VERSIONS = "newsShownVersions"

        @JvmStatic
        fun getInstance(context: Context): Settings = Settings(context)
    }
}
```

- [ ] **Step 4: Run the tests and the green gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.SettingsTest' && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: 15 tests pass; `BUILD SUCCESSFUL` (all Java callers — `MumlaActivity`, `MumlaService`, fragments, `CertificateGenerateActivity`, … — compile unchanged against the Kotlin class).

- [ ] **Step 5: Commit**

```bash
cd /home/becker/git/mumla
git add app/src/test/java/se/lublin/mumla/SettingsTest.kt app/src/main/java/se/lublin/mumla/Settings.kt
git rm -q --cached app/src/main/java/se/lublin/mumla/Settings.java 2>/dev/null; git add -A app/src/main/java/se/lublin/mumla
git commit -m "refactor: convert Settings to kotlin" -m "Same Java-visible API; characterization tests cover the input-method, hot-corner and threshold mappings so that later streams can add keys safely."
```

---

### Task 4: Remove the pre-API-31 code path from `CertificateExportActivity`

**Files:**
- Create: `app/src/test/java/se/lublin/mumla/preference/CertificateExportActivityTest.kt`
- Delete: `app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.java`
- Create: `app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.kt`

**Interfaces:**
- Consumes: `MumlaSQLiteDatabase(Context)`, `addCertificate(String, byte[]): DatabaseCertificate`, `getCertificates(): List<DatabaseCertificate>`, `getCertificateData(long): byte[]`, `DatabaseCertificate.getId()/getName()`; string resources `pref_export_certificate_title`, `externalStorageUnavailable`, `export_success`, `error_writing_to_storage` (all exist today).
- Produces: nothing other streams use.

`CertificateExportActivity.java:101` branches on `SDK_INT >= R`; the `else` branch (`saveCertificateClassic`, `onRequestPermissionsResult`, `WRITE_EXTERNAL_STORAGE`, `Environment.getExternalStorageDirectory()`) cannot run on API 31+. Convert first (own commit), then delete the dead branch and give the SAF request a proper MIME type.

Only the MIME type is covered by a test. Robolectric runs at SDK 36, so the `SDK_INT >= R` branch is taken whether or not the legacy code is still there — deleting it is unreachable-code removal, proven by compilation and by the `minSdk = 31` floor, not by a test.

- [ ] **Step 1: Convert to Kotlin (faithful, legacy branch included)**

`git rm app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.java`; create `app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.kt` (GPL header as in `Settings.kt`, omitted here for brevity — copy it):
```kotlin
package se.lublin.mumla.preference

import android.Manifest
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaSQLiteDatabase

class CertificateExportActivity : AppCompatActivity(), DialogInterface.OnClickListener {

    private lateinit var database: MumlaDatabase
    private lateinit var certificates: List<DatabaseCertificate>

    @Suppress("DEPRECATION")
    private val documentCreator: ActivityResultLauncher<String> =
        registerForActivityResult(CreateDocument(), ::onDocumentCreated)
    private var certificatePending: DatabaseCertificate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = MumlaSQLiteDatabase(this)
        certificates = database.certificates

        val labels = certificates.map { it.name as CharSequence }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_export_certificate_title)
            .setItems(labels, this)
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        database.close()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        val certificate = certificates[which]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            certificatePending = certificate
            documentCreator.launch(certificate.name)
        } else {
            saveCertificateClassic(certificate)
        }
    }

    private fun onDocumentCreated(uri: Uri?) {
        val pending = certificatePending
        if (uri != null && pending != null) {
            try {
                val os = contentResolver.openOutputStream(uri)
                val df = DocumentFile.fromSingleUri(this, uri)
                writeCertificate(os, pending, df?.name ?: "<unknown>")
            } catch (e: FileNotFoundException) {
                showErrorDialog(R.string.externalStorageUnavailable)
                Log.w(TAG, "FileNotFound on output file picked by user?!")
            }
        } else if (pending == null) {
            Log.w(TAG, "No pending certificate after user picked output file")
        }
        finish()
    }

    private fun saveCertificateClassic(certificate: DatabaseCertificate) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE
            )
            certificatePending = certificate
            return
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            showErrorDialog(R.string.externalStorageUnavailable)
            return
        }
        @Suppress("DEPRECATION")
        val storageDirectory = Environment.getExternalStorageDirectory()
        val mumlaDirectory = File(storageDirectory, EXTERNAL_STORAGE_DIR)
        if (!mumlaDirectory.exists() && !mumlaDirectory.mkdir()) {
            showErrorDialog(R.string.externalStorageUnavailable)
            return
        }
        val outputFile = File(mumlaDirectory, certificate.name)
        val fos = try {
            FileOutputStream(outputFile)
        } catch (e: FileNotFoundException) {
            showErrorDialog(R.string.externalStorageUnavailable)
            return
        }
        writeCertificate(fos, certificate, outputFile.absolutePath)
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val pending = certificatePending
                if (pending != null) {
                    saveCertificateClassic(pending)
                } else {
                    Log.w(TAG, "No pending certificate after permission was granted")
                }
            } else {
                Toast.makeText(this, getString(R.string.grant_perm_storage), Toast.LENGTH_LONG).show()
            }
            certificatePending = null
        }
    }

    private fun writeCertificate(fos: OutputStream?, cert: DatabaseCertificate, path: String) {
        val data = database.getCertificateData(cert.id)
        try {
            BufferedOutputStream(fos).use { it.write(data) }
            Toast.makeText(this, getString(R.string.export_success, path), Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorDialog(R.string.error_writing_to_storage)
        }
    }

    private fun showErrorDialog(resourceId: Int) {
        MaterialAlertDialogBuilder(this)
            .setMessage(resourceId)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private val TAG = CertificateExportActivity::class.java.name
        /** The name of the directory to export to on external storage. */
        private const val EXTERNAL_STORAGE_DIR = "Mumla"
        private const val PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 2
    }
}
```

- [ ] **Step 2: Build and commit the conversion**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.
```bash
cd /home/becker/git/mumla
git add -A app/src/main/java/se/lublin/mumla/preference
git commit -m "refactor: convert CertificateExportActivity to kotlin"
```

- [ ] **Step 3: Write the failing test for the PKCS#12 MIME type**

Create `app/src/test/java/se/lublin/mumla/preference/CertificateExportActivityTest.kt`:
```kotlin
package se.lublin.mumla.preference

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.db.MumlaSQLiteDatabase

@RunWith(RobolectricTestRunner::class)
class CertificateExportActivityTest {

    @Test
    fun `choosing a certificate asks the system to create a pkcs12 document named after it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MumlaSQLiteDatabase(context)
        db.addCertificate("alice.p12", byteArrayOf(1, 2, 3))
        db.close()

        val activity = Robolectric.buildActivity(CertificateExportActivity::class.java).setup().get()
        activity.onClick(null, 0)

        val started = shadowOf(activity).nextStartedActivityForResult
        assertThat(started).isNotNull()
        assertThat(started.intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(started.intent.type).isEqualTo("application/x-pkcs12")
        assertThat(started.intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("alice.p12")
    }
}
```

- [ ] **Step 4: Run it to see it fail**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.preference.CertificateExportActivityTest'`
Expected: FAIL at `intent.type`: `expected: application/x-pkcs12 but was: */*` (androidx.activity's deprecated no-arg `CreateDocument()` delegates to `this("*/*")` and `createIntent` calls `setType(mimeType)`, so the wildcard type is what the intent carries).

- [ ] **Step 5: Delete the legacy branch and set the MIME type**

In `CertificateExportActivity.kt`:
- replace the `documentCreator` declaration with
  ```kotlin
  private val documentCreator: ActivityResultLauncher<String> =
      registerForActivityResult(CreateDocument("application/x-pkcs12"), ::onDocumentCreated)
  ```
- replace `onClick` with
  ```kotlin
  override fun onClick(dialog: DialogInterface?, which: Int) {
      val certificate = certificates[which]
      certificatePending = certificate
      documentCreator.launch(certificate.name)
  }
  ```
- delete `saveCertificateClassic`, `onRequestPermissionsResult`, the constants `EXTERNAL_STORAGE_DIR` and `PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE`, and the now-unused imports `android.Manifest`, `android.content.pm.PackageManager`, `android.os.Build`, `android.os.Environment`, `androidx.core.app.ActivityCompat`, `androidx.core.content.ContextCompat`, `java.io.File`, `java.io.FileOutputStream`.

- [ ] **Step 6: Run the test and the green gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :app:testFossDebugUnitTest --tests 'se.lublin.mumla.preference.CertificateExportActivityTest' && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: PASS; `BUILD SUCCESSFUL`. (`R.string.grant_perm_storage` stays in `strings.xml`; translation files are Weblate's.)

- [ ] **Step 7: Commit**

```bash
cd /home/becker/git/mumla
git add app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.kt app/src/test/java/se/lublin/mumla/preference/CertificateExportActivityTest.kt
git commit -m "refactor: export certificates through the storage access framework only" -m "minSdk is 31, so the WRITE_EXTERNAL_STORAGE path can never run. The document request now carries the PKCS#12 MIME type."
```

---

### Task 5: F4 (part 1) — Dependency updates, guava removal, `NOTICE.md`

**Files:**
- Modify: `gradle/libs.versions.toml` (`[versions]` block)
- Modify: `libraries/humla/build.gradle` (dependencies block), `libraries/humla/src/main/java/se/lublin/humla/model/Server.java:24,187` (cross-stream, 2 lines)
- Modify: `app/src/goog/java/se/lublin/mumla/app/StartupAction.java:207-216` (Play Billing 9 API)
- Create: `NOTICE.md`

**Interfaces:**
- Consumes: catalog from Task 2.
- Produces: the final third-party versions for the whole modernization (later streams add rnnoise/webrtc entries to `NOTICE.md`).

Version decisions (all verified 2026-09-19, see Version research): AndroidX appcompat 1.8.0, activity 1.13.0, core-ktx 1.19.0, fragment 1.9.0, recyclerview 1.4.0, preference 1.2.1, exifinterface 1.4.2, documentfile 1.1.0, cardview 1.0.0; Material 1.14.0; jsoup 1.23.2 (the only API used is `Jsoup.parseBodyFragment`, unchanged); Play Billing 9.1.0 (goog flavor only; 8.0 changed `queryProductDetailsAsync`'s callback to deliver a `QueryProductDetailsResult`); minidns **1.0.5** for both `minidns-hla` and `minidns-android21` — `minidns-hla` has a 1.1.1 release but `minidns-android21` (used by `HumlaService.onCreate` via `AndroidUsingLinkProperties.setup`) has no stable 1.1.x artifact (only `1.1.0-alpha3`), and mixing minidns lines is not supported, so 1.0.5 is the latest stable *consistent* set; netcipher stays at 2.1.0 (2.2.0-alpha is a pre-release, upstream repo untouched since 2020-12 — recorded in `NOTICE.md` as required by the spec); jetbrains annotations 26.1.0. guava's single use (`InetAddresses.isInetAddress`) is replaced by the platform's `android.net.InetAddresses.isNumericAddress` (API 29+, identical semantics: literal IPv4/IPv6 without DNS).

- [ ] **Step 1: Bump the catalog**

In `gradle/libs.versions.toml` replace these `[versions]` entries (leave the rest):
```toml
androidx-appcompat = "1.8.0"
androidx-fragment = "1.9.0"
material = "1.14.0"
jsoup = "1.23.2"
billing = "9.1.0"
jetbrains-annotations = "26.1.0"
minidns = "1.0.5"
```
and delete the `guava = "28.2-android"` version and the `guava = { module = ... }` library entry.

- [ ] **Step 2: Remove guava from humla**

In `libraries/humla/build.gradle` delete the line `implementation libs.guava`.

In `libraries/humla/src/main/java/se/lublin/humla/model/Server.java` replace line 24
```java
import com.google.common.net.InetAddresses;
```
with
```java
import android.net.InetAddresses;
```
and line 187
```java
        if (InetAddresses.isInetAddress(mHost)
```
with
```java
        if (InetAddresses.isNumericAddress(mHost)
```

- [ ] **Step 3: Adapt the goog flavor to Play Billing 9**

`StartupAction.java` stays Java. The Global Constraint converts a Java file that a task changes *non-trivially*; this is a six-line adaptation of a single call site in the `goog`-only flavor (one lambda parameter, one statement, two imports) with no logic change, so it falls under the "more than a few lines" threshold. Stream P converts the file if it ever reshapes it.

In `app/src/goog/java/se/lublin/mumla/app/StartupAction.java` replace lines 207–216
```java
        billingClient.queryProductDetailsAsync(params, (queryResult, productDetails) -> {
            if ((queryResult.getResponseCode() != OK) || productDetails.isEmpty()) {
                showToast(activity, String.format("Failed to query product details: %s (code %d)", queryResult.getDebugMessage(), queryResult.getResponseCode()));
                return;
            }
            activity.runOnUiThread(() -> {
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(
                                ProductDetailsParams.newBuilder()
                                        .setProductDetails(productDetails.get(0))
```
with
```java
        billingClient.queryProductDetailsAsync(params, (queryResult, detailsResult) -> {
            List<ProductDetails> productDetails = detailsResult.getProductDetailsList();
            if ((queryResult.getResponseCode() != OK) || productDetails.isEmpty()) {
                showToast(activity, String.format("Failed to query product details: %s (code %d)", queryResult.getDebugMessage(), queryResult.getResponseCode()));
                return;
            }
            activity.runOnUiThread(() -> {
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(
                                ProductDetailsParams.newBuilder()
                                        .setProductDetails(productDetails.get(0))
```
and add the imports `import com.android.billingclient.api.ProductDetails;` (after line 22) and `import java.util.List;` (after line 28).

- [ ] **Step 4: Create `NOTICE.md`**

```markdown
# Third-party notices

Mumla is licensed under the GNU GPL v3 (see `LICENSE`). It bundles or links the
following third-party components. Every entry is GPLv3-compatible.

| Component | Version | License | Source |
|---|---|---|---|
| Kotlin standard library and compiler | bundled with AGP 9.4.1 (built-in Kotlin; `agp` in `gradle/libs.versions.toml`) | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx-coroutines | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (appcompat, activity, core, fragment, preference, recyclerview, cardview, documentfile, exifinterface) | see `gradle/libs.versions.toml` | Apache-2.0 | https://developer.android.com/jetpack |
| Material Components for Android | 1.14.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| jsoup | 1.23.2 | MIT | https://jsoup.org |
| MiniDNS | 1.0.5 | LGPL-2.1-or-later / Apache-2.0 / WTFPL (tri-licensed) | https://github.com/MiniDNS/minidns |
| NetCipher | 2.1.0 | Apache-2.0 | https://github.com/guardianproject/NetCipher — upstream has had no release since 2.1.0 (2021) and no commits since 2020-12; kept because no maintained replacement provides `OrbotHelper`. Re-evaluate when Tor integration is next touched. |
| protobuf-java | 3.11.4 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| Spongycastle | 1.51.0.0 | MIT (Bouncy Castle license) | https://rtyley.github.io/spongycastle/ — replaced by BouncyCastle in this branch |
| JetBrains annotations | 26.1.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Google Play Billing Library | 9.1.0 | Android SDK License (proprietary; `goog` flavor only, not shipped in the F-Droid `foss` build) | https://developer.android.com/google/play/billing |
| opus | 1.1 | BSD-3-Clause | https://github.com/xiph/opus |
| speex (codec + dsp, pre-split tree) | 1.2beta2+ | BSD-3-Clause | https://github.com/xiph/speex |
| CELT | 0.7.1, 0.11.1 | BSD-3-Clause | https://gitlab.com/quite/celt |
| JavaCPP | 0.7 | Apache-2.0 | https://github.com/bytedeco/javacpp — removed by the CMake migration |
```
(Later tasks update the protobuf, Spongycastle/BouncyCastle, opus, speex and JavaCPP rows.)

- [ ] **Step 5: Run the green gate plus the goog flavor**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug assembleGoogDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `grep -rn "com.google.common" app/src libraries/humla/src` prints nothing.

- [ ] **Step 6: Commit**

```bash
cd /home/becker/git/mumla
git add gradle/libs.versions.toml libraries/humla/build.gradle libraries/humla/src/main/java/se/lublin/humla/model/Server.java app/src/goog/java/se/lublin/mumla/app/StartupAction.java NOTICE.md
git commit -m "build: update androidx, material, jsoup, billing, minidns and drop guava" -m "guava's only use (InetAddresses.isInetAddress) becomes android.net.InetAddresses.isNumericAddress. minidns stays on the 1.0.x line because minidns-android21 has no stable 1.1.x release. NetCipher 2.1.0 is kept and its maintenance status recorded in NOTICE.md."
```

---

### Task 6: F4 (part 2) — Generate `Mumble.java` at build time with the protobuf Gradle plugin

**Files:**
- Modify: `gradle/libs.versions.toml`, `libraries/humla/build.gradle`
- Delete: `libraries/humla/src/main/java/se/lublin/humla/protobuf/Mumble.java` (1.4 MB generated file)
- Modify: `libraries/humla/protobuf-update-and-compile.sh` (no longer runs protoc)
- Create: `libraries/humla/src/test/java/se/lublin/humla/protobuf/MumbleProtoTest.kt`
- Modify: `NOTICE.md` (protobuf row)

**Interfaces:**
- Consumes: `libraries/humla/src/Mumble.proto` (unchanged; its trailing `option java_package = "se.lublin.humla.protobuf"; option java_outer_classname = "Mumble"; option java_multiple_files = false;` keep the generated API identical).
- Produces: `se.lublin.humla.protobuf.Mumble` generated into `libraries/humla/build/generated/source/proto/<variant>/java` by `com.google.protobuf:protoc:4.36.2`, runtime `protobuf-java:4.36.2`.

The protobuf-gradle-plugin 0.10.0 (released 2026-04-20) supports AGP 9 (the AGP 9 compatibility issues in its tracker are closed). Full (non-lite) generation is kept because `HumlaService`/`HumlaTCP` use the full `GeneratedMessage` API today.

- [ ] **Step 1: Write the wire-format round-trip test**

Create `libraries/humla/src/test/java/se/lublin/humla/protobuf/MumbleProtoTest.kt`:
```kotlin
package se.lublin.humla.protobuf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MumbleProtoTest {

    @Test
    fun `version message serializes to the expected proto2 bytes and parses back`() {
        val bytes = Mumble.Version.newBuilder()
            .setVersion(0x10305)
            .setRelease("Mumla")
            .build()
            .toByteArray()

        // field 1 (varint) 0x10305 = 66309 -> 0x85 0x86 0x04 ; field 2 (len-delimited) "Mumla"
        assertThat(bytes).isEqualTo(
            byteArrayOf(0x08, 0x85.toByte(), 0x86.toByte(), 0x04, 0x12, 0x05, 0x4d, 0x75, 0x6d, 0x6c, 0x61)
        )
        val parsed = Mumble.Version.parseFrom(bytes)
        assertThat(parsed.version).isEqualTo(0x10305)
        assertThat(parsed.release).isEqualTo("Mumla")
    }
}
```

- [ ] **Step 2: Remove the checked-in generated code and watch the build fail**

Run:
```bash
cd /home/becker/git/mumla && git rm -q libraries/humla/src/main/java/se/lublin/humla/protobuf/Mumble.java
nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.protobuf.MumbleProtoTest'
```
Expected: FAIL to compile with `package se.lublin.humla.protobuf does not exist` / `Unresolved reference: Mumble`.

- [ ] **Step 3: Add the plugin and protoc**

In `gradle/libs.versions.toml`:
- `[versions]`: change `protobuf = "3.11.4"` to `protobuf = "4.36.2"` and add `protobuf-plugin = "0.10.0"`.
- `[libraries]`: add `protobuf-protoc = { module = "com.google.protobuf:protoc", version.ref = "protobuf" }`.
- `[plugins]`: add `protobuf = { id = "com.google.protobuf", version.ref = "protobuf-plugin" }`.

In the root `build.gradle` `plugins { }` block add `alias(libs.plugins.protobuf) apply false`.

In `libraries/humla/build.gradle`:
- plugins block becomes
  ```groovy
  plugins {
      alias(libs.plugins.android.library)
      alias(libs.plugins.protobuf)
  }
  ```
- inside `android { }` add
  ```groovy
      sourceSets {
          main {
              proto {
                  // Mumble.proto lives next to src/main, not in the plugin's default src/main/proto
                  srcDir 'src'
                  include '*.proto'
              }
          }
      }
  ```
- after the `android { }` block add
  ```groovy
  protobuf {
      protoc {
          artifact = libs.protobuf.protoc.get().toString()   // com.google.protobuf:protoc:4.36.2
      }
      generateProtoTasks {
          all().configureEach { task ->
              task.builtins {
                  java { }
              }
          }
      }
  }
  ```

- [ ] **Step 4: Retire the protoc step of the update script**

Replace the last two commands of `libraries/humla/protobuf-update-and-compile.sh` (`protoc --java_out=src/main/java "$protof"` and `git diff --stat src/main/java/ "$protof"`) with
```sh
echo "Updated $protof; the Java classes are generated at build time by the protobuf Gradle plugin."
git diff --stat "$protof"
```
and change the generated header line `// Going to compile to java classes using protoc from ${protoc}.` to `// Java classes are generated at build time (protobuf-gradle-plugin, see libraries/humla/build.gradle).`; delete the `protoc=$(protoc --version)` line. Rename the file with `git mv libraries/humla/protobuf-update-and-compile.sh libraries/humla/protobuf-update.sh`.

- [ ] **Step 5: Run the test, then the green gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.protobuf.MumbleProtoTest' && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: PASS; `BUILD SUCCESSFUL`; `ls libraries/humla/build/generated/source/proto/debug/java/se/lublin/humla/protobuf/Mumble.java` exists; `git status` shows no generated file under `src/`.

- [ ] **Step 6: Update `NOTICE.md`**

Change the protobuf row to `| protobuf-java (runtime) and protoc (build only) | 4.36.2 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |`.

- [ ] **Step 7: Commit**

```bash
cd /home/becker/git/mumla
git add -A gradle/libs.versions.toml build.gradle libraries/humla/build.gradle libraries/humla/protobuf-update.sh libraries/humla/src NOTICE.md
git commit -m "build(humla): generate protobuf classes with protoc 4.36.2 at build time" -m "Mumble.java is no longer checked in; the protobuf Gradle plugin compiles src/Mumble.proto."
```

---

### Task 7: F5 — Spongycastle → BouncyCastle

**Files:**
- Modify: `gradle/libs.versions.toml`, `libraries/humla/build.gradle`
- Create: `libraries/humla/src/test/java/se/lublin/humla/net/Pkcs12CertificatesTest.kt`
- Create: `libraries/humla/src/main/java/se/lublin/humla/net/Pkcs12Certificates.kt`
- Modify: `libraries/humla/src/main/java/se/lublin/humla/net/HumlaCertificateGenerator.java:20-28,50` (imports + comment)
- Modify (cross-stream hooks): `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java:79-82`, `libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java:28,516-521`, `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:67`
- Modify: `app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java:33,104-105`
- Modify: `NOTICE.md`
- Delete: `libraries/humla/tools/mkp12.sh` (openssl `-export` encrypts the key, which is not what Mumble produces; the test fixture below is the reference now)

**Interfaces:**
- Consumes: bcprov-jdk18on / bcpkix-jdk18on 1.86.
- Produces: `object Pkcs12Certificates { @JvmStatic fun load(pkcs12: ByteArray, password: String?): KeyStore; @JvmStatic fun load(input: InputStream, password: CharArray): KeyStore }` — the one entry point for reading client certificates (stream A's `HumlaConnection` and the import activity call it).

Research result: stock BouncyCastle's `PKCS12KeyStoreSpi.engineLoad` handles `keyBag` inside an unencrypted `data` ContentInfo (`prov/.../PKCS12KeyStoreSpi.java` on `main`, the `else if (b.getBagId().equals(keyBag)) processKeyBag(b)` branch under `c[i].getContentType().equals(data)`), i.e. the same thing the Spongycastle fork patched in 2014. The test below builds a byte-for-byte Mumble-style file (unencrypted keyBag + certBag with `friendlyName`/`localKeyId` attributes, SHA-1 MAC over the empty password, mirroring `PKCS12_create("", "Mumble Identity", pkey, x509, certs, -1, -1, 0, 0, 0)` in Mumble's `src/mumble/Cert.cpp:548`) and proves it loads. Spec F5 makes a minimal Kotlin PKCS#12 reader conditional on stock BouncyCastle *not* handling that shape. It does handle it — verified against `bcgit/bc-java` `main`, `prov/src/main/java/org/bouncycastle/jcajce/provider/keystore/pkcs12/PKCS12KeyStoreSpi.java`, where `engineLoad` dispatches `else if (b.getBagId().equals(keyBag)) { processKeyBag(b); }` under the unencrypted `data` ContentInfo (see Version research) — so the fallback reader is **out of scope for this plan** and no step implements it. If the first test in step 2 nevertheless fails inside `KeyStore.load`, stop: that invalidates the premise of this task, and the fallback has to be planned as its own task (spec F5) rather than improvised here.

Global provider registration: `HumlaService` installs the provider at position 1 for "creating and managing PKCS #12 certificates". With Spongycastle the provider name was `SC`; BouncyCastle's is `BC`, which Android already registers, so `Security.insertProviderAt(new BouncyCastleProvider(), 1)` would return −1 and do nothing. Every crypto call site already passes the provider instance explicitly (`KeyStore.getInstance("PKCS12", provider)`, `JcaContentSignerBuilder.setProvider`, `JcaX509CertificateConverter.setProvider`); `MumlaTrustStore` uses the platform `BKS`, TLS uses the platform key/trust managers. The static block is therefore removed rather than changed to a `removeProvider("BC")` dance that would swap Android's own crypto out from under the platform.

- [ ] **Step 1: Switch the dependencies**

In `gradle/libs.versions.toml`: delete the `spongycastle` version and the three `spongycastle-*` libraries; add
```toml
bouncycastle = "1.86"
```
under `[versions]` and
```toml
bouncycastle-prov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
bouncycastle-pkix = { module = "org.bouncycastle:bcpkix-jdk18on", version.ref = "bouncycastle" }
```
under `[libraries]`. In `libraries/humla/build.gradle` replace the three `api libs.spongycastle.*` lines with
```groovy
    api libs.bouncycastle.prov
    api libs.bouncycastle.pkix
```

- [ ] **Step 2: Write the failing tests**

Create `libraries/humla/src/test/java/se/lublin/humla/net/Pkcs12CertificatesTest.kt`:
```kotlin
package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.util.Date

class Pkcs12CertificatesTest {

    @Test
    fun `loads a mumble style pkcs12 whose private key sits in an unencrypted keyBag`() {
        val keyPair = rsaKeyPair()
        val cert = selfSigned(keyPair, "CN=Mumble Test")

        val store = Pkcs12Certificates.load(mumbleStylePkcs12(keyPair, cert), null)

        val alias = store.aliases().toList().single()
        assertThat(store.isKeyEntry(alias)).isTrue()
        assertThat(store.getKey(alias, CharArray(0))).isInstanceOf(RSAPrivateKey::class.java)
        val loadedCert = store.getCertificate(alias) as X509Certificate
        assertThat(loadedCert.subjectX500Principal.name).isEqualTo("CN=Mumble Test")
        assertThat(loadedCert.encoded).isEqualTo(cert.encoded)
    }

    @Test
    fun `a certificate written by HumlaCertificateGenerator loads back with its key`() {
        val out = ByteArrayOutputStream()
        val generated = HumlaCertificateGenerator.generateCertificate(out)

        val store = Pkcs12Certificates.load(out.toByteArray(), "")

        assertThat(store.getCertificate("Humla Key").encoded).isEqualTo(generated.encoded)
        assertThat(store.getKey("Humla Key", CharArray(0))).isInstanceOf(RSAPrivateKey::class.java)
    }

    @Test
    fun `a null password is treated as the empty password`() {
        val keyPair = rsaKeyPair()
        val cert = selfSigned(keyPair, "CN=Mumble Test")
        val bytes = mumbleStylePkcs12(keyPair, cert)

        val viaNull = Pkcs12Certificates.load(bytes, null)
        val viaEmpty = Pkcs12Certificates.load(bytes, "")

        assertThat(viaNull.aliases().toList()).isEqualTo(viaEmpty.aliases().toList())
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun selfSigned(keyPair: KeyPair, dn: String): X509Certificate {
        val provider = BouncyCastleProvider()
        val name = X500Name(dn)
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 24L * 60 * 60 * 1000)
        val holder = X509v3CertificateBuilder(
            name, BigInteger.ONE, notBefore, notAfter, name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private))
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
    }

    /**
     * Mirrors what Mumble's Cert.cpp writes with
     * PKCS12_create("", "Mumble Identity", pkey, x509, certs, -1, -1, 0, 0, 0):
     * an unencrypted keyBag and certBag in plain `data` ContentInfos, both tagged with
     * friendlyName and localKeyId, and a MAC computed over the empty password.
     */
    private fun mumbleStylePkcs12(keyPair: KeyPair, cert: X509Certificate): ByteArray {
        val provider = BouncyCastleProvider()
        val friendlyName = DERBMPString("Mumble Identity")
        val localKeyId = DEROctetString(MessageDigest.getInstance("SHA-1").digest(keyPair.public.encoded))
        val keyBag = PKCS12SafeBagBuilder(PrivateKeyInfo.getInstance(keyPair.private.encoded))
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val certBag = JcaPKCS12SafeBagBuilder(cert)
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val pfx = PKCS12PfxPduBuilder()
            .addData(keyBag)
            .addData(certBag)
            .build(JcePKCS12MacCalculatorBuilder().setProvider(provider), CharArray(0))
        return pfx.getEncoded(ASN1Encoding.DL)
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.net.Pkcs12CertificatesTest'`
Expected: FAIL to compile: `Unresolved reference: Pkcs12Certificates` (and, in `HumlaCertificateGenerator.java`, `package org.spongycastle... does not exist` — fixed in step 5).

- [ ] **Step 4: Implement `Pkcs12Certificates`**

Create `libraries/humla/src/main/java/se/lublin/humla/net/Pkcs12Certificates.kt` (GPL header as in `Settings.kt`):
```kotlin
package se.lublin.humla.net

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException

/**
 * Reads PKCS#12 client certificates: the ones Mumble writes (unencrypted keyBag and certBag,
 * MAC over the empty password) and the ones [HumlaCertificateGenerator] writes.
 *
 * Always uses BouncyCastle's own PKCS#12 implementation (passed as a provider instance, no
 * global registration) because Android's stripped-down "BC" provider rejects plain keyBags.
 */
object Pkcs12Certificates {

    @JvmStatic
    @Throws(KeyStoreException::class, IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
    fun load(pkcs12: ByteArray, password: String?): KeyStore =
        load(ByteArrayInputStream(pkcs12), password?.toCharArray() ?: CharArray(0))

    @JvmStatic
    @Throws(KeyStoreException::class, IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
    fun load(input: InputStream, password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12", BouncyCastleProvider())
        store.load(input, password)
        return store
    }
}
```

- [ ] **Step 5: Move the remaining code from Spongycastle to BouncyCastle**

All four Java files below keep their language. Every edit is a package rename in an import line (plus, in `HumlaService`, the deletion of a four-line static block and in `HumlaConnection`/`CertificateImportActivity` the replacement of a `KeyStore.getInstance` pair by one call); none of them is the "more than a few lines" non-trivial change that the Global Constraint converts to Kotlin first. `HumlaCertificateGenerator.java` in particular changes nine import lines and one comment and no code at all.

`libraries/humla/src/main/java/se/lublin/humla/net/HumlaCertificateGenerator.java`: replace lines 20–28
```java
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.operator.ContentSigner;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder;
```
with the same nine lines using `org.bouncycastle.` instead of `org.spongycastle.`, and on line 50 change the comment `// Use SpongyCastle provider, supports creating X509 certs` to `// BouncyCastle provider instance: supports creating X509 certs and PKCS#12 stores`.

`libraries/humla/src/main/java/se/lublin/humla/HumlaService.java`: delete lines 79–82
```java
    static {
        // Use Spongy Castle for crypto implementation so we can create and manage PKCS #12 (.p12) certificates.
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }
```
and the now unused `import java.security.Security;`.

`libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java`: delete line 28 `import org.spongycastle.jce.provider.BouncyCastleProvider;`; replace lines 516–521
```java
            KeyStore keyStore = null;
            if(mCertificate != null) {
                keyStore = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
                ByteArrayInputStream inputStream = new ByteArrayInputStream(mCertificate);
                keyStore.load(inputStream, mCertificatePassword != null ?
                        mCertificatePassword.toCharArray() : new char[0]);
            }
```
with
```java
            KeyStore keyStore = null;
            if(mCertificate != null) {
                keyStore = Pkcs12Certificates.load(mCertificate, mCertificatePassword);
            }
```
(`java.io.ByteArrayInputStream` stays imported only if still used elsewhere in the file; otherwise delete the import.)

`app/src/main/java/se/lublin/mumla/app/MumlaActivity.java` line 67: `import org.spongycastle.util.encoders.Hex;` → `import org.bouncycastle.util.encoders.Hex;`.

`app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java`: replace line 33 `import org.spongycastle.jce.provider.BouncyCastleProvider;` with `import se.lublin.humla.net.Pkcs12Certificates;` and lines 104–105
```java
            keyStore = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
            keyStore.load(input, password);
```
with
```java
            keyStore = Pkcs12Certificates.load(input, password);
```

Delete `libraries/humla/tools/mkp12.sh` (`git rm`).

- [ ] **Step 6: Run the tests and the green gate**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.net.Pkcs12CertificatesTest' && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: 3 tests PASS; `BUILD SUCCESSFUL`; `grep -rn spongycastle app/src libraries/humla/src gradle build.gradle app/build.gradle libraries/humla/build.gradle` prints nothing.

- [ ] **Step 7: Update `NOTICE.md`**

Replace the Spongycastle row with `| Bouncy Castle (bcprov-jdk18on, bcpkix-jdk18on) | 1.86 | MIT (Bouncy Castle license) | https://www.bouncycastle.org/ |`.

- [ ] **Step 8: Commit**

```bash
cd /home/becker/git/mumla
git add -A gradle/libs.versions.toml libraries/humla NOTICE.md app/src/main/java/se/lublin/mumla/app/MumlaActivity.java app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java
git commit -m "refactor: replace spongycastle with bouncycastle" -m "Stock BouncyCastle 1.86 reads Mumble's unencrypted PKCS#12 keyBag files, which is what the old humla-spongycastle fork patched in. Pkcs12Certificates is the single loader; the global provider registration in HumlaService is dropped because every call site passes the provider explicitly and Android already owns the name BC."
```

---

### Task 8: F6 (part 1) — Convert the native call sites to Kotlin (faithful, still on javacpp)

**Files:**
- Delete (Java) / Create (Kotlin), same directory `libraries/humla/src/main/java/se/lublin/humla/audio/encoder/`: `OpusEncoder`, `CELT7Encoder`, `CELT11Encoder`, `PreprocessingEncoder`, `ResamplingEncoder`
- Delete (Java) / Create (Kotlin): `libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech`

**Interfaces:**
- Consumes: `IEncoder` (Java, unchanged), `IDecoder` (Java, unchanged), `IAudioMixerSource<T> { T getSamples(); int getNumSamples(); }`, `PacketBuffer`, `User.getSession()/getAverageAvailable()/setAverageAvailable(float)`, `AudioHandler.SAMPLE_RATE = 48000`, `AudioHandler.FRAME_SIZE = 480`, the javacpp classes `Opus`, `Speex`, `CELT7`, `CELT11` (deleted in Task 9).
- Produces: identical Java-visible constructors and methods, so `AudioHandler.java` (`new CELT7Encoder(SAMPLE_RATE, FRAME_SIZE, 1, mFramesPerPacket, mBitrate, MAX_BUFFER_SIZE)`, `new CELT11Encoder(SAMPLE_RATE, 1, mFramesPerPacket)`, `new OpusEncoder(SAMPLE_RATE, 1, FRAME_SIZE, mFramesPerPacket, mBitrate, MAX_BUFFER_SIZE)`, `new PreprocessingEncoder(encoder, FRAME_SIZE, SAMPLE_RATE)`, `new ResamplingEncoder(encoder, 1, rate, FRAME_SIZE, SAMPLE_RATE)`) and `AudioOutput.java` (`new AudioOutputSpeech(user, messageType, mBufferSize, this)`, `AudioOutputSpeech.TalkStateListener`, `AudioOutputSpeech.Result`, `result.isAlive()`, `result.getSpeechOutput()`, `speech.getUser()`, `speech.getSession()`, `speech.getCodec()`, `speech.destroy()`, `speech.addFrameToBuffer(...)`, `speech.setRequestedSamples(int)`) compile unchanged.

These six files belong to stream B (`audio/**`), and this task deletes and re-creates all of them; they are listed in **Cross-stream touches** for that reason. They are also the only Java users of the javacpp bindings besides `HumlaService:356`. Java cannot even name the target package `se.lublin.humla.audio.native` (`native` is a Java keyword), so they must be Kotlin before Task 9 rewires them. This task is a pure conversion — no behavior change — and one commit. The only intentional deviation: `AudioOutputSpeech`'s codec `switch` had no `default` (an unsupported codec left the decoder `null` and crashed with an NPE on first decode); the Kotlin `when` throws `NativeAudioException` from the constructor, which `AudioOutput` already catches.

- [ ] **Step 1: Convert `OpusEncoder`**

`git rm libraries/humla/src/main/java/se/lublin/humla/audio/encoder/OpusEncoder.java`; create `OpusEncoder.kt` in the same directory (GPL header as in `Settings.kt` on every new file in this task):
```kotlin
package se.lublin.humla.audio.encoder

import com.googlecode.javacpp.IntPointer
import com.googlecode.javacpp.Pointer
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.util.Arrays
import se.lublin.humla.audio.javacpp.Opus
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class OpusEncoder(
    sampleRate: Int,
    channels: Int,
    private val frameSize: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
) : IEncoder {
    private val buffer = ByteArray(maxBufferSize)
    private val audioBuffer = ShortArray(framesPerPacket * frameSize)

    // Stateful
    private var bufferedFrames = 0
    private var encodedLength = 0
    private var terminated = false

    private val state: Pointer

    init {
        val error = IntPointer(1)
        error.put(0)
        state = Opus.opus_encoder_create(sampleRate, channels, Opus.OPUS_APPLICATION_VOIP, error)
        if (error.get() < 0) throw NativeAudioException("Opus encoder initialization failed with error: " + error.get())
        Opus.opus_encoder_ctl(state, Opus.OPUS_SET_VBR_REQUEST, 0)
        Opus.opus_encoder_ctl(state, Opus.OPUS_SET_BITRATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        if (inputSize != frameSize) {
            throw IllegalArgumentException("This Opus encoder implementation requires a constant frame size.")
        }
        terminated = false
        System.arraycopy(input, 0, audioBuffer, frameSize * bufferedFrames, frameSize)
        bufferedFrames++
        return if (bufferedFrames == framesPerPacket) encodePacket() else 0
    }

    @Throws(NativeAudioException::class)
    private fun encodePacket(): Int {
        if (bufferedFrames < framesPerPacket) {
            // If encoding is done before enough frames are buffered, fill rest of packet.
            Arrays.fill(audioBuffer, frameSize * bufferedFrames, audioBuffer.size, 0.toShort())
            bufferedFrames = framesPerPacket
        }
        val result = Opus.opus_encode(state, audioBuffer, frameSize * bufferedFrames, buffer, buffer.size)
        if (result < 0) throw NativeAudioException("Opus encoding failed with error: $result")
        encodedLength = result
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = encodedLength > 0

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!isReady()) throw BufferUnderflowException()
        var size = encodedLength
        if (terminated) size = size or (1 shl 13)
        packetBuffer.writeLong(size.toLong())
        packetBuffer.append(buffer, encodedLength)
        bufferedFrames = 0
        encodedLength = 0
        terminated = false
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        terminated = true
        if (bufferedFrames > 0 && !isReady()) {
            // Perform encode operation on remaining audio if available.
            encodePacket()
        }
    }

    fun getBitrate(): Int {
        val ptr = IntPointer(1)
        Opus.opus_encoder_ctl(state, Opus.OPUS_GET_BITRATE_REQUEST, ptr)
        return ptr.get()
    }

    override fun destroy() {
        Opus.opus_encoder_destroy(state)
    }
}
```

- [ ] **Step 2: Convert `CELT7Encoder`**

```kotlin
package se.lublin.humla.audio.encoder

import com.googlecode.javacpp.IntPointer
import com.googlecode.javacpp.Pointer
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import kotlin.math.min
import se.lublin.humla.audio.javacpp.CELT7
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class CELT7Encoder(
    sampleRate: Int,
    frameSize: Int,
    channels: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
) : IEncoder {
    private val bufferSize = min(maxBufferSize, bitrate / 800)
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private val packetLengths = IntArray(framesPerPacket)
    private var bufferedFrames = 0
    private var ready = false

    private val mode: Pointer
    private val state: Pointer

    init {
        val error = IntPointer(1)
        error.put(0)
        mode = CELT7.celt_mode_create(sampleRate, frameSize, error)
        if (error.get() < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: " + error.get())
        state = CELT7.celt_encoder_create(mode, channels, error)
        if (error.get() < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: " + error.get())
        CELT7.celt_encoder_ctl(state, CELT7.CELT_SET_PREDICTION_REQUEST, 0)
        CELT7.celt_encoder_ctl(state, CELT7.CELT_SET_VBR_RATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = CELT7.celt_encode(state, input, null, buffer[bufferedFrames], bufferSize)
        if (result < 0) throw NativeAudioException("CELT 0.7.0 encoding failed with error: $result")
        packetLengths[bufferedFrames] = result
        bufferedFrames++
        if (bufferedFrames >= framesPerPacket) ready = true
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = ready && bufferedFrames > 0

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!ready) throw BufferUnderflowException()
        for (x in 0 until bufferedFrames) {
            val frame = buffer[x]
            val length = packetLengths[x]
            var head = length
            if (x < bufferedFrames - 1) head = head or 0x80
            packetBuffer.append(head.toLong())
            packetBuffer.append(frame, length)
        }
        bufferedFrames = 0
        ready = false
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        ready = true
    }

    override fun destroy() {
        CELT7.celt_encoder_destroy(state)
        CELT7.celt_mode_destroy(mode)
    }
}
```

- [ ] **Step 3: Convert `CELT11Encoder`**

```kotlin
package se.lublin.humla.audio.encoder

import com.googlecode.javacpp.IntPointer
import com.googlecode.javacpp.Pointer
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import se.lublin.humla.audio.javacpp.CELT11
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class CELT11Encoder(
    sampleRate: Int,
    channels: Int,
    private val framesPerPacket: Int,
) : IEncoder {
    private val bufferSize = sampleRate / 800
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private var bufferedFrames = 0

    private val state: Pointer

    init {
        val error = IntPointer(1)
        error.put(0)
        state = CELT11.celt_encoder_create(sampleRate, channels, error)
        if (error.get() < 0) throw NativeAudioException("CELT 0.11.0 encoder initialization failed with error: " + error.get())
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = CELT11.celt_encode(state, input, inputSize, buffer[bufferedFrames], bufferSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 encoding failed with error: $result")
        bufferedFrames++
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = bufferedFrames == framesPerPacket

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (bufferedFrames < framesPerPacket) throw BufferUnderflowException()
        for (x in 0 until bufferedFrames) {
            val frame = buffer[x]
            var head = frame.size
            if (x < bufferedFrames - 1) head = head or 0x80
            packetBuffer.append(head.toLong())
            packetBuffer.append(frame, frame.size)
        }
        bufferedFrames = 0
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        // The CELT 0.11 encoder has no partial-packet flush; kept as before.
    }

    override fun destroy() {
        CELT11.celt_encoder_destroy(state)
    }
}
```

- [ ] **Step 4: Convert `PreprocessingEncoder`**

```kotlin
package se.lublin.humla.audio.encoder

import com.googlecode.javacpp.IntPointer
import java.nio.BufferUnderflowException
import se.lublin.humla.audio.javacpp.Speex
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class PreprocessingEncoder(
    private var encoder: IEncoder,
    frameSize: Int,
    sampleRate: Int,
) : IEncoder {
    private val preprocessor = Speex.SpeexPreprocessState(frameSize, sampleRate)

    init {
        val arg = IntPointer(1)
        arg.put(0)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_VAD, arg)
        arg.put(1)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_AGC, arg)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_DENOISE, arg)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_DEREVERB, arg)
        arg.put(30000)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_SET_AGC_TARGET, arg)
        // Increase VAD difficulty. NOTE: the request id is GET_PROB_START, as in the Java
        // original; stream B (spec B9) corrects this to SET_PROB_START.
        arg.put(99)
        preprocessor.control(Speex.SpeexPreprocessState.SPEEX_PREPROCESS_GET_PROB_START, arg)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        preprocessor.preprocess(input)
        return encoder.encode(input, inputSize)
    }

    override fun getBufferedFrames(): Int = encoder.getBufferedFrames()

    override fun isReady(): Boolean = encoder.isReady()

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) = encoder.getEncodedData(packetBuffer)

    @Throws(NativeAudioException::class)
    override fun terminate() = encoder.terminate()

    fun setEncoder(encoder: IEncoder) {
        this.encoder.destroy()
        this.encoder = encoder
    }

    override fun destroy() {
        preprocessor.destroy()
        encoder.destroy()
    }
}
```

- [ ] **Step 5: Convert `ResamplingEncoder`**

```kotlin
package se.lublin.humla.audio.encoder

import java.nio.BufferUnderflowException
import se.lublin.humla.audio.javacpp.Speex
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class ResamplingEncoder(
    private var encoder: IEncoder,
    channels: Int,
    inputSampleRate: Int,
    private val targetFrameSize: Int,
    targetSampleRate: Int,
) : IEncoder {
    private val resampleBuffer = ShortArray(targetFrameSize)
    private val resampler = Speex.SpeexResampler(channels, inputSampleRate, targetSampleRate, SPEEX_RESAMPLE_QUALITY)

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        resampler.resample(input, resampleBuffer)
        return encoder.encode(resampleBuffer, targetFrameSize)
    }

    override fun getBufferedFrames(): Int = encoder.getBufferedFrames()

    override fun isReady(): Boolean = encoder.isReady()

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) = encoder.getEncodedData(packetBuffer)

    @Throws(NativeAudioException::class)
    override fun terminate() = encoder.terminate()

    fun setEncoder(encoder: IEncoder) {
        this.encoder.destroy()
        this.encoder = encoder
    }

    override fun destroy() {
        resampler.destroy()
        encoder.destroy()
    }

    private companion object {
        const val SPEEX_RESAMPLE_QUALITY = 3
    }
}
```

- [ ] **Step 6: Convert `AudioOutputSpeech`**

`git rm libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.java`; create `AudioOutputSpeech.kt`:
```kotlin
package se.lublin.humla.audio

import com.googlecode.javacpp.IntPointer
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Queue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.sin
import se.lublin.humla.audio.javacpp.CELT11
import se.lublin.humla.audio.javacpp.CELT7
import se.lublin.humla.audio.javacpp.Opus
import se.lublin.humla.audio.javacpp.Speex
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

/** Decodes one user's incoming voice stream through a jitter buffer into float PCM. */
class AudioOutputSpeech @Throws(NativeAudioException::class) constructor(
    private val user: User,
    private val codec: HumlaUDPMessageType,
    private var requestedSamples: Int,
    private val talkStateListener: TalkStateListener,
) : Callable<AudioOutputSpeech.Result> {

    fun interface TalkStateListener {
        fun onTalkStateUpdated(session: Int, state: TalkState)
    }

    private val decoder: IDecoder
    private val jitterBuffer: Speex.JitterBuffer
    private val jitterLock = Any()
    private var audioBufferSize = AudioHandler.FRAME_SIZE

    // State-specific
    private var buffer: FloatArray
    private val out: FloatArray
    private val fadeOut = FloatArray(AudioHandler.FRAME_SIZE)
    private val fadeIn = FloatArray(AudioHandler.FRAME_SIZE)
    private val frames: Queue<ByteBuffer> = ConcurrentLinkedQueue()
    private var missCount = 0
    private var hasTerminator = false
    private var lastAlive = true
    private var bufferFilled = 0
    private var lastConsume = 0
    private var ucFlags = 0
    private val avail = IntPointer(1)

    init {
        decoder = when (codec) {
            HumlaUDPMessageType.UDPVoiceOpus -> {
                audioBufferSize *= 12
                Opus.OpusDecoder(AudioHandler.SAMPLE_RATE, 1)
            }
            HumlaUDPMessageType.UDPVoiceCELTBeta -> CELT11.CELT11Decoder(AudioHandler.SAMPLE_RATE, 1)
            HumlaUDPMessageType.UDPVoiceCELTAlpha -> CELT7.CELT7Decoder(AudioHandler.SAMPLE_RATE, AudioHandler.FRAME_SIZE, 1)
            HumlaUDPMessageType.UDPVoiceSpeex -> Speex.SpeexDecoder()
            else -> throw NativeAudioException("No decoder for codec $codec")
        }

        // Larger initial buffer so we can save performance by not resizing at runtime.
        buffer = FloatArray(audioBufferSize * 2)
        out = FloatArray(audioBufferSize)

        // Sine function to represent fade in/out. Period is FRAME_SIZE.
        val mul = (Math.PI / (2.0 * AudioHandler.FRAME_SIZE)).toFloat()
        for (i in 0 until AudioHandler.FRAME_SIZE) {
            val v = sin((i * mul).toDouble()).toFloat()
            fadeIn[i] = v
            fadeOut[AudioHandler.FRAME_SIZE - i - 1] = v
        }

        jitterBuffer = Speex.JitterBuffer(AudioHandler.FRAME_SIZE)
        val margin = IntPointer(1)
        margin.put(10 * AudioHandler.FRAME_SIZE)
        jitterBuffer.control(Speex.JitterBuffer.JITTER_BUFFER_SET_MARGIN, margin)
    }

    fun addFrameToBuffer(pb: PacketBuffer, flags: Byte, seq: Int) {
        if (pb.capacity() < 2) return

        synchronized(jitterLock) {
            try {
                var samples = 0
                if (codec == HumlaUDPMessageType.UDPVoiceOpus) {
                    val header = pb.readLong()
                    val size = (header and ((1L shl 13) - 1)).toInt()
                    if (size > 0) {
                        val data = pb.dataBlock(size)
                        if (data.size != size) return
                        val frameCount = Opus.opus_packet_get_nb_frames(data, size)
                        samples = frameCount * Opus.opus_packet_get_samples_per_frame(data, AudioHandler.SAMPLE_RATE)
                    } else {
                        return
                    }
                } else {
                    try {
                        var header: Int
                        do {
                            header = pb.next()
                            samples += AudioHandler.FRAME_SIZE
                            pb.skip(header and 0x7f)
                        } while ((header and 0x80) > 0)
                    } catch (e: BufferUnderflowException) {
                        // reached end of buffer
                    }
                }
                pb.rewind()

                val size = pb.left()
                val data = pb.dataBlock(size)
                val packet = Speex.JitterBufferPacket(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags.toInt())
                jitterBuffer.put(packet)
            } catch (e: BufferOverflowException) {
                e.printStackTrace()
            }
        }
    }

    @Throws(Exception::class)
    override fun call(): Result {
        if (bufferFilled - lastConsume > 0) {
            // Shift over the remaining unconsumed data in the buffer.
            System.arraycopy(buffer, lastConsume, buffer, 0, bufferFilled - lastConsume)
        }
        bufferFilled -= lastConsume
        lastConsume = requestedSamples

        if (bufferFilled >= requestedSamples) return Result(this, lastAlive, buffer, bufferFilled)

        var nextAlive = lastAlive

        while (bufferFilled < requestedSamples) {
            var decodedSamples = AudioHandler.FRAME_SIZE
            resizeBuffer(bufferFilled + audioBufferSize)

            if (!lastAlive) {
                Arrays.fill(out, 0f)
            } else {
                avail.put(0)
                val ts = synchronized(jitterLock) {
                    val t = jitterBuffer.pointerTimestamp
                    jitterBuffer.control(Speex.JitterBuffer.JITTER_BUFFER_GET_AVAILABLE_COUNT, avail)
                    t
                }
                val availPackets = avail.get().toFloat()

                // Make sure that we have enough packets in the jitter buffer before we even begin
                // decoding, based on the average # of packets available. Prevents a metallic
                // 'twang' when the user starts talking, caused by buffer underrun. The official
                // Mumble project uses the same technique.
                if (ts == 0) {
                    val want = ceil(user.averageAvailable.toDouble()).toInt()
                    if (availPackets < want) {
                        missCount++
                        if (missCount < 20) {
                            Arrays.fill(out, 0f)
                            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
                            bufferFilled += decodedSamples
                            continue
                        }
                    }
                }

                if (frames.isEmpty()) {
                    val packet = ByteBuffer.allocateDirect(4096)
                    val jbp = Speex.JitterBufferPacket(packet, 4096, 0, 0, 0, 0)
                    val result = synchronized(jitterLock) { jitterBuffer.get(jbp, null) }

                    if (result == Speex.JitterBuffer.JITTER_BUFFER_OK) {
                        packet.limit(jbp.length)
                        val pb = PacketBuffer(packet)

                        missCount = 0
                        ucFlags = jbp.userData
                        hasTerminator = false
                        try {
                            if (codec == HumlaUDPMessageType.UDPVoiceOpus) {
                                val header = pb.readLong()
                                val size = (header and ((1L shl 13) - 1)).toInt()
                                hasTerminator = (header and (1L shl 13)) > 0
                                frames.add(pb.bufferBlock(size))
                            } else {
                                var header: Int
                                do {
                                    header = pb.next()
                                    val size = header and 0x7f
                                    if (header > 0) {
                                        frames.add(pb.bufferBlock(size))
                                    } else {
                                        hasTerminator = true
                                    }
                                } while ((header and 0x80) > 0)
                            }
                        } catch (e: BufferOverflowException) {
                            e.printStackTrace()
                        } catch (e: BufferUnderflowException) {
                            e.printStackTrace()
                        }

                        if (availPackets >= user.averageAvailable) {
                            user.averageAvailable = availPackets
                        } else {
                            user.averageAvailable = user.averageAvailable * 0.99f
                        }
                    } else {
                        synchronized(jitterLock) { jitterBuffer.updateDelay(jbp, null) }
                        missCount++
                        if (missCount > 10) nextAlive = false
                    }
                }

                try {
                    if (!frames.isEmpty()) {
                        val data = frames.poll()
                        decodedSamples = decoder.decodeFloat(data, data.limit(), out, audioBufferSize)
                        if (frames.isEmpty()) {
                            synchronized(jitterLock) { jitterBuffer.updateDelay(null, IntPointer(1)) }
                        }
                        if (frames.isEmpty() && hasTerminator) nextAlive = false
                    } else {
                        decodedSamples = decoder.decodeFloat(null, 0, out, AudioHandler.FRAME_SIZE)
                    }
                } catch (e: NativeAudioException) {
                    e.printStackTrace()
                    decodedSamples = AudioHandler.FRAME_SIZE
                }

                if (!nextAlive) {
                    for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeOut[i]
                } else if (ts == 0) {
                    for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeIn[i]
                }

                synchronized(jitterLock) {
                    repeat(decodedSamples / AudioHandler.FRAME_SIZE) { jitterBuffer.tick() }
                }
            }

            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
            bufferFilled += decodedSamples
        }

        if (!nextAlive) ucFlags = 0xFF

        val talkState = when (ucFlags) {
            0 -> TalkState.TALKING
            1 -> TalkState.SHOUTING
            0xFF -> TalkState.PASSIVE
            else -> TalkState.WHISPERING
        }
        talkStateListener.onTalkStateUpdated(user.session, talkState)

        val tmp = lastAlive
        lastAlive = nextAlive
        return Result(this, tmp, buffer, requestedSamples)
    }

    private fun resizeBuffer(newSize: Int) {
        if (newSize > buffer.size) buffer = Arrays.copyOf(buffer, newSize)
    }

    /** Sets the preferred number of samples to return when the callable is executed. */
    fun setRequestedSamples(samples: Int) {
        requestedSamples = samples
    }

    fun getCodec(): HumlaUDPMessageType = codec

    fun getUser(): User = user

    fun getSession(): Int = user.session

    /** Cleans up all native resources linked to this instance. MUST be called eventually. */
    fun destroy() {
        decoder.destroy()
        jitterBuffer.destroy()
    }

    /** The outcome of a decoding pass. */
    class Result internal constructor(
        private val speechOutput: AudioOutputSpeech,
        private val alive: Boolean,
        private val samples: FloatArray,
        private val numSamples: Int,
    ) : IAudioMixerSource<FloatArray> {
        fun getSpeechOutput(): AudioOutputSpeech = speechOutput
        fun isAlive(): Boolean = alive
        override fun getSamples(): FloatArray = samples
        override fun getNumSamples(): Int = numSamples
    }
}
```

- [ ] **Step 7: Build, run existing tests, commit**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: `BUILD SUCCESSFUL` (`AudioHandler.java` and `AudioOutput.java` compile against the Kotlin classes unchanged).
```bash
cd /home/becker/git/mumla
git add -A libraries/humla/src/main/java/se/lublin/humla/audio
git commit -m "refactor(humla): convert audio encoders and AudioOutputSpeech to kotlin"
```

---

### Task 9: F6 (part 2) — CMake native build, hand-written JNI, Kotlin `external` wrappers, javacpp and ndk-build removed

**Files:**
- Move (submodules, `git mv`): `libraries/humla/src/main/jni/opus` → `libraries/humla/src/main/cpp/third_party/opus` (then updated to `v1.6.1`), `.../jni/celt-0.7.0-src` → `.../cpp/third_party/celt-0.7.0`, `.../jni/celt-0.11.0-src` → `.../cpp/third_party/celt-0.11.0`
- Replace submodule: `libraries/humla/src/main/jni/speex` (pre-split snapshot) → `libraries/humla/src/main/cpp/third_party/speex` (`Speex-1.2.1`) + `libraries/humla/src/main/cpp/third_party/speexdsp` (`SpeexDSP-1.2.1`)
- Move: `libraries/humla/src/main/jni/celt-0.7.0-build/config.h` → `libraries/humla/src/main/cpp/celt-0.7.0-build/config.h`, same for `celt-0.11.0-build`
- Delete: `libraries/humla/src/main/jni/` (Android.mk, Application.mk, jnicelt11.cpp, jnicelt7.cpp, jniopus.cpp, jnispeex.cpp), `libraries/humla/src/main/java/se/lublin/humla/audio/javacpp/` (CELT11.java, CELT7.java, Opus.java, Speex.java), `libraries/humla/tools/javacpp-0.7.jar`, `libraries/humla/tools/jnigen.sh`
- Create: `libraries/humla/src/main/cpp/CMakeLists.txt`, `jni_handle.h`, `jni_opus.cpp`, `jni_speex.cpp`, `jni_speexdsp.cpp`, `jni_celt7.cpp`, `jni_celt11.cpp`
- Create: `libraries/humla/src/main/java/se/lublin/humla/audio/native/{OpusEncoderNative,OpusDecoderNative,SpeexPreprocessNative,SpeexResamplerNative,SpeexJitterNative,SpeexDecoderNative,Celt7Native,Celt11Native}.kt`
- Create: `libraries/humla/src/main/java/se/lublin/humla/audio/{PacketBytes,OpusDecoder,CELT7Decoder,CELT11Decoder,SpeexDecoder,SpeexJitterBuffer}.kt`
- Modify: the five encoder `.kt` files and `AudioOutputSpeech.kt` from Task 8
- Create tests: `libraries/humla/src/test/java/se/lublin/humla/audio/encoder/{RecordingEncoder,OpusEncoderTest,CELT7EncoderTest,CELT11EncoderTest,PreprocessingEncoderTest,ResamplingEncoderTest}.kt`, `libraries/humla/src/test/java/se/lublin/humla/audio/{FakeJitter,SpeexJitterBufferTest,SpeexDecoderTest,AudioOutputSpeechTest}.kt`
- Modify: `libraries/humla/build.gradle`, `gradle/libs.versions.toml` (drop javacpp), `libraries/humla/.gitignore`, `.gitmodules` (via git), `NOTICE.md`
- Modify (cross-stream hook): `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java:49,356`

**Interfaces:**
- Consumes: Task 8's Kotlin call sites; upstream opus CMake (`add_subdirectory`, options `OPUS_BUILD_SHARED_LIBRARY`, `OPUS_FIXED_POINT`, `OPUS_BUILD_PROGRAMS`, `OPUS_BUILD_TESTING`, `OPUS_INSTALL_*`; DNN/DRED/OSCE all default OFF in 1.6.1 so no model files are needed); speex 1.2.1 and speexdsp 1.2.1 source lists from their `Makefile.am`; the pre-generated celt `config.h` files.
- Produces (used by stream B): package `se.lublin.humla.audio.native` with one fakeable interface + one `object` per library: `OpusEncoderApi`/`OpusEncoderNative`, `OpusDecoderApi`/`OpusDecoderNative`, `SpeexPreprocessApi`/`SpeexPreprocessNative`, `SpeexResamplerApi`/`SpeexResamplerNative`, `SpeexJitterApi`/`SpeexJitterNative`, `SpeexDecoderApi`/`SpeexDecoderNative`, `Celt7Api`/`Celt7Native`, `Celt11Api`/`Celt11Native` (exact signatures below); shared libraries `libhumla_opus.so`, `libhumla_speex.so`, `libhumla_speexdsp.so`, `libhumla_celt7.so`, `libhumla_celt11.so`; `CELT7Encoder.getBitstreamVersion()` (Java-callable); every encoder/decoder constructor takes an optional trailing `api` parameter (`@JvmOverloads`) so tests inject fakes.

  Each `*Native` object calls `System.loadLibrary` in its `init`, so a JVM unit test must **always** pass an `*Api` fake explicitly and never fall through to the default argument — touching the object on a JVM without the `.so` throws `UnsatisfiedLinkError`. Referencing the request-code constants is safe: they are `const val` and the compiler inlines them, so no class is loaded.

Stream B branches from the post-F8 commit and receives this whole area in Kotlin: `audio/encoder/*.kt`, `AudioOutputSpeech.kt`, the new `audio/native/*.kt` seams and the Kotlin decoders. B1, B2, B9 and B11 are therefore written against `PreprocessingEncoder.kt` + `SpeexPreprocessApi`, not `PreprocessingEncoder.java` + `Speex.SpeexPreprocessState`; `audio/javacpp/` no longer exists.

Design decisions:
- **Five shared libraries, not one.** CELT 0.7 and 0.11 export identical symbol names (`celt_encode`, …) and speex 1.2.1 and speexdsp 1.2.1 both carry `kiss_fft.c`; linking them into one `.so` would collide. Each library keeps its own JNI file and is loaded by the Kotlin objects that need it (`System.loadLibrary` is idempotent).
- **speex is split into the two upstream release tags.** Spec F6 phrases the split as conditional ("if the old combined tree cannot build with the new NDK, otherwise keep and note"), but spec §2 states unconditionally that the third-party native sources are "git submodules of the main repo pointing at upstream release tags", and the current speex commit `a6d05eb` is an untagged 2008 snapshot of the pre-split tree. §2 therefore settles it, and the F6 condition cannot be evaluated at this point in the sequence anyway: the dev shell still ships NDK 26 here (Task 10 is what installs NDK 29), and this task deletes `src/main/jni` in step 7, so there is no ordering in which "build the old tree with NDK 29" could be a step of this plan.
- **Compile definitions are the ones ndk-build used** (`__EMX__ FIXED_POINT USE_KISS_FFT EXPORT=` for speex/speexdsp — `__EMX__` selects the plain `short/int` typedefs in `speex_types.h`/`speexdsp_types.h`, which still exist in 1.2.1, so no `configure` step is needed; `HAVE_CONFIG_H` + the checked-in `config.h` + `-fvisibility=hidden` for celt; fixed-point opus), so codec behavior is unchanged.
- **Buffers cross JNI as Java arrays.** The old `IDecoder` contract passes `ByteBuffer` slices of *heap* buffers (`PacketBuffer.bufferBlock`), which have no direct address; the Kotlin decoders copy the ≤ 1 KB frame into a `ByteArray` (`PacketBytes.copy`) and JNI pins arrays. The one safety change: `jni_speex.cpp` decodes into a buffer of the codec's real frame size and copies at most `out.size` samples (the javacpp binding let speex write 640 UWB samples into a 480-element array).
- **No host-side ctest targets.** Every JNI function is a one-line pass-through; the Kotlin logic on top is tested against fakes (this task), which is the second option the spec allows.
- **`ANDROID_STL=c++_static` is safe here.** The NDK warns against static libc++ because duplicated global state breaks when C++ objects, exceptions or `std::` containers cross a library boundary. Nothing crosses here: each of the five `.so` files is self-contained, exports only `Java_…` C functions, and the only C++ the JNI sources use is `new`/`delete[]` in `jni_speex.cpp` and `std::min`. The linker keeps just those pieces, so the size cost is negligible and no libc++ state is shared.

- [ ] **Step 1: Write the failing tests for the encoders, the decoders and the jitter buffer**

Create `libraries/humla/src/test/java/se/lublin/humla/audio/encoder/RecordingEncoder.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import se.lublin.humla.net.PacketBuffer

/** Test double for the encoder wrapped by PreprocessingEncoder / ResamplingEncoder. */
class RecordingEncoder : IEncoder {
    val received = mutableListOf<ShortArray>()
    val receivedSizes = mutableListOf<Int>()
    var destroyed = false

    override fun encode(input: ShortArray, inputSize: Int): Int {
        received += input.copyOf()
        receivedSizes += inputSize
        return inputSize
    }
    override fun getBufferedFrames(): Int = 0
    override fun isReady(): Boolean = false
    override fun getEncodedData(packetBuffer: PacketBuffer) {}
    override fun terminate() {}
    override fun destroy() { destroyed = true }
}
```

Create `OpusEncoderTest.kt` in the same directory:
```kotlin
package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusEncoderApi
import se.lublin.humla.net.PacketBuffer

class OpusEncoderTest {

    private class FakeOpus : OpusEncoderApi {
        val encodedFrameSizes = mutableListOf<Int>()
        override fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long {
            error[0] = 0
            return 42L
        }
        override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int {
            encodedFrameSizes += frameSize
            out[0] = 0x11; out[1] = 0x22; out[2] = 0x33
            return 3
        }
        override fun ctlSetInt(state: Long, request: Int, value: Int): Int = 0
        override fun ctlGetInt(state: Long, request: Int, value: IntArray): Int {
            value[0] = 40000
            return 0
        }
        override fun destroy(state: Long) {}
    }

    @Test
    fun `a full packet is written as varint length followed by the opus payload`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)

        assertThat(encoder.encode(ShortArray(480), 480)).isEqualTo(0)
        assertThat(encoder.isReady()).isFalse()
        assertThat(encoder.encode(ShortArray(480), 480)).isEqualTo(3)
        assertThat(encoder.isReady()).isTrue()
        assertThat(fake.encodedFrameSizes).containsExactly(960)

        val pb = PacketBuffer.allocate(16)
        encoder.getEncodedData(pb)
        assertThat(pb.size()).isEqualTo(4)
        pb.rewind()
        assertThat(pb.dataBlock(4)).isEqualTo(byteArrayOf(0x03, 0x11, 0x22, 0x33))
        assertThat(encoder.isReady()).isFalse()
    }

    @Test
    fun `terminate flushes a partial packet and sets the terminator bit in the header`() {
        val fake = FakeOpus()
        val encoder = OpusEncoder(48000, 1, 480, 2, 40000, 1024, fake)
        encoder.encode(ShortArray(480), 480)

        encoder.terminate()

        assertThat(fake.encodedFrameSizes).containsExactly(960) // zero-padded to a whole packet
        val pb = PacketBuffer.allocate(16)
        encoder.getEncodedData(pb)
        pb.rewind()
        assertThat(pb.readLong()).isEqualTo(3L or (1L shl 13)) // 8195: two-byte varint 0xA0 0x03
        assertThat(pb.dataBlock(3)).isEqualTo(byteArrayOf(0x11, 0x22, 0x33))
    }
}
```

Create `CELT7EncoderTest.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.Celt7Api
import se.lublin.humla.net.PacketBuffer

class CELT7EncoderTest {

    private class FakeCelt7 : Celt7Api {
        override fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long {
            error?.set(0, 0)
            return 1L
        }
        override fun modeInfo(mode: Long, request: Int, value: IntArray): Int {
            value[0] = 0
            return 0
        }
        override fun modeDestroy(mode: Long) {}
        override fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 2L
        }
        override fun encoderCtlInt(state: Long, request: Int, value: Int): Int = 0
        override fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int {
            for (i in 0 until 5) out[i] = (i + 1).toByte()
            return 5
        }
        override fun encoderDestroy(state: Long) {}
        override fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long = 3L
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int = 0
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int = 0
        override fun decoderDestroy(state: Long) {}
    }

    @Test
    fun `frames are chained with the continuation bit and their real lengths`() {
        val encoder = CELT7Encoder(48000, 480, 1, 2, 40000, 1024, FakeCelt7())
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isFalse()
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isTrue()

        val pb = PacketBuffer.allocate(32)
        encoder.getEncodedData(pb)
        pb.rewind()
        assertThat(pb.dataBlock(12)).isEqualTo(byteArrayOf(0x85.toByte(), 1, 2, 3, 4, 5, 0x05, 1, 2, 3, 4, 5))
        assertThat(encoder.isReady()).isFalse()
    }
}
```
`CELT7Encoder.getBitstreamVersion()` gets no test: it creates a mode, reads one constant out of it and destroys it again, so a test against a fake could only restate that pass-through. Its one consumer, `HumlaService.addCeltVersions`, belongs to stream A.

Create `CELT11EncoderTest.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.Celt11Api
import se.lublin.humla.net.PacketBuffer

class CELT11EncoderTest {

    private class FakeCelt11 : Celt11Api {
        override fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 7L
        }
        override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int = maxBytes
        override fun encoderDestroy(state: Long) {}
        override fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long = 8L
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int = frameSize
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int = frameSize
        override fun decoderDestroy(state: Long) {}
    }

    @Test
    fun `packet is ready after framesPerPacket frames and each frame is emitted at the fixed size`() {
        val encoder = CELT11Encoder(48000, 1, 2, FakeCelt11()) // buffer size = 48000 / 800 = 60 bytes
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isFalse()
        encoder.encode(ShortArray(480), 480)
        assertThat(encoder.isReady()).isTrue()

        val pb = PacketBuffer.allocate(128)
        encoder.getEncodedData(pb)
        assertThat(pb.size()).isEqualTo(122)
        pb.rewind()
        val bytes = pb.dataBlock(122)
        assertThat(bytes[0]).isEqualTo(0xBC.toByte())  // 60 | 0x80: more frames follow
        assertThat(bytes[61]).isEqualTo(0x3C.toByte()) // 60: last frame
    }
}
```

Create `PreprocessingEncoderTest.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexPreprocessApi

class PreprocessingEncoderTest {

    private class FakePreprocess : SpeexPreprocessApi {
        var runs = 0
        override fun init(frameSize: Int, sampleRate: Int): Long = 5L
        override fun run(state: Long, frame: ShortArray): Int {
            runs++
            for (i in frame.indices) frame[i] = (frame[i] / 2).toShort()
            return 1
        }
        override fun ctlInt(state: Long, request: Int, value: IntArray): Int = 0
        override fun destroy(state: Long) {}
    }

    @Test
    fun `frames are preprocessed in place before they reach the wrapped encoder`() {
        val inner = RecordingEncoder()
        val fake = FakePreprocess()
        val encoder = PreprocessingEncoder(inner, 480, 48000, fake)

        encoder.encode(ShortArray(480) { 1000 }, 480)

        assertThat(fake.runs).isEqualTo(1)
        assertThat(inner.received.single().toList().distinct()).containsExactly(500.toShort())
        assertThat(inner.receivedSizes).containsExactly(480)
    }
}
```
The constructor's six `ctl` calls get no test. Asserting the exact request/value sequence would only restate the constructor body, and spec B9 deliberately changes that sequence (`GET_PROB_START` → `SET_PROB_START`, AGC calls dropped), so such a test would exist only to be deleted by stream B.

Create `ResamplingEncoderTest.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexResamplerApi

class ResamplingEncoderTest {

    /** Pretends to upsample by 3: out[i] = input[i / 3]. */
    private class FakeResampler : SpeexResamplerApi {
        var initArgs: List<Int>? = null
        override fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long {
            initArgs = listOf(channels, inRate, outRate, quality)
            return 9L
        }
        override fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int {
            val n = minOf(outLen[0], inLen[0] * 3)
            for (i in 0 until n) out[i] = input[i / 3]
            inLen[0] = n / 3
            outLen[0] = n
            return 0
        }
        override fun destroy(state: Long) {}
    }

    @Test
    fun `input frames are resampled into the target frame size before encoding`() {
        val inner = RecordingEncoder()
        val fake = FakeResampler()
        val encoder = ResamplingEncoder(inner, 1, 16000, 480, 48000, fake)

        encoder.encode(ShortArray(160) { it.toShort() }, 160)

        assertThat(fake.initArgs).containsExactly(1, 16000, 48000, 3).inOrder()
        val received = inner.received.single()
        assertThat(received.size).isEqualTo(480)
        assertThat(received[0]).isEqualTo(0.toShort())
        assertThat(received[479]).isEqualTo(159.toShort())
        assertThat(inner.receivedSizes).containsExactly(480)
    }
}
```

Create `libraries/humla/src/test/java/se/lublin/humla/audio/FakeJitter.kt` (used by `SpeexJitterBufferTest` and `AudioOutputSpeechTest`; package `se.lublin.humla.audio`):
```kotlin
package se.lublin.humla.audio

import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative

/** Records what [SpeexJitterBuffer] sends to libspeexdsp and replays a scripted `get`. */
class FakeJitter : SpeexJitterApi {
    /** One entry per `put`, as `[length, timestamp, span, sequence, userData]`. */
    val puts = mutableListOf<List<Int>>()
    var lastPutData: ByteArray? = null
    /** Recorded `ctl` requests as `request to value`. */
    val ctlCalls = mutableListOf<Pair<Int, Int>>()
    /** The value the next `ctl` writes back into its in/out argument. */
    var ctlResult = 0
    var nextStatus = SpeexJitterNative.JITTER_BUFFER_MISSING
    var nextPacket = ByteArray(0)
    /** `[length, timestamp, span, sequence, userData]` the next `get` reports. */
    var nextMeta = intArrayOf(0, 0, 0, 0, 0)
    var ticks = 0
    var updateDelayCalls = 0
    var destroyed = false

    override fun init(stepSize: Int): Long = 1L
    override fun destroy(handle: Long) {
        destroyed = true
    }
    override fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int) {
        lastPutData = data.copyOf()
        puts += listOf(len, timestamp, span, sequence, userData)
    }
    override fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int {
        nextPacket.copyInto(out, 0, 0, minOf(nextPacket.size, out.size))
        nextMeta.copyInto(meta)
        return nextStatus
    }
    override fun pointerTimestamp(handle: Long): Int = 0
    override fun tick(handle: Long) {
        ticks++
    }
    override fun ctl(handle: Long, request: Int, value: IntArray): Int {
        ctlCalls += request to value[0]
        value[0] = ctlResult
        return 0
    }
    override fun updateDelay(handle: Long): Int {
        updateDelayCalls++
        return 0
    }
}
```

Create `libraries/humla/src/test/java/se/lublin/humla/audio/SpeexJitterBufferTest.kt`:
```kotlin
package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexJitterNative

class SpeexJitterBufferTest {

    @Test
    fun `get takes the packet length and user data out of the meta array`() {
        val fake = FakeJitter()
        fake.nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
        // [length, timestamp, span, sequence, userData] - length is slot 0, userData slot 4
        fake.nextMeta = intArrayOf(37, 1440, 960, 9, 1)

        val packet = SpeexJitterBuffer(480, fake).get(ByteArray(4096), 480)

        assertThat(packet.status).isEqualTo(SpeexJitterNative.JITTER_BUFFER_OK)
        assertThat(packet.length).isEqualTo(37)
        assertThat(packet.userData).isEqualTo(1)
    }

    @Test
    fun `control sends the value in and returns what the buffer wrote back`() {
        val fake = FakeJitter()
        fake.ctlResult = 3
        val buffer = SpeexJitterBuffer(480, fake)

        val available = buffer.control(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT, 0)

        assertThat(available).isEqualTo(3)
        assertThat(fake.ctlCalls).containsExactly(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT to 0)
    }
}
```

Create `libraries/humla/src/test/java/se/lublin/humla/audio/SpeexDecoderTest.kt`:
```kotlin
package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.SpeexDecoderApi

class SpeexDecoderTest {

    private class FakeSpeex(private val samples: FloatArray) : SpeexDecoderApi {
        override fun create(modeId: Int): Long = 1L
        override fun ctlInt(handle: Long, request: Int, value: Int): Int = 0
        override fun decodeFloat(handle: Long, data: ByteArray?, len: Int, out: FloatArray): Int {
            samples.copyInto(out, 0, 0, minOf(samples.size, out.size))
            return 0
        }
        override fun destroy(handle: Long) {}
    }

    @Test
    fun `decoded samples are scaled from the 16 bit range into minus one to one`() {
        val decoder = SpeexDecoder(FakeSpeex(floatArrayOf(32767f, -32767f, 0f, 3276.7f)))
        val out = FloatArray(4)

        assertThat(decoder.decodeFloat(null, 0, out, 4)).isEqualTo(4)

        assertThat(out[0]).isWithin(1e-5f).of(1f)
        assertThat(out[1]).isWithin(1e-5f).of(-1f)
        assertThat(out[2]).isEqualTo(0f)
        assertThat(out[3]).isWithin(1e-5f).of(0.1f)
    }
}
```

Create `libraries/humla/src/test/java/se/lublin/humla/audio/AudioOutputSpeechTest.kt`:
```kotlin
package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

class AudioOutputSpeechTest {

    private class FakeOpusDecoder(
        private val nbFrames: Int = 1,
        private val samplesPerFrame: Int = AudioHandler.FRAME_SIZE,
    ) : OpusDecoderApi {
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long {
            error[0] = 0
            return 1L
        }
        override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int =
            AudioHandler.FRAME_SIZE
        override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int =
            AudioHandler.FRAME_SIZE
        override fun destroy(state: Long) {}
        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = nbFrames
        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = samplesPerFrame
    }

    /** One Mumble opus payload: the 13-bit size header, then `payload`. */
    private fun opusPacket(payload: ByteArray): ByteArray {
        val pb = PacketBuffer.allocate(payload.size + 4)
        pb.writeLong(payload.size.toLong())
        pb.append(payload, payload.size)
        val length = pb.size()
        pb.rewind()
        return pb.dataBlock(length)
    }

    @Test
    fun `an opus frame reaches the jitter buffer with its sample count as span`() {
        val jitter = FakeJitter()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            HumlaUDPMessageType.UDPVoiceOpus,
            AudioHandler.FRAME_SIZE,
            { _, _ -> },
            FakeOpusDecoder(nbFrames = 2, samplesPerFrame = 480),
            jitter,
        )
        val packet = opusPacket(byteArrayOf(0x41, 0x42, 0x43))

        speech.addFrameToBuffer(PacketBuffer(packet, packet.size), 0, 7)

        // [length, timestamp, span, sequence, userData]: the whole packet, FRAME_SIZE * seq,
        // frames * samplesPerFrame, a sequence of 0 and the message flags as user data.
        assertThat(jitter.puts).containsExactly(listOf(4, AudioHandler.FRAME_SIZE * 7, 2 * 480, 0, 0))
        assertThat(jitter.lastPutData).isEqualTo(packet)
    }

    @Test
    fun `a decoded packet reports the talk state carried in its user data`() {
        val packet = opusPacket(byteArrayOf(0x41, 0x42, 0x43))
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, 1) // userData 1 = shouting
            ctlResult = 3                                    // three packets available
        }
        val states = mutableListOf<Pair<Int, TalkState>>()
        val speech = AudioOutputSpeech(
            User(42, "alice"),
            HumlaUDPMessageType.UDPVoiceOpus,
            AudioHandler.FRAME_SIZE,
            { session, state -> states += session to state },
            FakeOpusDecoder(),
            jitter,
        )

        val result = speech.call()

        assertThat(states).containsExactly(42 to TalkState.SHOUTING)
        assertThat(result.isAlive()).isTrue()
        assertThat(result.getNumSamples()).isEqualTo(AudioHandler.FRAME_SIZE)
        assertThat(jitter.ticks).isEqualTo(1)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.audio.*'`
Expected: FAIL to compile: `Unresolved reference: se.lublin.humla.audio.native` (and the extra constructor arguments, `SpeexJitterBuffer`, `SpeexDecoder` and the `AudioOutputSpeech` api parameters).

- [ ] **Step 3: Create the `native` package (interfaces + `external` objects)**

All files in `libraries/humla/src/main/java/se/lublin/humla/audio/native/`, package `se.lublin.humla.audio.native`, GPL header on each. Handles are raw C pointers as `Long`; `error`/`value` arrays are one-element in/out parameters.

`OpusEncoderNative.kt`:
```kotlin
package se.lublin.humla.audio.native

/** libopus encoder. State handles are `OpusEncoder*`. */
interface OpusEncoderApi {
    fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long
    fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    fun ctlSetInt(state: Long, request: Int, value: Int): Int
    fun ctlGetInt(state: Long, request: Int, value: IntArray): Int
    fun destroy(state: Long)
}

object OpusEncoderNative : OpusEncoderApi {
    const val OPUS_APPLICATION_VOIP = 2048
    const val OPUS_SET_BITRATE_REQUEST = 4002
    const val OPUS_GET_BITRATE_REQUEST = 4003
    const val OPUS_SET_VBR_REQUEST = 4006

    init {
        System.loadLibrary("humla_opus")
    }

    external override fun create(sampleRate: Int, channels: Int, application: Int, error: IntArray): Long
    external override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    external override fun ctlSetInt(state: Long, request: Int, value: Int): Int
    external override fun ctlGetInt(state: Long, request: Int, value: IntArray): Int
    external override fun destroy(state: Long)
}
```

`OpusDecoderNative.kt`:
```kotlin
package se.lublin.humla.audio.native

/** libopus decoder and packet inspection. State handles are `OpusDecoder*`. `data == null` requests PLC. */
interface OpusDecoderApi {
    fun create(sampleRate: Int, channels: Int, error: IntArray): Long
    fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int
    fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int
    fun destroy(state: Long)
    fun packetGetNbFrames(packet: ByteArray, len: Int): Int
    fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int
}

object OpusDecoderNative : OpusDecoderApi {
    init {
        System.loadLibrary("humla_opus")
    }

    external override fun create(sampleRate: Int, channels: Int, error: IntArray): Long
    external override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int, decodeFec: Int): Int
    external override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, decodeFec: Int): Int
    external override fun destroy(state: Long)
    external override fun packetGetNbFrames(packet: ByteArray, len: Int): Int
    external override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int
}
```

`Celt7Native.kt`:
```kotlin
package se.lublin.humla.audio.native

/** CELT 0.7.1 (Mumble "CELT alpha"). Mode handles are `CELTMode*`, state handles `CELTEncoder*` / `CELTDecoder*`. */
interface Celt7Api {
    fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long
    fun modeInfo(mode: Long, request: Int, value: IntArray): Int
    fun modeDestroy(mode: Long)
    fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long
    fun encoderCtlInt(state: Long, request: Int, value: Int): Int
    /** `celt_encode(state, pcm, NULL, out, maxBytes)` */
    fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int
    fun encoderDestroy(state: Long)
    fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long
    fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int
    fun decoderDestroy(state: Long)
}

object Celt7Native : Celt7Api {
    const val CELT_GET_BITSTREAM_VERSION = 2000
    const val CELT_SET_PREDICTION_REQUEST = 4
    const val CELT_SET_VBR_RATE_REQUEST = 6

    init {
        System.loadLibrary("humla_celt7")
    }

    external override fun modeCreate(sampleRate: Int, frameSize: Int, error: IntArray?): Long
    external override fun modeInfo(mode: Long, request: Int, value: IntArray): Int
    external override fun modeDestroy(mode: Long)
    external override fun encoderCreate(mode: Long, channels: Int, error: IntArray): Long
    external override fun encoderCtlInt(state: Long, request: Int, value: Int): Int
    external override fun encode(state: Long, pcm: ShortArray, out: ByteArray, maxBytes: Int): Int
    external override fun encoderDestroy(state: Long)
    external override fun decoderCreate(mode: Long, channels: Int, error: IntArray): Long
    external override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    external override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray): Int
    external override fun decoderDestroy(state: Long)
}
```

`Celt11Native.kt`:
```kotlin
package se.lublin.humla.audio.native

/** CELT 0.11.1 (Mumble "CELT beta"). Handles are `CELTEncoder*` / `CELTDecoder*`. */
interface Celt11Api {
    fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    fun encoderDestroy(state: Long)
    fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int
    fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int
    fun decoderDestroy(state: Long)
}

object Celt11Native : Celt11Api {
    init {
        System.loadLibrary("humla_celt11")
    }

    external override fun encoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    external override fun encode(state: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    external override fun encoderDestroy(state: Long)
    external override fun decoderCreate(sampleRate: Int, channels: Int, error: IntArray): Long
    external override fun decodeFloat(state: Long, data: ByteArray?, len: Int, out: FloatArray, frameSize: Int): Int
    external override fun decodeShort(state: Long, data: ByteArray?, len: Int, out: ShortArray, frameSize: Int): Int
    external override fun decoderDestroy(state: Long)
}
```

`SpeexDecoderNative.kt`:
```kotlin
package se.lublin.humla.audio.native

/**
 * libspeex decoder. A handle owns the decoder state, its SpeexBits and a frame buffer of the
 * mode's frame size; [decodeFloat] copies at most `out.size` samples. `data == null` feeds an
 * empty bit stream (what the old binding did for a lost frame).
 */
interface SpeexDecoderApi {
    fun create(modeId: Int): Long
    fun ctlInt(handle: Long, request: Int, value: Int): Int
    fun decodeFloat(handle: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    fun destroy(handle: Long)
}

object SpeexDecoderNative : SpeexDecoderApi {
    const val SPEEX_SET_ENH = 0
    const val SPEEX_MODEID_UWB = 2

    init {
        System.loadLibrary("humla_speex")
    }

    external override fun create(modeId: Int): Long
    external override fun ctlInt(handle: Long, request: Int, value: Int): Int
    external override fun decodeFloat(handle: Long, data: ByteArray?, len: Int, out: FloatArray): Int
    external override fun destroy(handle: Long)
}
```

`SpeexPreprocessNative.kt`:
```kotlin
package se.lublin.humla.audio.native

/** libspeexdsp preprocessor. State handles are `SpeexPreprocessState*`; `value[0]` is the in/out int argument of `speex_preprocess_ctl`. */
interface SpeexPreprocessApi {
    fun init(frameSize: Int, sampleRate: Int): Long
    /** Runs the preprocessor in place; returns the speex VAD decision (1 = speech). */
    fun run(state: Long, frame: ShortArray): Int
    fun ctlInt(state: Long, request: Int, value: IntArray): Int
    fun destroy(state: Long)
}

object SpeexPreprocessNative : SpeexPreprocessApi {
    const val SPEEX_PREPROCESS_SET_DENOISE = 0
    const val SPEEX_PREPROCESS_SET_AGC = 2
    const val SPEEX_PREPROCESS_SET_VAD = 4
    const val SPEEX_PREPROCESS_SET_DEREVERB = 8
    const val SPEEX_PREPROCESS_SET_PROB_START = 14
    const val SPEEX_PREPROCESS_GET_PROB_START = 15
    const val SPEEX_PREPROCESS_SET_NOISE_SUPPRESS = 18
    const val SPEEX_PREPROCESS_GET_PROB = 45
    const val SPEEX_PREPROCESS_SET_AGC_TARGET = 46

    init {
        System.loadLibrary("humla_speexdsp")
    }

    external override fun init(frameSize: Int, sampleRate: Int): Long
    external override fun run(state: Long, frame: ShortArray): Int
    external override fun ctlInt(state: Long, request: Int, value: IntArray): Int
    external override fun destroy(state: Long)
}
```

`SpeexResamplerNative.kt`:
```kotlin
package se.lublin.humla.audio.native

/** libspeexdsp resampler. `inLen[0]`/`outLen[0]` are in/out sample counts as in `speex_resampler_process_int`. */
interface SpeexResamplerApi {
    fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long
    fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int
    fun destroy(state: Long)
}

object SpeexResamplerNative : SpeexResamplerApi {
    init {
        System.loadLibrary("humla_speexdsp")
    }

    external override fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long
    external override fun processInt(state: Long, channelIndex: Int, input: ShortArray, inLen: IntArray, out: ShortArray, outLen: IntArray): Int
    external override fun destroy(state: Long)
}
```

`SpeexJitterNative.kt`:
```kotlin
package se.lublin.humla.audio.native

/**
 * libspeexdsp adaptive jitter buffer. [put] copies `len` bytes of `data` (the buffer keeps its own
 * copy). [get] writes the packet into `out` and fills `meta` with
 * `[len, timestamp, span, sequence, userData]`; it returns a `JITTER_BUFFER_*` status.
 */
interface SpeexJitterApi {
    fun init(stepSize: Int): Long
    fun destroy(handle: Long)
    fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int)
    fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int
    fun pointerTimestamp(handle: Long): Int
    fun tick(handle: Long)
    fun ctl(handle: Long, request: Int, value: IntArray): Int
    fun updateDelay(handle: Long): Int
}

object SpeexJitterNative : SpeexJitterApi {
    const val JITTER_BUFFER_OK = 0
    const val JITTER_BUFFER_MISSING = 1
    const val JITTER_BUFFER_INCOMPLETE = 2
    const val JITTER_BUFFER_INTERNAL_ERROR = -1
    const val JITTER_BUFFER_BAD_ARGUMENT = -2
    const val JITTER_BUFFER_SET_MARGIN = 0
    const val JITTER_BUFFER_GET_MARGIN = 1
    const val JITTER_BUFFER_GET_AVAILABLE_COUNT = 3

    init {
        System.loadLibrary("humla_speexdsp")
    }

    external override fun init(stepSize: Int): Long
    external override fun destroy(handle: Long)
    external override fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int)
    external override fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int
    external override fun pointerTimestamp(handle: Long): Int
    external override fun tick(handle: Long)
    external override fun ctl(handle: Long, request: Int, value: IntArray): Int
    external override fun updateDelay(handle: Long): Int
}
```

- [ ] **Step 4: Create the Kotlin decoders, packet helper and jitter wrapper**

All in `libraries/humla/src/main/java/se/lublin/humla/audio/`, package `se.lublin.humla.audio`.

`PacketBytes.kt`:
```kotlin
package se.lublin.humla.audio

import java.nio.ByteBuffer

internal object PacketBytes {
    /** Copies the first [length] bytes of [input] (from its position) into a fresh array; null stays null. */
    fun copy(input: ByteBuffer?, length: Int): ByteArray? {
        if (input == null) return null
        val bytes = ByteArray(length)
        input.duplicate().get(bytes, 0, length)
        return bytes
    }
}
```

`OpusDecoder.kt`:
```kotlin
package se.lublin.humla.audio

import java.nio.ByteBuffer
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.OpusDecoderNative
import se.lublin.humla.exception.NativeAudioException

class OpusDecoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val api: OpusDecoderApi = OpusDecoderNative,
) : IDecoder {
    private val state: Long

    init {
        val error = intArrayOf(0)
        state = api.create(sampleRate, channels, error)
        if (error[0] < 0) throw NativeAudioException("Opus decoder initialization failed with error: ${error[0]}")
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize, 0)
        if (result < 0) throw NativeAudioException("Opus decoding failed with error: $result")
        return result
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val result = api.decodeShort(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize, 0)
        if (result < 0) throw NativeAudioException("Opus decoding failed with error: $result")
        return result
    }

    override fun destroy() = api.destroy(state)
}
```

`CELT7Decoder.kt`:
```kotlin
package se.lublin.humla.audio

import java.nio.ByteBuffer
import se.lublin.humla.audio.native.Celt7Api
import se.lublin.humla.audio.native.Celt7Native
import se.lublin.humla.exception.NativeAudioException

class CELT7Decoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    frameSize: Int,
    channels: Int,
    private val api: Celt7Api = Celt7Native,
) : IDecoder {
    private val mode: Long
    private val state: Long

    init {
        val error = intArrayOf(0)
        mode = api.modeCreate(sampleRate, frameSize, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.7.0 decoder initialization failed with error: ${error[0]}")
        state = api.decoderCreate(mode, channels, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.7.0 decoder initialization failed with error: ${error[0]}")
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(state, PacketBytes.copy(input, inputSize), inputSize, output)
        if (result < 0) throw NativeAudioException("CELT 0.7.0 decoding failed with error: $result")
        return frameSize
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val result = api.decodeShort(state, PacketBytes.copy(input, inputSize), inputSize, output)
        if (result < 0) throw NativeAudioException("CELT 0.7.0 decoding failed with error: $result")
        return frameSize
    }

    override fun destroy() {
        api.decoderDestroy(state)
        api.modeDestroy(mode)
    }
}
```

`CELT11Decoder.kt`:
```kotlin
package se.lublin.humla.audio

import java.nio.ByteBuffer
import se.lublin.humla.audio.native.Celt11Api
import se.lublin.humla.audio.native.Celt11Native
import se.lublin.humla.exception.NativeAudioException

class CELT11Decoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val api: Celt11Api = Celt11Native,
) : IDecoder {
    private val state: Long

    init {
        val error = intArrayOf(0)
        state = api.decoderCreate(sampleRate, channels, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.11.0 decoder initialization failed with error: ${error[0]}")
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 decoding failed with error: $result")
        return frameSize
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val result = api.decodeShort(state, PacketBytes.copy(input, inputSize), inputSize, output, frameSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 decoding failed with error: $result")
        return frameSize
    }

    override fun destroy() = api.decoderDestroy(state)
}
```

`SpeexDecoder.kt`:
```kotlin
package se.lublin.humla.audio

import java.nio.ByteBuffer
import se.lublin.humla.audio.native.SpeexDecoderApi
import se.lublin.humla.audio.native.SpeexDecoderNative
import se.lublin.humla.exception.NativeAudioException

class SpeexDecoder @JvmOverloads constructor(
    private val api: SpeexDecoderApi = SpeexDecoderNative,
) : IDecoder {
    private val handle: Long = api.create(SpeexDecoderNative.SPEEX_MODEID_UWB)

    init {
        api.ctlInt(handle, SpeexDecoderNative.SPEEX_SET_ENH, 1)
    }

    @Throws(NativeAudioException::class)
    override fun decodeFloat(input: ByteBuffer?, inputSize: Int, output: FloatArray, frameSize: Int): Int {
        val result = api.decodeFloat(handle, PacketBytes.copy(input, inputSize), inputSize, output)
        if (result < 0) throw NativeAudioException("Speex decoding failed with error: $result")
        for (i in 0 until frameSize) output[i] *= (1.0f / Short.MAX_VALUE)
        return frameSize
    }

    @Throws(NativeAudioException::class)
    override fun decodeShort(input: ByteBuffer?, inputSize: Int, output: ShortArray, frameSize: Int): Int {
        val floats = FloatArray(frameSize)
        val result = api.decodeFloat(handle, PacketBytes.copy(input, inputSize), inputSize, floats)
        if (result < 0) throw NativeAudioException("Speex decoding failed with error: $result")
        for (i in 0 until frameSize) output[i] = floats[i].toInt().toShort()
        return frameSize
    }

    override fun destroy() = api.destroy(handle)
}
```

`SpeexJitterBuffer.kt`:
```kotlin
package se.lublin.humla.audio

import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative

/** Object wrapper around the speexdsp jitter buffer (replaces the old javacpp `Speex.JitterBuffer`). */
class SpeexJitterBuffer @JvmOverloads constructor(
    stepSize: Int,
    private val api: SpeexJitterApi = SpeexJitterNative,
) {
    /** Result of [get]: [status] is a `JITTER_BUFFER_*` code; [length] and [userData] come from the packet. */
    class Packet(val status: Int, val length: Int, val userData: Int)

    private val handle: Long = api.init(stepSize)
    private val meta = IntArray(5)
    private val scratch = IntArray(1)

    fun put(data: ByteArray, length: Int, timestamp: Int, span: Int, sequence: Int, userData: Int) =
        api.put(handle, data, length, timestamp, span, sequence, userData)

    fun get(out: ByteArray, desiredSpan: Int): Packet {
        val status = api.get(handle, out, desiredSpan, meta)
        return Packet(status, meta[0], meta[4])
    }

    val pointerTimestamp: Int
        get() = api.pointerTimestamp(handle)

    /** Runs `jitter_buffer_ctl` with an int argument and returns the (possibly updated) argument. */
    fun control(request: Int, value: Int): Int {
        scratch[0] = value
        api.ctl(handle, request, scratch)
        return scratch[0]
    }

    fun updateDelay(): Int = api.updateDelay(handle)

    fun tick() = api.tick(handle)

    fun destroy() = api.destroy(handle)
}
```

- [ ] **Step 5: Rewire the encoders and `AudioOutputSpeech` to the wrappers**

Replace the six files from Task 8 with the versions below. They are given in full: only the native access and the new `api` parameters change, but an executor must not have to diff them against Task 8 to reconstruct them.

`libraries/humla/src/main/java/se/lublin/humla/audio/encoder/OpusEncoder.kt` (every file of this step carries the GPL header block written out in full in Task 3 Step 3):
```kotlin
package se.lublin.humla.audio.encoder

import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.util.Arrays
import se.lublin.humla.audio.native.OpusEncoderApi
import se.lublin.humla.audio.native.OpusEncoderNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class OpusEncoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val frameSize: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
    private val api: OpusEncoderApi = OpusEncoderNative,
) : IEncoder {
    private val buffer = ByteArray(maxBufferSize)
    private val audioBuffer = ShortArray(framesPerPacket * frameSize)

    // Stateful
    private var bufferedFrames = 0
    private var encodedLength = 0
    private var terminated = false

    private val state: Long

    init {
        val error = intArrayOf(0)
        state = api.create(sampleRate, channels, OpusEncoderNative.OPUS_APPLICATION_VOIP, error)
        if (error[0] < 0) throw NativeAudioException("Opus encoder initialization failed with error: ${error[0]}")
        api.ctlSetInt(state, OpusEncoderNative.OPUS_SET_VBR_REQUEST, 0)
        api.ctlSetInt(state, OpusEncoderNative.OPUS_SET_BITRATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        if (inputSize != frameSize) {
            throw IllegalArgumentException("This Opus encoder implementation requires a constant frame size.")
        }
        terminated = false
        System.arraycopy(input, 0, audioBuffer, frameSize * bufferedFrames, frameSize)
        bufferedFrames++
        return if (bufferedFrames == framesPerPacket) encodePacket() else 0
    }

    @Throws(NativeAudioException::class)
    private fun encodePacket(): Int {
        if (bufferedFrames < framesPerPacket) {
            // If encoding is done before enough frames are buffered, fill rest of packet.
            Arrays.fill(audioBuffer, frameSize * bufferedFrames, audioBuffer.size, 0.toShort())
            bufferedFrames = framesPerPacket
        }
        val result = api.encode(state, audioBuffer, frameSize * bufferedFrames, buffer, buffer.size)
        if (result < 0) throw NativeAudioException("Opus encoding failed with error: $result")
        encodedLength = result
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = encodedLength > 0

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!isReady()) throw BufferUnderflowException()
        var size = encodedLength
        if (terminated) size = size or (1 shl 13)
        packetBuffer.writeLong(size.toLong())
        packetBuffer.append(buffer, encodedLength)
        bufferedFrames = 0
        encodedLength = 0
        terminated = false
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        terminated = true
        if (bufferedFrames > 0 && !isReady()) {
            // Perform encode operation on remaining audio if available.
            encodePacket()
        }
    }

    fun getBitrate(): Int {
        val value = intArrayOf(0)
        api.ctlGetInt(state, OpusEncoderNative.OPUS_GET_BITRATE_REQUEST, value)
        return value[0]
    }

    override fun destroy() = api.destroy(state)
}
```

`libraries/humla/src/main/java/se/lublin/humla/audio/encoder/CELT7Encoder.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import kotlin.math.min
import se.lublin.humla.audio.native.Celt7Api
import se.lublin.humla.audio.native.Celt7Native
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

class CELT7Encoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    frameSize: Int,
    channels: Int,
    private val framesPerPacket: Int,
    bitrate: Int,
    maxBufferSize: Int,
    private val api: Celt7Api = Celt7Native,
) : IEncoder {
    private val bufferSize = min(maxBufferSize, bitrate / 800)
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private val packetLengths = IntArray(framesPerPacket)
    private var bufferedFrames = 0
    private var ready = false

    private val mode: Long
    private val state: Long

    init {
        val error = intArrayOf(0)
        mode = api.modeCreate(sampleRate, frameSize, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: ${error[0]}")
        state = api.encoderCreate(mode, channels, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.7.0 encoder initialization failed with error: ${error[0]}")
        api.encoderCtlInt(state, Celt7Native.CELT_SET_PREDICTION_REQUEST, 0)
        api.encoderCtlInt(state, Celt7Native.CELT_SET_VBR_RATE_REQUEST, bitrate)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = api.encode(state, input, buffer[bufferedFrames], bufferSize)
        if (result < 0) throw NativeAudioException("CELT 0.7.0 encoding failed with error: $result")
        packetLengths[bufferedFrames] = result
        bufferedFrames++
        if (bufferedFrames >= framesPerPacket) ready = true
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = ready && bufferedFrames > 0

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (!ready) throw BufferUnderflowException()
        for (x in 0 until bufferedFrames) {
            val frame = buffer[x]
            val length = packetLengths[x]
            var head = length
            if (x < bufferedFrames - 1) head = head or 0x80
            packetBuffer.append(head.toLong())
            packetBuffer.append(frame, length)
        }
        bufferedFrames = 0
        ready = false
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        ready = true
    }

    override fun destroy() {
        api.encoderDestroy(state)
        api.modeDestroy(mode)
    }

    companion object {
        /** The CELT 0.7 bitstream version Mumla announces in `Authenticate.celt_versions`. */
        @JvmStatic
        fun getBitstreamVersion(): Int {
            val mode = Celt7Native.modeCreate(AudioHandler.SAMPLE_RATE, AudioHandler.FRAME_SIZE, null)
            val version = intArrayOf(0)
            Celt7Native.modeInfo(mode, Celt7Native.CELT_GET_BITSTREAM_VERSION, version)
            Celt7Native.modeDestroy(mode)
            return version[0]
        }
    }
}
```

`libraries/humla/src/main/java/se/lublin/humla/audio/encoder/CELT11Encoder.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import se.lublin.humla.audio.native.Celt11Api
import se.lublin.humla.audio.native.Celt11Native
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class CELT11Encoder @JvmOverloads @Throws(NativeAudioException::class) constructor(
    sampleRate: Int,
    channels: Int,
    private val framesPerPacket: Int,
    private val api: Celt11Api = Celt11Native,
) : IEncoder {
    private val bufferSize = sampleRate / 800
    private val buffer = Array(framesPerPacket) { ByteArray(bufferSize) }
    private var bufferedFrames = 0

    private val state: Long

    init {
        val error = intArrayOf(0)
        state = api.encoderCreate(sampleRate, channels, error)
        if (error[0] < 0) throw NativeAudioException("CELT 0.11.0 encoder initialization failed with error: ${error[0]}")
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        if (bufferedFrames >= framesPerPacket) throw BufferOverflowException()
        val result = api.encode(state, input, inputSize, buffer[bufferedFrames], bufferSize)
        if (result < 0) throw NativeAudioException("CELT 0.11.0 encoding failed with error: $result")
        bufferedFrames++
        return result
    }

    override fun getBufferedFrames(): Int = bufferedFrames

    override fun isReady(): Boolean = bufferedFrames == framesPerPacket

    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) {
        if (bufferedFrames < framesPerPacket) throw BufferUnderflowException()
        for (x in 0 until bufferedFrames) {
            val frame = buffer[x]
            var head = frame.size
            if (x < bufferedFrames - 1) head = head or 0x80
            packetBuffer.append(head.toLong())
            packetBuffer.append(frame, frame.size)
        }
        bufferedFrames = 0
    }

    @Throws(NativeAudioException::class)
    override fun terminate() {
        // The CELT 0.11 encoder has no partial-packet flush; kept as before.
    }

    override fun destroy() = api.encoderDestroy(state)
}
```

`libraries/humla/src/main/java/se/lublin/humla/audio/encoder/PreprocessingEncoder.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import java.nio.BufferUnderflowException
import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.SpeexPreprocessNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class PreprocessingEncoder @JvmOverloads constructor(
    private var encoder: IEncoder,
    frameSize: Int,
    sampleRate: Int,
    private val api: SpeexPreprocessApi = SpeexPreprocessNative,
) : IEncoder {
    private val state: Long = api.init(frameSize, sampleRate)

    init {
        val arg = intArrayOf(0)
        api.ctlInt(state, SpeexPreprocessNative.SPEEX_PREPROCESS_SET_VAD, arg)
        arg[0] = 1
        api.ctlInt(state, SpeexPreprocessNative.SPEEX_PREPROCESS_SET_AGC, arg)
        api.ctlInt(state, SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DENOISE, arg)
        api.ctlInt(state, SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DEREVERB, arg)
        arg[0] = 30000
        api.ctlInt(state, SpeexPreprocessNative.SPEEX_PREPROCESS_SET_AGC_TARGET, arg)
        // Increase VAD difficulty. NOTE: request id is GET_PROB_START as in the original;
        // stream B (spec B9) corrects this to SET_PROB_START.
        arg[0] = 99
        api.ctlInt(state, SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB_START, arg)
    }

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        api.run(state, input)
        return encoder.encode(input, inputSize)
    }

    override fun getBufferedFrames(): Int = encoder.getBufferedFrames()
    override fun isReady(): Boolean = encoder.isReady()
    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) = encoder.getEncodedData(packetBuffer)
    @Throws(NativeAudioException::class)
    override fun terminate() = encoder.terminate()

    fun setEncoder(encoder: IEncoder) {
        this.encoder.destroy()
        this.encoder = encoder
    }

    override fun destroy() {
        api.destroy(state)
        encoder.destroy()
    }
}
```

`libraries/humla/src/main/java/se/lublin/humla/audio/encoder/ResamplingEncoder.kt`:
```kotlin
package se.lublin.humla.audio.encoder

import java.nio.BufferUnderflowException
import se.lublin.humla.audio.native.SpeexResamplerApi
import se.lublin.humla.audio.native.SpeexResamplerNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.PacketBuffer

class ResamplingEncoder @JvmOverloads constructor(
    private var encoder: IEncoder,
    channels: Int,
    inputSampleRate: Int,
    private val targetFrameSize: Int,
    targetSampleRate: Int,
    private val api: SpeexResamplerApi = SpeexResamplerNative,
) : IEncoder {
    private val resampleBuffer = ShortArray(targetFrameSize)
    private val state: Long = api.init(channels, inputSampleRate, targetSampleRate, SPEEX_RESAMPLE_QUALITY, null)

    @Throws(NativeAudioException::class)
    override fun encode(input: ShortArray, inputSize: Int): Int {
        api.processInt(state, 0, input, intArrayOf(input.size), resampleBuffer, intArrayOf(resampleBuffer.size))
        return encoder.encode(resampleBuffer, targetFrameSize)
    }

    override fun getBufferedFrames(): Int = encoder.getBufferedFrames()
    override fun isReady(): Boolean = encoder.isReady()
    @Throws(BufferUnderflowException::class)
    override fun getEncodedData(packetBuffer: PacketBuffer) = encoder.getEncodedData(packetBuffer)
    @Throws(NativeAudioException::class)
    override fun terminate() = encoder.terminate()

    fun setEncoder(encoder: IEncoder) {
        this.encoder.destroy()
        this.encoder = encoder
    }

    override fun destroy() {
        api.destroy(state)
        encoder.destroy()
    }

    private companion object {
        const val SPEEX_RESAMPLE_QUALITY = 3
    }
}
```

`libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.kt`:
```kotlin
package se.lublin.humla.audio

import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Queue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.sin
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.OpusDecoderNative
import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

/**
 * Decodes one user's incoming voice stream through a jitter buffer into float PCM.
 *
 * [opusApi] and [jitterApi] are the seams JVM tests use to drive the Opus path without native
 * libraries; they default to the `*Native` objects, which load their `.so` on first touch. The
 * CELT and Speex decoders keep their own defaults, so only the Opus codec is testable this way.
 */
class AudioOutputSpeech @JvmOverloads @Throws(NativeAudioException::class) constructor(
    private val user: User,
    private val codec: HumlaUDPMessageType,
    private var requestedSamples: Int,
    private val talkStateListener: TalkStateListener,
    private val opusApi: OpusDecoderApi = OpusDecoderNative,
    jitterApi: SpeexJitterApi = SpeexJitterNative,
) : Callable<AudioOutputSpeech.Result> {

    fun interface TalkStateListener {
        fun onTalkStateUpdated(session: Int, state: TalkState)
    }

    private val decoder: IDecoder
    private val jitterBuffer: SpeexJitterBuffer
    private val jitterLock = Any()
    private var audioBufferSize = AudioHandler.FRAME_SIZE

    // State-specific
    private var buffer: FloatArray
    private val out: FloatArray
    private val fadeOut = FloatArray(AudioHandler.FRAME_SIZE)
    private val fadeIn = FloatArray(AudioHandler.FRAME_SIZE)
    private val frames: Queue<ByteBuffer> = ConcurrentLinkedQueue()
    private var missCount = 0
    private var hasTerminator = false
    private var lastAlive = true
    private var bufferFilled = 0
    private var lastConsume = 0
    private var ucFlags = 0

    init {
        decoder = when (codec) {
            HumlaUDPMessageType.UDPVoiceOpus -> {
                audioBufferSize *= 12
                OpusDecoder(AudioHandler.SAMPLE_RATE, 1, opusApi)
            }
            HumlaUDPMessageType.UDPVoiceCELTBeta -> CELT11Decoder(AudioHandler.SAMPLE_RATE, 1)
            HumlaUDPMessageType.UDPVoiceCELTAlpha -> CELT7Decoder(AudioHandler.SAMPLE_RATE, AudioHandler.FRAME_SIZE, 1)
            HumlaUDPMessageType.UDPVoiceSpeex -> SpeexDecoder()
            else -> throw NativeAudioException("No decoder for codec $codec")
        }

        // Larger initial buffer so we can save performance by not resizing at runtime.
        buffer = FloatArray(audioBufferSize * 2)
        out = FloatArray(audioBufferSize)

        // Sine function to represent fade in/out. Period is FRAME_SIZE.
        val mul = (Math.PI / (2.0 * AudioHandler.FRAME_SIZE)).toFloat()
        for (i in 0 until AudioHandler.FRAME_SIZE) {
            val v = sin((i * mul).toDouble()).toFloat()
            fadeIn[i] = v
            fadeOut[AudioHandler.FRAME_SIZE - i - 1] = v
        }

        jitterBuffer = SpeexJitterBuffer(AudioHandler.FRAME_SIZE, jitterApi)
        jitterBuffer.control(SpeexJitterNative.JITTER_BUFFER_SET_MARGIN, 10 * AudioHandler.FRAME_SIZE)
    }

    fun addFrameToBuffer(pb: PacketBuffer, flags: Byte, seq: Int) {
        if (pb.capacity() < 2) return

        synchronized(jitterLock) {
            try {
                var samples = 0
                if (codec == HumlaUDPMessageType.UDPVoiceOpus) {
                    val header = pb.readLong()
                    val size = (header and ((1L shl 13) - 1)).toInt()
                    if (size > 0) {
                        val data = pb.dataBlock(size)
                        if (data.size != size) return
                        val frameCount = opusApi.packetGetNbFrames(data, size)
                        samples = frameCount * opusApi.packetGetSamplesPerFrame(data, AudioHandler.SAMPLE_RATE)
                    } else {
                        return
                    }
                } else {
                    try {
                        var header: Int
                        do {
                            header = pb.next()
                            samples += AudioHandler.FRAME_SIZE
                            pb.skip(header and 0x7f)
                        } while ((header and 0x80) > 0)
                    } catch (e: BufferUnderflowException) {
                        // reached end of buffer
                    }
                }
                pb.rewind()

                val size = pb.left()
                val data = pb.dataBlock(size)
                jitterBuffer.put(data, size, AudioHandler.FRAME_SIZE * seq, samples, 0, flags.toInt())
            } catch (e: BufferOverflowException) {
                e.printStackTrace()
            }
        }
    }

    @Throws(Exception::class)
    override fun call(): Result {
        if (bufferFilled - lastConsume > 0) {
            // Shift over the remaining unconsumed data in the buffer.
            System.arraycopy(buffer, lastConsume, buffer, 0, bufferFilled - lastConsume)
        }
        bufferFilled -= lastConsume
        lastConsume = requestedSamples

        if (bufferFilled >= requestedSamples) return Result(this, lastAlive, buffer, bufferFilled)

        var nextAlive = lastAlive

        while (bufferFilled < requestedSamples) {
            var decodedSamples = AudioHandler.FRAME_SIZE
            resizeBuffer(bufferFilled + audioBufferSize)

            if (!lastAlive) {
                Arrays.fill(out, 0f)
            } else {
                val (ts, availPackets) = synchronized(jitterLock) {
                    jitterBuffer.pointerTimestamp to
                        jitterBuffer.control(SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT, 0).toFloat()
                }

                // Make sure that we have enough packets in the jitter buffer before we even begin
                // decoding, based on the average # of packets available. Prevents a metallic
                // 'twang' when the user starts talking, caused by buffer underrun. The official
                // Mumble project uses the same technique.
                if (ts == 0) {
                    val want = ceil(user.averageAvailable.toDouble()).toInt()
                    if (availPackets < want) {
                        missCount++
                        if (missCount < 20) {
                            Arrays.fill(out, 0f)
                            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
                            bufferFilled += decodedSamples
                            continue
                        }
                    }
                }

                if (frames.isEmpty()) {
                    val packetBytes = ByteArray(4096)
                    val jbp = synchronized(jitterLock) { jitterBuffer.get(packetBytes, AudioHandler.FRAME_SIZE) }

                    if (jbp.status == SpeexJitterNative.JITTER_BUFFER_OK) {
                        val pb = PacketBuffer(packetBytes, jbp.length)

                        missCount = 0
                        ucFlags = jbp.userData
                        hasTerminator = false
                        try {
                            if (codec == HumlaUDPMessageType.UDPVoiceOpus) {
                                val header = pb.readLong()
                                val size = (header and ((1L shl 13) - 1)).toInt()
                                hasTerminator = (header and (1L shl 13)) > 0
                                frames.add(pb.bufferBlock(size))
                            } else {
                                var header: Int
                                do {
                                    header = pb.next()
                                    val size = header and 0x7f
                                    if (header > 0) {
                                        frames.add(pb.bufferBlock(size))
                                    } else {
                                        hasTerminator = true
                                    }
                                } while ((header and 0x80) > 0)
                            }
                        } catch (e: BufferOverflowException) {
                            e.printStackTrace()
                        } catch (e: BufferUnderflowException) {
                            e.printStackTrace()
                        }

                        if (availPackets >= user.averageAvailable) {
                            user.averageAvailable = availPackets
                        } else {
                            user.averageAvailable = user.averageAvailable * 0.99f
                        }
                    } else {
                        synchronized(jitterLock) { jitterBuffer.updateDelay() }
                        missCount++
                        if (missCount > 10) nextAlive = false
                    }
                }

                try {
                    if (!frames.isEmpty()) {
                        val data = frames.poll()
                        decodedSamples = decoder.decodeFloat(data, data.limit(), out, audioBufferSize)
                        if (frames.isEmpty()) {
                            synchronized(jitterLock) { jitterBuffer.updateDelay() }
                        }
                        if (frames.isEmpty() && hasTerminator) nextAlive = false
                    } else {
                        decodedSamples = decoder.decodeFloat(null, 0, out, AudioHandler.FRAME_SIZE)
                    }
                } catch (e: NativeAudioException) {
                    e.printStackTrace()
                    decodedSamples = AudioHandler.FRAME_SIZE
                }

                if (!nextAlive) {
                    for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeOut[i]
                } else if (ts == 0) {
                    for (i in 0 until AudioHandler.FRAME_SIZE) out[i] *= fadeIn[i]
                }

                synchronized(jitterLock) {
                    repeat(decodedSamples / AudioHandler.FRAME_SIZE) { jitterBuffer.tick() }
                }
            }

            System.arraycopy(out, 0, buffer, bufferFilled, decodedSamples)
            bufferFilled += decodedSamples
        }

        if (!nextAlive) ucFlags = 0xFF

        val talkState = when (ucFlags) {
            0 -> TalkState.TALKING
            1 -> TalkState.SHOUTING
            0xFF -> TalkState.PASSIVE
            else -> TalkState.WHISPERING
        }
        talkStateListener.onTalkStateUpdated(user.session, talkState)

        val tmp = lastAlive
        lastAlive = nextAlive
        return Result(this, tmp, buffer, requestedSamples)
    }

    private fun resizeBuffer(newSize: Int) {
        if (newSize > buffer.size) buffer = Arrays.copyOf(buffer, newSize)
    }

    /** Sets the preferred number of samples to return when the callable is executed. */
    fun setRequestedSamples(samples: Int) {
        requestedSamples = samples
    }

    fun getCodec(): HumlaUDPMessageType = codec

    fun getUser(): User = user

    fun getSession(): Int = user.session

    /** Cleans up all native resources linked to this instance. MUST be called eventually. */
    fun destroy() {
        decoder.destroy()
        jitterBuffer.destroy()
    }

    /** The outcome of a decoding pass. */
    class Result internal constructor(
        private val speechOutput: AudioOutputSpeech,
        private val alive: Boolean,
        private val samples: FloatArray,
        private val numSamples: Int,
    ) : IAudioMixerSource<FloatArray> {
        fun getSpeechOutput(): AudioOutputSpeech = speechOutput
        fun isAlive(): Boolean = alive
        override fun getSamples(): FloatArray = samples
        override fun getNumSamples(): Int = numSamples
    }
}
```

- [ ] **Step 6: Write the JNI layer**

All under `libraries/humla/src/main/cpp/`.

`jni_handle.h`:
```cpp
// Shared helpers for the hand-written JNI bindings. Handles are raw pointers carried as jlong.
#pragma once
#include <jni.h>
#include <cstdint>

template <typename T>
static inline T* fromHandle(jlong handle) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

static inline jlong toHandle(const void* pointer) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

/** Writes value into slot 0 of an optional one-element int array (error / out parameters). */
static inline void writeInt(JNIEnv* env, jintArray target, jint value) {
    if (target != nullptr) env->SetIntArrayRegion(target, 0, 1, &value);
}
```

`jni_opus.cpp`:
```cpp
#include <jni.h>
#include <opus.h>
#include "jni_handle.h"

#define ENC(name) Java_se_lublin_humla_audio_native_OpusEncoderNative_##name
#define DEC(name) Java_se_lublin_humla_audio_native_OpusDecoderNative_##name

extern "C" {

JNIEXPORT jlong JNICALL ENC(create)(JNIEnv* env, jobject, jint sampleRate, jint channels, jint application, jintArray error) {
    int err = 0;
    OpusEncoder* st = opus_encoder_create(sampleRate, channels, application, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL ENC(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    int result = opus_encode(fromHandle<OpusEncoder>(state), pcmPtr, frameSize,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL ENC(ctlSetInt)(JNIEnv*, jobject, jlong state, jint request, jint value) {
    return opus_encoder_ctl(fromHandle<OpusEncoder>(state), request, static_cast<opus_int32>(value));
}

JNIEXPORT jint JNICALL ENC(ctlGetInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    opus_int32 v = 0;
    int result = opus_encoder_ctl(fromHandle<OpusEncoder>(state), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL ENC(destroy)(JNIEnv*, jobject, jlong state) {
    opus_encoder_destroy(fromHandle<OpusEncoder>(state));
}

JNIEXPORT jlong JNICALL DEC(create)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    OpusDecoder* st = opus_decoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL DEC(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out, jint frameSize, jint decodeFec) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    int result = opus_decode_float(fromHandle<OpusDecoder>(state),
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr, frameSize, decodeFec);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL DEC(decodeShort)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jshortArray out, jint frameSize, jint decodeFec) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = opus_decode(fromHandle<OpusDecoder>(state),
                             dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                             dataPtr != nullptr ? len : 0, outPtr, frameSize, decodeFec);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL DEC(destroy)(JNIEnv*, jobject, jlong state) {
    opus_decoder_destroy(fromHandle<OpusDecoder>(state));
}

JNIEXPORT jint JNICALL DEC(packetGetNbFrames)(JNIEnv* env, jobject, jbyteArray packet, jint len) {
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    int result = opus_packet_get_nb_frames(reinterpret_cast<const unsigned char*>(ptr), len);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL DEC(packetGetSamplesPerFrame)(JNIEnv* env, jobject, jbyteArray packet, jint sampleRate) {
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    int result = opus_packet_get_samples_per_frame(reinterpret_cast<const unsigned char*>(ptr), sampleRate);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

} // extern "C"
```

`jni_celt7.cpp`:
```cpp
#include <jni.h>
extern "C" {
#include <celt.h>
#include <celt_types.h>
}
#include "jni_handle.h"

#define C7(name) Java_se_lublin_humla_audio_native_Celt7Native_##name

extern "C" {

JNIEXPORT jlong JNICALL C7(modeCreate)(JNIEnv* env, jobject, jint sampleRate, jint frameSize, jintArray error) {
    int err = 0;
    CELTMode* mode = celt_mode_create(sampleRate, frameSize, &err);
    writeInt(env, error, err);
    return toHandle(mode);
}

JNIEXPORT jint JNICALL C7(modeInfo)(JNIEnv* env, jobject, jlong mode, jint request, jintArray value) {
    celt_int32 v = 0;
    int result = celt_mode_info(fromHandle<const CELTMode>(mode), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL C7(modeDestroy)(JNIEnv*, jobject, jlong mode) {
    celt_mode_destroy(fromHandle<CELTMode>(mode));
}

JNIEXPORT jlong JNICALL C7(encoderCreate)(JNIEnv* env, jobject, jlong mode, jint channels, jintArray error) {
    int err = 0;
    CELTEncoder* st = celt_encoder_create(fromHandle<const CELTMode>(mode), channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C7(encoderCtlInt)(JNIEnv*, jobject, jlong state, jint request, jint value) {
    return celt_encoder_ctl(fromHandle<CELTEncoder>(state), request, static_cast<int>(value));
}

JNIEXPORT jint JNICALL C7(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jbyteArray out, jint maxBytes) {
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    int result = celt_encode(fromHandle<CELTEncoder>(state), pcmPtr, nullptr,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C7(encoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_encoder_destroy(fromHandle<CELTEncoder>(state));
}

JNIEXPORT jlong JNICALL C7(decoderCreate)(JNIEnv* env, jobject, jlong mode, jint channels, jintArray error) {
    int err = 0;
    CELTDecoder* st = celt_decoder_create(fromHandle<const CELTMode>(mode), channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C7(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    int result = celt_decode_float(fromHandle<CELTDecoder>(state),
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL C7(decodeShort)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jshortArray out) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = celt_decode(fromHandle<CELTDecoder>(state),
                             dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                             dataPtr != nullptr ? len : 0, outPtr);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C7(decoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_decoder_destroy(fromHandle<CELTDecoder>(state));
}

} // extern "C"
```

`jni_celt11.cpp`:
```cpp
#include <jni.h>
extern "C" {
#include <celt.h>
#include <celt_types.h>
}
#include "jni_handle.h"

#define C11(name) Java_se_lublin_humla_audio_native_Celt11Native_##name

extern "C" {

JNIEXPORT jlong JNICALL C11(encoderCreate)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    CELTEncoder* st = celt_encoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C11(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    int result = celt_encode(fromHandle<CELTEncoder>(state), pcmPtr, frameSize,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C11(encoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_encoder_destroy(fromHandle<CELTEncoder>(state));
}

JNIEXPORT jlong JNICALL C11(decoderCreate)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    CELTDecoder* st = celt_decoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C11(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out, jint frameSize) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    int result = celt_decode_float(fromHandle<CELTDecoder>(state),
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr, frameSize);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL C11(decodeShort)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jshortArray out, jint frameSize) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = celt_decode(fromHandle<CELTDecoder>(state),
                             dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                             dataPtr != nullptr ? len : 0, outPtr, frameSize);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C11(decoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_decoder_destroy(fromHandle<CELTDecoder>(state));
}

} // extern "C"
```

`jni_speex.cpp` (codec only):
```cpp
#include <jni.h>
#include <algorithm>
#include <speex/speex.h>
#include "jni_handle.h"

#define SD(name) Java_se_lublin_humla_audio_native_SpeexDecoderNative_##name

namespace {
struct SpeexDecoderHandle {
    void* state;
    SpeexBits bits;
    int frameSize;
    float* frame;
};
}

extern "C" {

JNIEXPORT jlong JNICALL SD(create)(JNIEnv*, jobject, jint modeId) {
    auto* h = new SpeexDecoderHandle();
    h->state = speex_decoder_init(speex_lib_get_mode(modeId));
    speex_bits_init(&h->bits);
    spx_int32_t frameSize = 0;
    speex_decoder_ctl(h->state, SPEEX_GET_FRAME_SIZE, &frameSize);
    h->frameSize = frameSize;
    h->frame = new float[frameSize];
    return toHandle(h);
}

JNIEXPORT jint JNICALL SD(ctlInt)(JNIEnv*, jobject, jlong handle, jint request, jint value) {
    spx_int32_t v = value;
    return speex_decoder_ctl(fromHandle<SpeexDecoderHandle>(handle)->state, request, &v);
}

JNIEXPORT jint JNICALL SD(decodeFloat)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jfloatArray out) {
    auto* h = fromHandle<SpeexDecoderHandle>(handle);
    if (data != nullptr) {
        jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
        speex_bits_read_from(&h->bits, reinterpret_cast<const char*>(dataPtr), len);
        env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    } else {
        speex_bits_read_from(&h->bits, nullptr, 0);
    }
    int result = speex_decode(h->state, &h->bits, h->frame);
    jsize n = std::min(env->GetArrayLength(out), static_cast<jsize>(h->frameSize));
    env->SetFloatArrayRegion(out, 0, n, h->frame);
    return result;
}

JNIEXPORT void JNICALL SD(destroy)(JNIEnv*, jobject, jlong handle) {
    auto* h = fromHandle<SpeexDecoderHandle>(handle);
    speex_decoder_destroy(h->state);
    speex_bits_destroy(&h->bits);
    delete[] h->frame;
    delete h;
}

} // extern "C"
```

`jni_speexdsp.cpp`:
```cpp
#include <jni.h>
#include <speex/speex_jitter.h>
#include <speex/speex_preprocess.h>
#include <speex/speex_resampler.h>
#include "jni_handle.h"

#define RS(name) Java_se_lublin_humla_audio_native_SpeexResamplerNative_##name
#define JB(name) Java_se_lublin_humla_audio_native_SpeexJitterNative_##name
#define PP(name) Java_se_lublin_humla_audio_native_SpeexPreprocessNative_##name

extern "C" {

// ---- resampler ----

JNIEXPORT jlong JNICALL RS(init)(JNIEnv* env, jobject, jint channels, jint inRate, jint outRate, jint quality, jintArray error) {
    int err = 0;
    SpeexResamplerState* st = speex_resampler_init(channels, inRate, outRate, quality, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL RS(processInt)(JNIEnv* env, jobject, jlong state, jint channelIndex, jshortArray input, jintArray inLen, jshortArray out, jintArray outLen) {
    jint inCount = 0, outCount = 0;
    env->GetIntArrayRegion(inLen, 0, 1, &inCount);
    env->GetIntArrayRegion(outLen, 0, 1, &outCount);
    spx_uint32_t in = static_cast<spx_uint32_t>(inCount);
    spx_uint32_t outN = static_cast<spx_uint32_t>(outCount);
    jshort* inPtr = env->GetShortArrayElements(input, nullptr);
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = speex_resampler_process_int(fromHandle<SpeexResamplerState>(state), channelIndex, inPtr, &in, outPtr, &outN);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
    writeInt(env, inLen, static_cast<jint>(in));
    writeInt(env, outLen, static_cast<jint>(outN));
    return result;
}

JNIEXPORT void JNICALL RS(destroy)(JNIEnv*, jobject, jlong state) {
    speex_resampler_destroy(fromHandle<SpeexResamplerState>(state));
}

// ---- jitter buffer ----

JNIEXPORT jlong JNICALL JB(init)(JNIEnv*, jobject, jint stepSize) {
    return toHandle(jitter_buffer_init(stepSize));
}

JNIEXPORT void JNICALL JB(destroy)(JNIEnv*, jobject, jlong handle) {
    jitter_buffer_destroy(fromHandle<JitterBuffer>(handle));
}

JNIEXPORT void JNICALL JB(put)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jint timestamp, jint span, jint sequence, jint userData) {
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(dataPtr);
    packet.len = static_cast<spx_uint32_t>(len);
    packet.timestamp = static_cast<spx_uint32_t>(timestamp);
    packet.span = static_cast<spx_uint32_t>(span);
    packet.sequence = static_cast<spx_uint16_t>(sequence);
    packet.user_data = static_cast<spx_uint32_t>(userData);
    jitter_buffer_put(fromHandle<JitterBuffer>(handle), &packet); // copies the payload
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

JNIEXPORT jint JNICALL JB(get)(JNIEnv* env, jobject, jlong handle, jbyteArray out, jint desiredSpan, jintArray meta) {
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(outPtr);
    packet.len = static_cast<spx_uint32_t>(env->GetArrayLength(out));
    packet.timestamp = 0;
    packet.span = 0;
    packet.sequence = 0;
    packet.user_data = 0;
    int status = jitter_buffer_get(fromHandle<JitterBuffer>(handle), &packet, desiredSpan, nullptr);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    jint values[5] = {
        static_cast<jint>(packet.len), static_cast<jint>(packet.timestamp), static_cast<jint>(packet.span),
        static_cast<jint>(packet.sequence), static_cast<jint>(packet.user_data)
    };
    env->SetIntArrayRegion(meta, 0, 5, values);
    return status;
}

JNIEXPORT jint JNICALL JB(pointerTimestamp)(JNIEnv*, jobject, jlong handle) {
    return jitter_buffer_get_pointer_timestamp(fromHandle<JitterBuffer>(handle));
}

JNIEXPORT void JNICALL JB(tick)(JNIEnv*, jobject, jlong handle) {
    jitter_buffer_tick(fromHandle<JitterBuffer>(handle));
}

JNIEXPORT jint JNICALL JB(ctl)(JNIEnv* env, jobject, jlong handle, jint request, jintArray value) {
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = jitter_buffer_ctl(fromHandle<JitterBuffer>(handle), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT jint JNICALL JB(updateDelay)(JNIEnv*, jobject, jlong handle) {
    // The packet and start_offset arguments are unused by libspeexdsp's implementation.
    return jitter_buffer_update_delay(fromHandle<JitterBuffer>(handle), nullptr, nullptr);
}

// ---- preprocessor ----

JNIEXPORT jlong JNICALL PP(init)(JNIEnv*, jobject, jint frameSize, jint sampleRate) {
    return toHandle(speex_preprocess_state_init(frameSize, sampleRate));
}

JNIEXPORT jint JNICALL PP(run)(JNIEnv* env, jobject, jlong state, jshortArray frame) {
    jshort* ptr = env->GetShortArrayElements(frame, nullptr);
    int result = speex_preprocess_run(fromHandle<SpeexPreprocessState>(state), ptr);
    env->ReleaseShortArrayElements(frame, ptr, 0);
    return result;
}

JNIEXPORT jint JNICALL PP(ctlInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = speex_preprocess_ctl(fromHandle<SpeexPreprocessState>(state), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL PP(destroy)(JNIEnv*, jobject, jlong state) {
    speex_preprocess_state_destroy(fromHandle<SpeexPreprocessState>(state));
}

} // extern "C"
```

- [ ] **Step 7: Move/replace the submodules and the celt config headers**

```bash
cd /home/becker/git/mumla
mkdir -p libraries/humla/src/main/cpp/third_party
git mv libraries/humla/src/main/jni/opus            libraries/humla/src/main/cpp/third_party/opus
git mv libraries/humla/src/main/jni/celt-0.7.0-src  libraries/humla/src/main/cpp/third_party/celt-0.7.0
git mv libraries/humla/src/main/jni/celt-0.11.0-src libraries/humla/src/main/cpp/third_party/celt-0.11.0
git mv libraries/humla/src/main/jni/celt-0.7.0-build  libraries/humla/src/main/cpp/celt-0.7.0-build
git mv libraries/humla/src/main/jni/celt-0.11.0-build libraries/humla/src/main/cpp/celt-0.11.0-build

# opus: v1.1 -> v1.6.1 (a5d6c1b6f4e582df97390f9ac5c6e7c51cbffffe)
git -C libraries/humla/src/main/cpp/third_party/opus fetch --tags origin
git -C libraries/humla/src/main/cpp/third_party/opus checkout --detach v1.6.1
git add libraries/humla/src/main/cpp/third_party/opus

# speex: replace the pre-split snapshot by the two release tags
git submodule deinit -f libraries/humla/src/main/jni/speex
git rm -f libraries/humla/src/main/jni/speex
rm -rf .git/modules/libraries/humla/src/main/jni/speex
git submodule add https://github.com/xiph/speex    libraries/humla/src/main/cpp/third_party/speex
git -C libraries/humla/src/main/cpp/third_party/speex checkout --detach Speex-1.2.1      # 5dceaaf3e23ee7fd17c80cb5f02a838fd6c18e01
git submodule add https://github.com/xiph/speexdsp libraries/humla/src/main/cpp/third_party/speexdsp
git -C libraries/humla/src/main/cpp/third_party/speexdsp checkout --detach SpeexDSP-1.2.1 # 1b28a0f61bc31162979e1f26f3981fc3637095c8
git add .gitmodules libraries/humla/src/main/cpp/third_party

# the rest of the ndk-build tree, javacpp bindings and tools
git rm -r libraries/humla/src/main/jni
git rm -r libraries/humla/src/main/java/se/lublin/humla/audio/javacpp
git rm libraries/humla/tools/javacpp-0.7.jar libraries/humla/tools/jnigen.sh
git submodule status
```
Expected `git submodule status` (six entries, paths under `src/main/cpp/third_party`): opus `a5d6c1b6…` (v1.6.1), speex `5dceaaf3…` (Speex-1.2.1), speexdsp `1b28a0f6…` (SpeexDSP-1.2.1), celt-0.7.0 `6c79a932…` (v0.7.1), celt-0.11.0 `e3d39fec…` (v0.11.1). `git mv` rewrites the `path =` lines in `.gitmodules` and the gitdir links itself.

In `libraries/humla/.gitignore` replace the two lines `src/main/libs/` and `src/main/obj/` with `.cxx/` (AGP's CMake work directory).

- [ ] **Step 8: Write `CMakeLists.txt`**

Create `libraries/humla/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(humla_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
# Android 15+ requires 16 KB page alignment for shared libraries.
set(HUMLA_LINK_OPTIONS "-Wl,-z,max-page-size=16384")
# celt 0.7/0.11 predate C99-strict clang: keep implicit declarations as warnings, as ndk-build did.
set(CELT_C_OPTIONS -fvisibility=hidden -Wno-error=implicit-function-declaration -Wno-implicit-function-declaration)

# ---------------------------------------------------------------- opus 1.6.1 (upstream CMake)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)            # as the ndk-build flags (-DFIXED_POINT)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/opus" EXCLUDE_FROM_ALL)

add_library(humla_opus SHARED jni_opus.cpp)
target_link_libraries(humla_opus PRIVATE opus log)
target_link_options(humla_opus PRIVATE ${HUMLA_LINK_OPTIONS})

# ---------------------------------------------------------------- speex 1.2.1 (codec)
set(SPEEX_DIR "${THIRD_PARTY}/speex/libspeex")
add_library(speex STATIC
    ${SPEEX_DIR}/bits.c ${SPEEX_DIR}/cb_search.c ${SPEEX_DIR}/exc_10_16_table.c
    ${SPEEX_DIR}/exc_10_32_table.c ${SPEEX_DIR}/exc_20_32_table.c ${SPEEX_DIR}/exc_5_256_table.c
    ${SPEEX_DIR}/exc_5_64_table.c ${SPEEX_DIR}/exc_8_128_table.c ${SPEEX_DIR}/filters.c
    ${SPEEX_DIR}/gain_table.c ${SPEEX_DIR}/gain_table_lbr.c ${SPEEX_DIR}/hexc_10_32_table.c
    ${SPEEX_DIR}/hexc_table.c ${SPEEX_DIR}/high_lsp_tables.c ${SPEEX_DIR}/kiss_fft.c
    ${SPEEX_DIR}/kiss_fftr.c ${SPEEX_DIR}/lpc.c ${SPEEX_DIR}/lsp.c ${SPEEX_DIR}/lsp_tables_nb.c
    ${SPEEX_DIR}/ltp.c ${SPEEX_DIR}/modes.c ${SPEEX_DIR}/modes_wb.c ${SPEEX_DIR}/nb_celp.c
    ${SPEEX_DIR}/quant_lsp.c ${SPEEX_DIR}/sb_celp.c ${SPEEX_DIR}/speex.c
    ${SPEEX_DIR}/speex_callbacks.c ${SPEEX_DIR}/speex_header.c ${SPEEX_DIR}/stereo.c
    ${SPEEX_DIR}/vbr.c ${SPEEX_DIR}/vq.c ${SPEEX_DIR}/window.c)
target_include_directories(speex PUBLIC "${THIRD_PARTY}/speex/include" PRIVATE "${SPEEX_DIR}")
# __EMX__ picks the plain short/int typedefs in speex_types.h (no configure run needed);
# the other three are exactly what Android.mk passed.
target_compile_definitions(speex PUBLIC __EMX__ PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)

add_library(humla_speex SHARED jni_speex.cpp)
target_link_libraries(humla_speex PRIVATE speex log)
target_link_options(humla_speex PRIVATE ${HUMLA_LINK_OPTIONS})

# ---------------------------------------------------------------- speexdsp 1.2.1 (resampler, jitter, preprocess)
set(SPEEXDSP_DIR "${THIRD_PARTY}/speexdsp/libspeexdsp")
add_library(speexdsp STATIC
    ${SPEEXDSP_DIR}/buffer.c ${SPEEXDSP_DIR}/fftwrap.c ${SPEEXDSP_DIR}/filterbank.c
    ${SPEEXDSP_DIR}/jitter.c ${SPEEXDSP_DIR}/kiss_fft.c ${SPEEXDSP_DIR}/kiss_fftr.c
    ${SPEEXDSP_DIR}/mdf.c ${SPEEXDSP_DIR}/preprocess.c ${SPEEXDSP_DIR}/resample.c
    ${SPEEXDSP_DIR}/scal.c)
target_include_directories(speexdsp PUBLIC "${THIRD_PARTY}/speexdsp/include" PRIVATE "${SPEEXDSP_DIR}")
target_compile_definitions(speexdsp PUBLIC __EMX__ PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)

add_library(humla_speexdsp SHARED jni_speexdsp.cpp)
target_link_libraries(humla_speexdsp PRIVATE speexdsp log)
target_link_options(humla_speexdsp PRIVATE ${HUMLA_LINK_OPTIONS})

# ---------------------------------------------------------------- celt 0.7.1 ("CELT alpha")
set(CELT7_DIR "${THIRD_PARTY}/celt-0.7.0/libcelt")
add_library(celt7 STATIC
    ${CELT7_DIR}/bands.c ${CELT7_DIR}/celt.c ${CELT7_DIR}/cwrs.c ${CELT7_DIR}/entcode.c
    ${CELT7_DIR}/entdec.c ${CELT7_DIR}/entenc.c ${CELT7_DIR}/header.c ${CELT7_DIR}/kiss_fft.c
    ${CELT7_DIR}/kiss_fftr.c ${CELT7_DIR}/laplace.c ${CELT7_DIR}/mdct.c ${CELT7_DIR}/modes.c
    ${CELT7_DIR}/pitch.c ${CELT7_DIR}/psy.c ${CELT7_DIR}/quant_bands.c ${CELT7_DIR}/rangedec.c
    ${CELT7_DIR}/rangeenc.c ${CELT7_DIR}/rate.c ${CELT7_DIR}/vq.c)
target_include_directories(celt7 PUBLIC "${CELT7_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/celt-0.7.0-build")
target_compile_definitions(celt7 PRIVATE HAVE_CONFIG_H)
target_compile_options(celt7 PRIVATE ${CELT_C_OPTIONS})

add_library(humla_celt7 SHARED jni_celt7.cpp)
target_link_libraries(humla_celt7 PRIVATE celt7 log)
target_link_options(humla_celt7 PRIVATE ${HUMLA_LINK_OPTIONS})

# ---------------------------------------------------------------- celt 0.11.1 ("CELT beta")
set(CELT11_DIR "${THIRD_PARTY}/celt-0.11.0/libcelt")
add_library(celt11 STATIC
    ${CELT11_DIR}/bands.c ${CELT11_DIR}/celt.c ${CELT11_DIR}/cwrs.c ${CELT11_DIR}/entcode.c
    ${CELT11_DIR}/entdec.c ${CELT11_DIR}/entenc.c ${CELT11_DIR}/header.c ${CELT11_DIR}/kiss_fft.c
    ${CELT11_DIR}/laplace.c ${CELT11_DIR}/mathops.c ${CELT11_DIR}/mdct.c ${CELT11_DIR}/modes.c
    ${CELT11_DIR}/pitch.c ${CELT11_DIR}/plc.c ${CELT11_DIR}/quant_bands.c ${CELT11_DIR}/rate.c
    ${CELT11_DIR}/vq.c)
target_include_directories(celt11 PUBLIC "${CELT11_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/celt-0.11.0-build")
target_compile_definitions(celt11 PRIVATE HAVE_CONFIG_H)
target_compile_options(celt11 PRIVATE ${CELT_C_OPTIONS})

add_library(humla_celt11 SHARED jni_celt11.cpp)
target_link_libraries(humla_celt11 PRIVATE celt11 log)
target_link_options(humla_celt11 PRIVATE ${HUMLA_LINK_OPTIONS})
```

- [ ] **Step 9: Switch `libraries/humla/build.gradle` from ndk-build to CMake**

- Delete the line `sourceSets.main.jniLibs.srcDirs += ['src/main/libs']` and its comment, the whole `def ndkDirectory … tasks.named('preBuild') { dependsOn 'ndkBuild' }` block, and `implementation libs.javacpp`.
- Inside `defaultConfig { }` add:
  ```groovy
        ndk {
            abiFilters += ['arm64-v8a', 'armeabi-v7a', 'x86_64']
        }
        externalNativeBuild {
            cmake {
                // Static libc++: every humla_*.so is self-contained, exports only C JNI
                // entry points and shares no C++ objects or exceptions across libraries,
                // so the NDK's warning about duplicated libc++ state does not apply.
                arguments += ['-DANDROID_STL=c++_static']
            }
        }
  ```
- Inside `android { }` (after `buildFeatures`) add:
  ```groovy
    externalNativeBuild {
        cmake {
            path = file('src/main/cpp/CMakeLists.txt')
            version = '3.22.1'
        }
    }
  ```
- In `gradle/libs.versions.toml` delete `javacpp = "0.7"` and the `javacpp` library entry.

- [ ] **Step 10: Cross-stream hook in `HumlaService`**

`libraries/humla/src/main/java/se/lublin/humla/HumlaService.java`: line 49 `import se.lublin.humla.audio.javacpp.CELT7;` → `import se.lublin.humla.audio.encoder.CELT7Encoder;`; line 356 `auth.addCeltVersions(CELT7.getBitstreamVersion());` → `auth.addCeltVersions(CELT7Encoder.getBitstreamVersion());`.

- [ ] **Step 11: Run the audio tests, then the green gate, then inspect the APK**

Run:
```bash
cd /home/becker/git/mumla && nix develop --command ./gradlew :libraries:humla:testDebugUnitTest --tests 'se.lublin.humla.audio.*'
nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug
unzip -l app/build/outputs/apk/foss/debug/app-foss-debug.apk | grep -E 'lib/(arm64-v8a|armeabi-v7a|x86_64)/libhumla_' | sort
```
Expected: 11 tests PASS (6 encoder, 2 jitter buffer, 1 speex decoder, 2 AudioOutputSpeech); `BUILD SUCCESSFUL`; 15 lines listing `libhumla_celt11.so libhumla_celt7.so libhumla_opus.so libhumla_speex.so libhumla_speexdsp.so` for each of the three ABIs; `grep -rn "javacpp\|ndk-build\|Android.mk" --include='*.gradle' --include='*.java' --include='*.kt' --include='*.mk' . | grep -v /build/` prints nothing.

If the opus subproject fails to configure on `armeabi-v7a` because of its runtime CPU detection (`OPUS_MAY_HAVE_NEON`), add `set(OPUS_DISABLE_INTRINSICS ON CACHE BOOL "" FORCE)` above the `add_subdirectory` line (the ndk-build flavor never used intrinsics either).

- [ ] **Step 12: Update `NOTICE.md`**

Change the rows to: `| opus | 1.6.1 | BSD-3-Clause | https://github.com/xiph/opus |`, `| speex | 1.2.1 | BSD-3-Clause | https://github.com/xiph/speex |`, add `| speexdsp | 1.2.1 | BSD-3-Clause | https://github.com/xiph/speexdsp |`, and delete the JavaCPP row.

- [ ] **Step 13: Commit**

```bash
cd /home/becker/git/mumla
git add -A .gitmodules gradle/libs.versions.toml libraries/humla NOTICE.md
git commit -m "build(humla): build native codecs with cmake and hand-written jni" -m "Replaces ndk-build, Android.mk and the javacpp bindings with one CMakeLists.txt, five small JNI shared libraries and Kotlin external-fun wrappers behind fakeable interfaces. opus moves to v1.6.1 and the pre-split speex snapshot is replaced by the Speex-1.2.1 and SpeexDSP-1.2.1 release tags; third-party sources now live under src/main/cpp/third_party."
```

---

### Task 10: F6 (part 3) — NDK 29.0.14206865, SDK CMake 4.1.2, build-tools 36.1.0

**Files:**
- Modify: `flake.nix:21-30,54-63`, `libraries/humla/build.gradle`, `app/build.gradle`

**Interfaces:**
- Consumes: the pinned nixpkgs revision in `flake.lock` (`e554fab72f81915600f3f449b786fd9af40439a5`), whose `androidenv/repo.json` lists `ndk 29.0.14206865`, `cmake 4.1.2` and `build-tools 36.1.0` (verified; no `nix flake update` is needed).
- Produces: the toolchain versions required by spec §2 for every later stream.

- [ ] **Step 1: Update `flake.nix`**

Replace lines 21–30 — the whole `androidSdk = …;` binding, from `androidSdk = pkgs.androidenv.composeAndroidPackages {` through its closing `};` — with:
```nix
        androidSdk = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "36.1.0" ];
          platformVersions = [ "36" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
          includeNDK = true;
          ndkVersions = [ "29.0.14206865" ];
          cmakeVersions = [ "4.1.2" ];
        };
```
and in the `shellHook` change `export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"` to `export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"` and the aapt2 override to `-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/36.1.0/aapt2`.

- [ ] **Step 2: Update the Gradle side**

`libraries/humla/build.gradle`: `ndkVersion = '29.0.14206865'`; add `buildToolsVersion = '36.1.0'` right after it; in `externalNativeBuild { cmake { … } }` set `version = '4.1.2'`.
`app/build.gradle`: add `buildToolsVersion = '36.1.0'` after `compileSdk = 36`.

- [ ] **Step 3: Rebuild the dev shell and run the green gate**

Run: `cd /home/becker/git/mumla && nix develop --command bash -c 'ls $ANDROID_HOME/ndk $ANDROID_HOME/cmake $ANDROID_HOME/build-tools' && nix develop --command ./gradlew clean assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: `29.0.14206865`, `4.1.2`, `36.1.0` listed; `BUILD SUCCESSFUL`; `grep -o 'cmake/[0-9.]*' libraries/humla/.cxx/*/*/arm64-v8a/build.ninja | head -1` reports `cmake/4.1.2`.

- [ ] **Step 4: Commit**

```bash
cd /home/becker/git/mumla
git add flake.nix libraries/humla/build.gradle app/build.gradle
git commit -m "build: bump ndk, sdk cmake and build-tools" -m "NDK 29.0.14206865, SDK CMake 4.1.2 and build-tools 36.1.0, in flake.nix and in both module build files."
```

---

### Task 11: F7 — CI on a Nix image

**Files:**
- Rewrite: `.gitlab-ci.yml`

**Interfaces:**
- Consumes: `flake.nix` dev shell (`devShells.x86_64-linux.default`), `git` inside the shell (for `git describe`), submodules.
- Produces: one `build` job running `nix develop --command ./gradlew assembleFossDebug assembleGoogDebug test lint` — spec §6's acceptance command verbatim — caching `~/.gradle` (relocated into the project) and a file-backed Nix binary cache of the dev shell closure.

- [ ] **Step 1: Confirm the acceptance command is green locally**

Lint has been part of the green gate since Task 2 (which fixed the 30 errors it reported and made `abortOnError` meaningful again), so this is a confirmation, not new work. The one thing the gate does *not* cover is packaging the `goog` flavor, which was last assembled in Task 5.

Run: `cd /home/becker/git/mumla && nix develop --command ./gradlew --console=plain assembleFossDebug assembleGoogDebug test lint`
Expected: `BUILD SUCCESSFUL`. Warnings do not fail the build; errors are fixed at their source (never by disabling a check).

- [ ] **Step 2: Write `.gitlab-ci.yml`**

```yaml
# Builds and tests Mumla inside the Nix dev shell defined by flake.nix.
image: nixos/nix:latest

variables:
  GIT_SUBMODULE_STRATEGY: recursive
  NIX_CONFIG: "experimental-features = nix-command flakes"
  GRADLE_USER_HOME: "$CI_PROJECT_DIR/.gradle"
  DEVSHELL: ".#devShells.x86_64-linux.default"

cache:
  key: ${CI_PROJECT_ID}-nix-gradle
  paths:
    - .gradle/caches/
    - .gradle/wrapper/
    - .nix-cache/

stages:
  - build

build:
  stage: build
  before_script:
    - mkdir -p .nix-cache .gradle
    # Realise the dev shell, preferring the file-backed cache restored by GitLab over cache.nixos.org.
    - nix build --print-build-logs --out-link .devshell
        --substituters "file://$CI_PROJECT_DIR/.nix-cache https://cache.nixos.org"
        "$DEVSHELL"
  script:
    - nix develop "$DEVSHELL" --command ./gradlew --console=plain assembleFossDebug assembleGoogDebug test lint
  after_script:
    # Export the dev shell closure so the next pipeline does not rebuild the SDK/NDK.
    - nix copy --to "file://$CI_PROJECT_DIR/.nix-cache" "$DEVSHELL" || true
  artifacts:
    when: always
    paths:
      - app/build/outputs/apk/
      - app/build/reports/
      - libraries/humla/build/reports/
      - app/build/test-results/
      - libraries/humla/build/test-results/
    expire_in: 3 months
```

- [ ] **Step 3: Dry-run the job's commands locally**

Run:
```bash
cd /home/becker/git/mumla && export NIX_CONFIG="experimental-features = nix-command flakes"
nix build --out-link /tmp/claude-1000/-home-becker-git-mumla/devshell ".#devShells.x86_64-linux.default"
nix develop ".#devShells.x86_64-linux.default" --command ./gradlew --console=plain assembleFossDebug assembleGoogDebug test lint
```
Expected: both succeed (`BUILD SUCCESSFUL`). This is spec §6's acceptance command, so CI and the acceptance criterion are the same command. `test` runs the unit tests of every flavor's debug variant (AGP 9 only creates unit tests for the tested build type).

- [ ] **Step 4: Commit**

```bash
cd /home/becker/git/mumla
git add .gitlab-ci.yml
git commit -m "build(ci): build, test and lint inside the nix dev shell" -m "Uses the nixos/nix image, runs the acceptance command from section 6 of the spec, and caches the Gradle home plus a file-backed binary cache of the dev shell closure."
```

---

### Task 12: F8 — README

**Files:**
- Rewrite: `README.md`
- Delete: `libraries/humla/README.md` (its build notes describe the standalone humla repo and humla-spongycastle, which no longer exist; the license header/LICENSE file stay)

**Interfaces:** none (documentation).

- [ ] **Step 1: Write `README.md`**

```markdown
# Mumla

Mumla is a [Mumble](https://www.mumble.info/) voice-chat client for Android
(GPLv3). It is a fork and continuation of
[Plumble](https://github.com/acomminos/Plumble) by Andrew Comminos and ships
its own protocol/audio library, Humla (a fork of Comminos's Jumble), in
`libraries/humla`.

Mumla runs on Android 12 (API 31) and later. It is available
[on F-Droid](https://f-droid.org/packages/se.lublin.mumla/) and on Google
Play; there is a small [landing page](https://mumla-app.gitlab.io/).

## Building

The supported development environment is the Nix flake in this repository.
With [Nix](https://nixos.org/) installed and flakes enabled:

    git clone --recursive https://gitlab.com/quite/mumla.git
    cd mumla
    nix develop

The shell provides JDK 21, the Android SDK (platform 36, build-tools 36.1.0),
NDK 29.0.14206865, SDK CMake 4.1.2, meson/ninja and `git`. Inside it:

    ./gradlew assembleFossDebug      # F-Droid flavor
    ./gradlew assembleGoogDebug      # Google Play flavor (Play Billing)
    ./gradlew test                   # unit tests of every module (JVM, Robolectric)
    ./gradlew lint

If you cloned without `--recursive`, run `git submodule update --init --recursive`
first: the native codecs (opus, speex, speexdsp, CELT) are git submodules under
`libraries/humla/src/main/cpp/third_party`.

[direnv](https://direnv.net/) users can `direnv allow` to enter the shell
automatically. The same shell is what CI uses (`.gitlab-ci.yml`).

## Repository layout

- `app/` — the Android application (`se.lublin.mumla`), product flavors
  `foss`, `goog`, `donation`, `beta`.
- `libraries/humla/` — the Mumble protocol implementation and audio pipeline
  (`se.lublin.humla`); `src/Mumble.proto` is compiled at build time,
  `src/main/cpp/CMakeLists.txt` builds the codecs and their JNI glue.
- `docs/superpowers/` — the modernization specification and implementation plans.
- `NOTICE.md` — third-party components and licenses.

## Contributing

- **Tests first.** Every behavior change starts with a failing JVM test
  (JUnit 4, Robolectric for Android classes, MockK, Google Truth,
  `kotlinx-coroutines-test`); native code stays a thin JNI pass-through and
  the Kotlin side is tested against fakes. `./gradlew test lint` must be green.
- **Kotlin.** New files are Kotlin; a Java file you change substantially is
  converted first, as its own commit.
- **Conventional Commits**, in English: `feat:`, `fix:`, `refactor:`,
  `build:`, `test:`, `docs:`, `chore:`, optional scope such as `fix(humla):`,
  imperative subject of at most 72 characters, no trailers.
- **Strings** go to `app/src/main/res/values/strings.xml`; translations are
  handled on [Weblate](https://hosted.weblate.org/engage/mumla/) — please do
  not edit the translated resource files directly.
- Work is organized in streams described in
  `docs/superpowers/specs/2026-09-19-mumla-modernization.md`; check the
  ownership table there before touching shared files.

## FAQ

**An action my user has permission for does not show up in the overflow menu.**
Disconnect and reconnect. Menu items are decided from the permissions known
when the UI was set up and are not updated while connected.

## License

GNU GPL v3, see [LICENSE](LICENSE). Third-party notices are in
[NOTICE.md](NOTICE.md).
```

- [ ] **Step 2: Remove the stale humla README and verify**

Run: `cd /home/becker/git/mumla && git rm -q libraries/humla/README.md && nix develop --command ./gradlew assembleFossDebug testFossDebugUnitTest :libraries:humla:testDebugUnitTest :app:lintFossDebug :libraries:humla:lintDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /home/becker/git/mumla
git add README.md
git commit -m "docs: rewrite README for the nix dev shell and contribution rules"
```

---

## Self-review against the spec

- F2 → Task 1 (inline, keep licenses, re-register four submodules at the same commits, delete humla-spongycastle, exact commit message). F3 → Tasks 2 and 3 (Gradle/catalog/minSdk/toolchain/nonTransitiveRClass/no Jetifier; AGP 9's built-in Kotlin instead of a separately declared `kotlin-android`/KGP, which AGP 9 rejects — see the Task 2 design notes; test stack; one Robolectric smoke test per module, `ServerParcelTest` in humla and `AppResourcesSmokeTest` in app, both inside Task 2; `Settings.kt` with mapping tests). Task 2 also makes `lint` pass for the first time in its own `fix(lint):` commit and adds it to the green gate, so "lint runs with abortOnError = true" holds from there to the end of the stream. F4 → Tasks 5 and 6 (latest stable AndroidX/material/jsoup/billing/minidns; netcipher kept + noted; guava dropped; protobuf plugin, no checked-in `Mumble.java`). F5 → Task 7 (BouncyCastle, Mumble-style fixture generated in the test). The one F5 clause this plan does *not* carry is the conditional "if not, implement a minimal Kotlin PKCS#12 reader": stock BouncyCastle 1.86 does read the unencrypted keyBag (verified against `bcgit/bc-java` `main`), so the condition is false and Task 7 says so explicitly instead of carrying an unexecutable branch; if the fixture test ever fails, that reader becomes its own task. F6 → Tasks 8, 9, 10 (CMake, hand-written JNI, the eight `*Native` objects with the spec's names, Java call sites adapted, opus latest tag, speex split to release tags, NDK/CMake/build-tools bump in `flake.nix` and `libraries/humla/build.gradle`). F7 → Task 11. F8 → Task 12. The "delete API < 31 paths in files you touch" rule → Task 4 (`CertificateExportActivity`); other guarded files are owned by A/D/P and listed under Cross-stream touches.
- §6 acceptance items owned by F after Task 12: `./gradlew assembleFossDebug assembleGoogDebug test lint` is exactly what Task 11 runs locally and in CI; no `javacpp`, `spongycastle`, `guava`, `ndk-build` left in the tree (Task 9 step 11 greps); `NOTICE.md` lists opus, speex, speexdsp, celt, BouncyCastle, minidns, jsoup, netcipher (rnnoise and webrtc-audio-processing are added by stream B).
- Cross-stream: the table above lists every file this stream touches that another stream owns, including the six audio files Task 8 rewrites in Kotlin, the javacpp package and `src/main/cpp/**` (Task 9), and the nine files Task 2 touches to clear lint. Stream B's plan must be written against the Kotlin files and the `*Api` seams, not against the Java originals.
- Type consistency: `Pkcs12Certificates.load(ByteArray, String?)` / `load(InputStream, CharArray)` are used with those exact shapes in `HumlaConnection` and `CertificateImportActivity`; the `*Api` signatures in Task 9 step 3 match their JNI counterparts in step 6 (argument order, array in/out parameters) and their fakes in step 1; `SpeexJitterBuffer.get` returns `Packet(status, length, userData)` as consumed in `AudioOutputSpeech` and asserted in `SpeexJitterBufferTest`; `AudioOutputSpeech`'s two extra constructor parameters are `opusApi: OpusDecoderApi` and `jitterApi: SpeexJitterApi`, in that order, in the class, in `AudioOutputSpeechTest` and in the Task 9 Files list.

## Version research (2026-09-19)

| What | Found | Where |
|---|---|---|
| Gradle current | 9.7.1 | https://services.gradle.org/versions/current |
| AGP latest stable | 9.4.1 (9.5.0-alpha06 is the newest pre-release) | https://dl.google.com/dl/android/maven2/com/android/tools/build/group-index.xml |
| AGP 9.4 requirements | min Gradle 9.6.0, JDK 17, build-tools ≥ 36.0.0; AGP 9.0+ has built-in Kotlin (runtime dependency on KGP 2.2.10, `org.jetbrains.kotlin.android` must not be applied, `android.builtInKotlin`/`android.newDsl` default true, `applicationVariants` removed → `androidComponents.onVariants`, `jvmTarget` follows `compileOptions.targetCompatibility`) | https://developer.android.com/build/releases/gradle-plugin, https://developer.android.com/build/releases/agp-9-0-0-release-notes, https://developer.android.com/build/migrate-to-built-in-kotlin |
| Kotlin Gradle plugin | not declared by this build. The standalone KGP is at 2.4.20 and supports Gradle 7.6.3–9.7.0, which is *older* than the 9.7.1 wrapper this plan pins — another reason not to force it onto the classpath. AGP 9.4.1 brings its own (≥ 2.2.10); Task 2 step 10 verifies which one. | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml, https://kotlinlang.org/docs/gradle-configure-project.html, https://developer.android.com/build/migrate-to-built-in-kotlin |
| kotlinx-coroutines (android, test) | 1.11.0 | Maven Central metadata |
| Robolectric | 4.17 (release notes list SDK 36 support) | https://api.github.com/repos/robolectric/robolectric/releases/latest |
| MockK / Truth / JUnit | 1.14.11 / 1.4.5 / 4.13.2 | Maven Central metadata |
| androidx.test core / ext junit | 1.7.0 / 1.3.0 | Google Maven group-index |
| AndroidX stable | appcompat 1.8.0, activity 1.13.0, core 1.19.0, fragment 1.9.0, recyclerview 1.4.0, preference 1.2.1, exifinterface 1.4.2, documentfile 1.1.0, cardview 1.0.0 | Google Maven group-index |
| Material | 1.14.0 | Google Maven group-index |
| Play Billing | 9.1.0; 8.0 removed the SKU APIs and changed `ProductDetailsResponseListener` to `(BillingResult, QueryProductDetailsResult)` | Google Maven, https://developer.android.com/google/play/billing/release-notes |
| BouncyCastle | bcprov-jdk18on / bcpkix-jdk18on 1.86; stock `PKCS12KeyStoreSpi.engineLoad` processes `keyBag` in plain `data` | Maven Central metadata, https://raw.githubusercontent.com/bcgit/bc-java/main/prov/src/main/java/org/bouncycastle/jcajce/provider/keystore/pkcs12/PKCS12KeyStoreSpi.java |
| Mumble certificate format | `PKCS12_create(SSL_STRING(""), SSL_STRING("Mumble Identity"), pkey, x509, certs, -1, -1, 0, 0, 0)` (no key/cert encryption, empty password MAC) | https://raw.githubusercontent.com/mumble-voip/mumble/master/src/mumble/Cert.cpp line 548 |
| Spongycastle 1.51.0.0 core/prov/pkix | present on Maven Central (HTTP 200) | https://repo1.maven.org/maven2/com/madgag/spongycastle/ |
| protobuf-java / protoc / plugin | 4.36.2 / 4.36.2 / protobuf-gradle-plugin 0.10.0 (2026-04-20, requires Gradle ≥ 7.6, AGP 9 issues closed) | Maven Central metadata, https://github.com/google/protobuf-gradle-plugin |
| jsoup | 1.23.2 (only `Jsoup.parseBodyFragment` is used) | Maven Central metadata |
| minidns | hla 1.1.1 exists but android21 tops out at 1.1.0-alpha3; 1.0.5 is the latest stable set with `AndroidUsingLinkProperties.setup` and `ResolverApi.resolveSrv(String)` | Maven Central metadata, https://github.com/MiniDNS/minidns (tag 1.0.5 sources) |
| netcipher | 2.1.0 stable, 2.2.0-alpha (2021-05); repo last commit 2020-12-22 | Maven Central metadata, https://api.github.com/repos/guardianproject/NetCipher/commits |
| JetBrains annotations | 26.1.0 | Maven Central metadata |
| opus | v1.6.1 = `a5d6c1b6f4e582df97390f9ac5c6e7c51cbffffe`; CMake ≥ 3.16; DRED/OSCE/DEEP_PLC (DNN) off by default; no submodules | https://api.github.com/repos/xiph/opus/git/refs/tags/v1.6.1, https://raw.githubusercontent.com/xiph/opus/v1.6.1/CMakeLists.txt |
| speex / speexdsp | Speex-1.2.1 = `5dceaaf3e23ee7fd17c80cb5f02a838fd6c18e01`, SpeexDSP-1.2.1 = `1b28a0f61bc31162979e1f26f3981fc3637095c8`; source lists from `libspeex/Makefile.am` and `libspeexdsp/Makefile.am`; `speex_types.h`/`speexdsp_types.h` still honor `__EMX__`; `jitter_buffer_update_delay` ignores its packet/offset arguments; `jitter_buffer_put` copies the payload | https://api.github.com/repos/xiph/speex/tags, https://api.github.com/repos/xiph/speexdsp/tags and the raw files at those tags |
| CELT | quite/celt tags v0.7.1 (`6c79a932…`) and v0.11.1 (`e3d39fec…`) kept; API signatures read from the checked-out `libcelt/celt.h` of each | local submodules |
| Nix toolchain | pinned nixpkgs `e554fab72f81915600f3f449b786fd9af40439a5` ships `ndk 29.0.14206865`, `cmake 4.1.2`, `build-tools 36.1.0` in `androidenv/repo.json` | https://raw.githubusercontent.com/NixOS/nixpkgs/e554fab72f81915600f3f449b786fd9af40439a5/pkgs/development/mobile/androidenv/repo.json |
