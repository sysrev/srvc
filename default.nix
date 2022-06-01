{ sources ? import ./nix/sources.nix
, pkgs ? import sources.nixpkgs {}
}:
with pkgs;
mkShell {
  buildInputs = [
    babashka
    sqlite
  ];
}