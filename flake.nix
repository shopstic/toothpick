{
  description = "Toothpick";

  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.gitignore = {
    url = "github:hercules-ci/gitignore.nix";
    # Use the same nixpkgs
    inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, flake-utils, gitignore }:
    flake-utils.lib.eachSystem [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" "aarch64-linux" ]
      (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          toothpick = import ./nix/toothpick.nix
            {
              inherit (gitignore.lib) gitignoreSource;
              sbt = pkgs.sbt.overrideAttrs (_: {
                postPatch = "";
              });
              jdk = pkgs.jdk11;
              inherit (pkgs)
                mkShellNoCC
                lib
                stdenv
                rsync
                makeWrapper
                ;
            };

          linuxPkgs =
            let
              linuxSystem =
                if pkgs.stdenv.isDarwin then
                  (builtins.replaceStrings [ "darwin" ] [ "linux" ] system)
                else
                  system;
            in
            nixpkgs.legacyPackages.${linuxSystem};

          toothpickServerImage = import ./nix/server-image.nix {
            toothpickServer = toothpick.package.server;
            inherit (pkgs)
              stdenv
              lib
              fetchurl
              makeWrapper
              writeText
              ;
            inherit (pkgs.dockerTools)
              pullImage
              buildLayeredImage
              ;
          };
        in
        {
          devShell = toothpick.shell;
          defaultPackage = toothpick.package;
          packages = {
            server = toothpick.package.server;
            serverImage = toothpickServerImage;
            dirty = pkgs.stdenv.mkDerivation {
              name = "dirty-test";
              # src = builtins.filterSource (path: type: builtins.trace path (!pkgs.lib.hasSuffix ".nix" path)) (gitignore.lib.gitignoreSource ./.);
              src = ./nix;
              /* unpackPhase = ''
                for srcFile in $src; do
                cp -r $srcFile $(stripHash $srcFile)
                done
                ''; */
              installPhase = ''
                ls -la .
                echo "!!!!!!!!!!!!!!!!!!! DIRTY HERE"
                mkdir $out
              '';
            };
          };
        }
      );
}
