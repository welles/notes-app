{
  description = "Notes – personal notes app (server, Android, desktop)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      # The desktop package (notes-desktop) follows in step 9.
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [
            pkgs.dotnet-sdk_10
            pkgs.jdk21
            pkgs.gradle
          ];
          DOTNET_CLI_TELEMETRY_OPTOUT = "1";
          DOTNET_ROOT = "${pkgs.dotnet-sdk_10}/share/dotnet";
        };
      });
    };
}
