{
  description = "sorchat: chat server and its NixOS module";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # Gradle for building the server, pinned separately: nix/deps.json is only valid for the Gradle
    # version it was recorded with, so this must not follow another nixpkgs. Bump it together with
    # regenerating deps.json.
    nixpkgs-gradle.url = "github:NixOS/nixpkgs/c59305bab2065cfecc4944690d9eedbb56f3a9fa";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    nixpkgs-gradle,
    ...
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
          };
        };
      in {
        devShells.default = pkgs.mkShell {};

        packages = rec {
          # Built from source; see nix/sorchat-server.nix for updating its dependency lock.
          sorchat-server = pkgs.callPackage ./nix/sorchat-server.nix {
            inherit (nixpkgs-gradle.legacyPackages.${system}) gradle_9;
          };
          default = sorchat-server;
        };
      }
    )
    // {
      # services.sorchat (deploy/sorchat.nix), defaulting to this flake's server package.
      nixosModules.default = {
        lib,
        pkgs,
        ...
      }: {
        imports = [./deploy/sorchat.nix];
        services.sorchat.package = lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.sorchat-server;
      };
    };
}
