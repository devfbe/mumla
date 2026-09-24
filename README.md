# Software maintenance situation

Current maintainer [Daniel Lublin](https://lublin.se)
([@quite@mstdn.social](https://mstdn.social/@quite) on fedi) has very
little time to do voluntary work on Mumla. My focus is strictly on
maintaining stability and security. This includes migrations to newer
Android SDKs, as they become requirements by Google/Alphabet for even
getting updates published on Google Play. There are also other
maintenance and administrative work, which barely gets done in a
timely manner.

At some point I expect Mumla to disappear from Google Play, because
there will be some requirement that I did not have time to fulfill.
Eventually it will also rot and no longer work well in general on
newer releases of Android.

Mumla needs a new maintainer that can allocate time to take on, to
begin with, all these tasks. To maintain stability and security. And
then hopefully also work with the community on for example protocol
parity with desktop Mumble, support for various hardware accessories,
general usability, and new features.

Until there is a new maintainer with time on their hands you cannot
expect new features, or even the continued existence of a usable
Mumble app for Android.

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
    ./gradlew lintFossDebug lintGoogDebug lintDonationDebug lintBetaDebug \
              :libraries:humla:lintDebug

If you cloned without `--recursive`, run `git submodule update --init --recursive`
first. `libraries/humla` is an ordinary directory in this repository, not a
submodule; the submodules are the third-party native sources under
`libraries/humla/src/main/cpp/third_party/` — the codecs opus, speex, CELT
0.7.0 and CELT 0.11.0, and the audio processing libraries speexdsp, RNNoise,
webrtc-audio-processing and its dependency abseil-cpp — built for
`arm64-v8a`, `armeabi-v7a` and `x86_64` via CMake with hand-written JNI glue.

[direnv](https://direnv.net/) users can `direnv allow` to enter the shell
automatically. The same shell is what CI uses (`.gitlab-ci.yml`).

If you get an error running out of Java heap space, try raising the `-Xmx` in
`gradle.properties`.

## Repository layout

- `app/` — the Android application (`se.lublin.mumla`), product flavors
  `foss`, `goog`, `donation`, `beta`.
- `libraries/humla/` — the Mumble protocol implementation and audio pipeline
  (`se.lublin.humla`); `src/Mumble.proto` is compiled to Java at build time
  (nothing generated is checked in), crypto uses BouncyCastle, and
  `src/main/cpp/CMakeLists.txt` builds the native codecs and audio processing
  libraries and their JNI glue.
- `docs/superpowers/` — the ongoing modernization specification and plans.
- `NOTICE.md` — third-party components and licenses.

## Contributing

- **Tests first.** Every behavior change starts with a failing JVM test
  (JUnit 4, Robolectric for Android classes, MockK, Google Truth,
  `kotlinx-coroutines-test`); native code stays a thin JNI pass-through and
  the Kotlin side is tested against fakes.
  `./gradlew assembleFossDebug assembleGoogDebug test lintFossDebug
  lintGoogDebug lintDonationDebug lintBetaDebug :libraries:humla:lintDebug`
  (CI's acceptance command, verbatim: all four product flavors and both
  modules) must be green. The bare `lint` task only covers `betaDebug`, so
  every flavor is named.
- **Kotlin.** New files are Kotlin; a Java file you change substantially is
  converted first, as its own commit.
- **Conventional Commits**, in English: `feat:`, `fix:`, `refactor:`,
  `build:`, `test:`, `docs:`, `chore:`, optional scope such as `fix(humla):`,
  imperative subject of at most 72 characters, no trailers.
- **Strings**: only the base (English) texts are edited in the repository, in
  `app/src/main/res/values/` (`strings.xml`, `preference.xml`) or
  `libraries/humla/src/main/res/values/strings.xml`; translations are
  handled on [Weblate](https://hosted.weblate.org/engage/mumla/) — please do
  not edit the translated resource files directly.
- Work is currently organized in streams described in
  `docs/superpowers/specs/2026-09-19-mumla-modernization.md`; check the
  ownership table there before touching shared files.

## FAQ

**An action my user has permission for does not show up in the overflow menu.**
Disconnect and reconnect. Menu items are decided from the permissions known
when the UI was set up and are not updated while connected.

## License

GNU GPL v3, see [LICENSE](LICENSE). Third-party notices are in
[NOTICE.md](NOTICE.md).
