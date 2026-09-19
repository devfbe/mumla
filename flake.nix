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
        # gradle.properties, so these literals and the ones in gradle.properties (read by both
        # libraries/humla/build.gradle and app/build.gradle) must be kept in sync by hand. Every
        # other flake.nix reference to these versions goes through these bindings.
        ndkVersion = "29.0.14206865";
        buildToolsVersion = "36.1.0";
        cmakeVersion = "4.1.2";

        # Android SDK configuration
        androidSdk = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ buildToolsVersion ];
          platformVersions = [ "36" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          cmakeVersions = [ cmakeVersion ];
        };

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

      in {
        devShells.default = pkgs.mkShell {
          name = "mumla-android-dev";
          inherit buildInputs shellHook;
        };
      }
    );
}
