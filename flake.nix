{
  description = "Mumla Android development environment with Nix flakes";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Single source for the Android toolchain version pins within this file: Nix cannot read
        # gradle.properties, so these literals and the ones in gradle.properties (read by
        # build-logic and libraries/humla/build.gradle.kts) must be kept in sync by hand. Every
        # other flake.nix reference to these versions goes through these bindings.
        ndkVersion = "29.0.14206865";
        buildToolsVersion = "37.0.0";
        cmakeVersion = "4.1.2";

        # Android SDK configuration
        androidSdk = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ buildToolsVersion ];
          platformVersions = [ "37.0" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          cmakeVersions = [ cmakeVersion ];
        };

        # Android Studio wired to the same SDK/NDK the command-line build uses (unfree; opt-in via
        # `nix run .#android-studio` or `nix develop .#studio`, so plain `nix develop` stays small).
        androidStudio = pkgs.android-studio.withSdk androidSdk.androidsdk;

        # Java/JDK 21
        jdk = pkgs.jdk21;

        # Python and build tools
        python = pkgs.python3;

        buildInputs = with pkgs; [
          jdk
          gradle
          androidSdk.androidsdk
          cmake
          ninja
          meson
          pkg-config
          python
          git
          autoconf
          automake
          libtool
          clang-tools
        ];

        shellHook = ''
          export ANDROID_HOME="${androidSdk.androidsdk}/libexec/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/${ndkVersion}"
          export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
          export JAVA_HOME="${jdk}"
          export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

          # AAPT2 override for NixOS compatibility
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildToolsVersion}/aapt2"

          echo "Android development environment loaded"
          echo "  ANDROID_HOME: $ANDROID_HOME"
          echo "  ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
          echo "  JAVA_HOME: $JAVA_HOME"
        '';

        studioLauncher = pkgs.writeShellScriptBin "mumla-android-studio" ''
          export PATH="${pkgs.lib.makeBinPath buildInputs}:$PATH"
          ${shellHook}
          exec ${androidStudio}/bin/android-studio "$@"
        '';

        # Piper voices for the device tests' speech corpus (tools/speech-corpus/generate.sh), pinned
        # by hash. Far end: two male voices; near end: two female voices.
        piperVoice = path: onnxHash: jsonHash:
          let
            name = builtins.baseNameOf path;
            url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/${path}";
          in [
            { name = "${name}.onnx"; path = pkgs.fetchurl { url = "${url}.onnx"; hash = onnxHash; }; }
            { name = "${name}.onnx.json"; path = pkgs.fetchurl { url = "${url}.onnx.json"; hash = jsonHash; }; }
          ];
        piperVoices = pkgs.linkFarm "mumla-piper-voices" (
          piperVoice "de/de_DE/thorsten/medium/de_DE-thorsten-medium"
            "sha256-fmR2LY5RGLtXjy7qYgfho1qODDBZUBC2ZvmD/Ie7eBk="
            "sha256-l0re55BTOtsnOhrIj0kCfSobjw8s9JBZVKR5HnkmToU="
          ++ piperVoice "en/en_US/ryan/medium/en_US-ryan-medium"
            "sha256-q/TCdIYlZO1ke6DSxH+O58m3F9J72tkhkQDrMQ20BHo="
            "sha256-RANMBWyxVoGyrUlDB8fz8uRJnRJTxwDHEfoKRgf/540="
          ++ piperVoice "de/de_DE/kerstin/low/de_DE-kerstin-low"
            "sha256-01KnZBiSzr8pA4Wa+U6bqBoUERAhX+OUO82n99pAG3o="
            "sha256-VucIVWt7m3pTxPiVfgIUIeafEaYAliu6VUz/vnLPLUc="
          ++ piperVoice "en/en_US/lessac/medium/en_US-lessac-medium"
            "sha256-Xv4J5pkCGHgnr2RuGm6dJp3udp+Yd9F7FrG0buqvAZ8="
            "sha256-7+GcQXvtBV8taZCCSMa6ZQ+hNbyGiw5quz2hgdq2kKA="
        );

        # Writes libraries/humla/src/testSpeech/speech/*.wav + MANIFEST.txt; run from the checkout's root.
        speechCorpus = pkgs.writeShellApplication {
          name = "mumla-speech-corpus";
          runtimeInputs = with pkgs; [ piper-tts sox coreutils gnused ];
          text = ''
            export PIPER_VOICES="${piperVoices}"
            export PIPER_VERSION="${pkgs.piper-tts.version}"
            exec bash ${./tools/speech-corpus/generate.sh} "$@"
          '';
        };

        # The voice the app ships for its double-talk self-test: MIT-licensed, trained from scratch
        # on the public-domain LJ Speech dataset, so it may be redistributed inside the APK.
        selftestVoice = pkgs.linkFarm "mumla-selftest-voice" (
          piperVoice "en/en_US/ljspeech/medium/en_US-ljspeech-medium"
            "sha256-b1KnUeI0mr56dnNesJ3Bh1KYx36iNC/9L+95/4G4fyI="
            "sha256-FB1hLMCpXtfvwcqTa4RcI2SWfy6SF8Xb/PafxNbGWGA="
        );

        # Writes app/src/main/res/raw/double_talk_voice.ogg + tools/selftest-clip/MANIFEST.txt;
        # run from the checkout's root.
        selftestClip = pkgs.writeShellApplication {
          name = "mumla-selftest-clip";
          runtimeInputs = with pkgs; [ piper-tts sox opusTools coreutils gnused ];
          text = ''
            export PIPER_VOICES="${selftestVoice}"
            export PIPER_VERSION="${pkgs.piper-tts.version}"
            exec bash ${./tools/selftest-clip/generate.sh} "$@"
          '';
        };
      in {
        devShells.default = pkgs.mkShell {
          name = "mumla-android-dev";
          inherit buildInputs shellHook;
        };

        devShells.studio = pkgs.mkShell {
          name = "mumla-android-studio";
          buildInputs = buildInputs ++ [ androidStudio ];
          inherit shellHook;
        };

        packages.android-studio = androidStudio;

        # Same environment as `nix develop` (SDK/NDK paths, JDK, the NixOS aapt2 override, build
        # tools on PATH), so a Gradle sync inside Studio behaves like the command-line build.
        apps.android-studio = {
          type = "app";
          program = "${studioLauncher}/bin/mumla-android-studio";
        };

        # `nix run .#speech-corpus` regenerates the device tests' speech clips (commit the result).
        apps.speech-corpus = {
          type = "app";
          program = "${speechCorpus}/bin/mumla-speech-corpus";
        };

        # `nix run .#selftest-clip` regenerates the self-test's voice clip (commit the result).
        apps.selftest-clip = {
          type = "app";
          program = "${selftestClip}/bin/mumla-selftest-clip";
        };
      }
    );
}
