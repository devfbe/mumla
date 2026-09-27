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
      }
    );
}
