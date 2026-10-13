{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    openjdk25
    git
  ];

  shellHook = ''
    echo "=== minecraft-system-block development shell ==="
    echo "Java version: $(java --version | head -n 1)"
    echo "Run ./build.sh to build the mod jar and optionally install to ~/.minecraft/mods"
  '';
}
