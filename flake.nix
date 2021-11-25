{
  description = "Toothpick";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/29830319abf5a925921885974faae5509312b940";
    flakeUtils = {
      url = "github:numtide/flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fdb = {
      url = "github:shopstic/nix-fdb";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flakeUtils.follows = "flakeUtils";
    };
    hotPot = {
      url = "github:shopstic/nix-hot-pot";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flakeUtils.follows = "flakeUtils";
    };
  };

  outputs = { self, nixpkgs, flakeUtils, fdb, hotPot }:
    flakeUtils.lib.eachSystem [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" "aarch64-linux" ]
      (system:
        let
          pkgs = import nixpkgs { inherit system; };

          fdbLib = pkgs.runCommandLocal "fdb-lib" { } ''
            cp -R ${fdb.defaultPackage.${system}}/lib $out
          '';
          toothpickSystem = if system == "aarch64-linux" then "x86_64-linux" else system;
          toothpickPkgs = nixpkgs.legacyPackages.${toothpickSystem};

          sbt = toothpickPkgs.sbt.overrideAttrs (_: {
            postPatch = "";
          });
          jdk = toothpickPkgs.jdk11_headless;

          toothpickDeps = toothpickPkgs.callPackage ./nix/deps.nix {
            inherit sbt jdk;
          };
          
          toothpick = toothpickPkgs.callPackage ./nix/toothpick.nix {
            inherit sbt jdk toothpickDeps;
          };

          toothpickServerImage = pkgs.callPackage ./nix/server-image.nix
            {
              toothpickServer = toothpick.server;
              inherit fdbLib;
              buildahBuild = pkgs.callPackage hotPot.lib.buildahBuild;
            };

          toothpickRunnerJre = pkgs.callPackage ./nix/runner-jre.nix {
            toothpickRunner = toothpick;
            jre = pkgs.jre_minimal.override { jdk = pkgs.jdk11_headless; };
          };

          skopeoShell = pkgs.mkShellNoCC {
            buildInputs = builtins.attrValues {
              inherit (pkgs)
                skopeo
                ;
            };
          };

          helmShell = pkgs.mkShellNoCC {
            buildInputs = builtins.attrValues {
              inherit (pkgs)
                kubernetes-helm
                yq-go
                ;
            };
          };

          devShell = pkgs.mkShellNoCC {
            buildInputs = toothpick.buildInputs ++ skopeoShell.buildInputs ++ helmShell.buildInputs;
          };
        in
        {
          inherit devShell;
          defaultPackage = toothpick;
          devShells = {
            inherit helmShell skopeoShell;
          };
          packages = {
            sbtDebug = pkgs.dockerTools.buildImage {
              name = "sbt-debug";
              contents = [
                sbt jdk
              ];
            };
            deps = toothpickDeps;
            hello = pkgs.hello;
            devEnv = devShell.inputDerivation;
            server = toothpick.server;
            dockerServer = toothpick.dockerServer;
            runnerJre = toothpickRunnerJre;
          } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
            serverImage = toothpickServerImage;
          });
        }
      );
}
