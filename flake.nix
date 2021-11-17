{
  description = "Toothpick";

  inputs = {
    flakeUtils = {
      url = "github:numtide/flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fdb = {
      url = "github:shopstic/nix-fdb";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flakeUtils.follows = "flakeUtils";
    };
  };

  outputs = { self, nixpkgs, flakeUtils, fdb }:
    flakeUtils.lib.eachSystem [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" "aarch64-linux" ]
      (system:
        let
          nativePkgs = nixpkgs.legacyPackages.${system};
          pkgs = nixpkgs.legacyPackages.${system};
          fdbLib = pkgs.runCommandLocal "fdb-lib" { } ''
            cp -R ${fdb.defaultPackage.${system}}/lib $out
          '';
          toothpickSystem = if system == "aarch64-linux" then "x86_64-linux" else system;
          toothpickPkgs = nixpkgs.legacyPackages.${toothpickSystem};
          toothpick = toothpickPkgs.callPackage ./nix/toothpick.nix
            {
              sbt = toothpickPkgs.sbt.overrideAttrs (_: {
                postPatch = "";
              });
              jdk = toothpickPkgs.jdk11;
            };


          toothpickServerImage = pkgs.callPackage ./nix/server-image.nix
            {
              toothpickServer = toothpick.server;
              inherit fdbLib;
            };

          toothpickRunnerJre = pkgs.callPackage ./nix/runner-jre.nix {
            toothpickRunner = toothpick;
            jre = pkgs.jre_minimal.override { jdk = pkgs.jdk11_headless; };
          };
        in
        rec {
          devShell = pkgs.mkShellNoCC {
            buildInputs = toothpick.buildInputs;
          };
          defaultPackage = toothpick;
          packages = {
            devEnv = devShell.inputDerivation;
            helmShell = pkgs.mkShellNoCC {
              buildInputs = builtins.attrValues {
                inherit (pkgs)
                  kubernetes-helm
                  yq-go
                  ;
              };
            };
            server = toothpick.server;
            dockerServer = toothpick.dockerServer;
            runnerJre = toothpickRunnerJre;
          } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
            serverImage = toothpickServerImage;
          });
        }
      );
}
