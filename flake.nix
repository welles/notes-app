{
  description = "Notes – personal notes app (server, Android, desktop)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config = {
          # Only needed for the Android SDK in the dev shell (androidenv bundles several unfree zips).
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      }));

      # Native libraries Compose Desktop (Skia/AWT) needs at runtime on Linux.
      desktopRuntimeLibs = pkgs: with pkgs; [
        libGL fontconfig freetype
        xorg.libX11 xorg.libXext xorg.libXrender xorg.libXtst xorg.libXi xorg.libXrandr xorg.libXcursor
      ];

      androidSdkFor = pkgs: (pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37" ];
        buildToolsVersions = [ "36.0.0" "37.0.0" ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      }).androidsdk;
    in
    {
      devShells = forAllSystems (pkgs:
        let
          jdk = pkgs.jdk21;
          androidSdk = androidSdkFor pkgs;
        in
        {
          # Server + desktop client (no Android SDK, small).
          default = pkgs.mkShell {
            packages = [ pkgs.dotnet-sdk_10 jdk ];
            JAVA_HOME = jdk.home;
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (desktopRuntimeLibs pkgs);
            DOTNET_CLI_TELEMETRY_OPTOUT = "1";
            DOTNET_ROOT = "${pkgs.dotnet-sdk_10}/share/dotnet";
          };

          # Everything including the Android SDK: `nix develop .#android`
          android = pkgs.mkShell {
            packages = [ pkgs.dotnet-sdk_10 jdk androidSdk ];
            JAVA_HOME = jdk.home;
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (desktopRuntimeLibs pkgs);
            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
            # The aapt2 binary AGP downloads from Maven is not NixOS-compatible; use the SDK's one.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/37.0.0/aapt2";
            DOTNET_CLI_TELEMETRY_OPTOUT = "1";
            DOTNET_ROOT = "${pkgs.dotnet-sdk_10}/share/dotnet";
          };
        });
    };
}
