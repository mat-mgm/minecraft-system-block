{
  description = "minecraft-system-block development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            openjdk25
            git
          ];

          shellHook = ''
            echo "=== minecraft-system-block Nix Flake Shell ==="
            echo "Java: $(java --version | head -n 1)"
            echo "Build with: ./build.sh"
          '';
        };
      });
}
