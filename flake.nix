{
  description = "sorchat: chat server and its NixOS module";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
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
          sorchat-server = pkgs.callPackage ./nix/sorchat-server.nix {};
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
