{
  description = "A basic Rust devshell for NixOS users developing Leptos";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
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
      in
        with pkgs; {
          devShells.default = mkShell {
            # buildInputs =
            #   [
            #     gcc
            #     glib
            #     openssl
            #     pkg-config
            #     cacert
            #     cargo-make
            #     trunk
            #     (rust-bin.selectLatestNightlyWith (
            #       toolchain:
            #         toolchain.default.override {
            #           extensions = [
            #             "rust-src"
            #             "rust-analyzer"
            #           ];
            #           targets = ["wasm32-unknown-unknown"];
            #         }
            #     ))
            #     turso-cli
            #     python3Packages.fonttools
            #     python3Packages.brotli
            #   ]
            #   ++ pkgs.lib.optionals pkgs.stdenv.hostPlatform.isDarwin [
            #     darwin.apple_sdk.frameworks.SystemConfiguration
            #   ];

            # shellHook = ''
            #   export PATH="$HOME/.cargo/bin:$PATH"
            # '';
          };
          packages = {
            server = pkgs.callPackage ./deploy/sorchat-server.nix {src =;};
          };
        }
    );
}
