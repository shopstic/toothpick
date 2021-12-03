{
  description = "Toothpick";

  inputs = {
    hotPot.url = "github:shopstic/nix-hot-pot";
    nixpkgs.follows = "hotPot/nixpkgs";
    flakeUtils.follows = "hotPot/flakeUtils";
    fdb.url = "github:shopstic/nix-fdb";
  };

  outputs = { self, nixpkgs, flakeUtils, fdb, hotPot }:
    flakeUtils.lib.eachSystem [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" "aarch64-linux" ]
      (system:
        let
          pkgs = import nixpkgs { inherit system; };
          hotPotPkgs = hotPot.packages.${system};

          fdbLib = pkgs.runCommandLocal "fdb-lib" { } ''
            cp -R ${fdb.defaultPackage.${system}}/lib $out
          '';
          toothpickSystem = if system == "aarch64-linux" then "x86_64-linux" else system;
          toothpickPkgs = import nixpkgs { system = toothpickSystem; };

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
            jre = pkgs.jdk11_headless;
          };

          devShell = pkgs.mkShellNoCC {
            buildInputs = toothpick.buildInputs ++ builtins.attrValues {
              inherit (pkgs)
                skopeo
                yq
                awscli2
                kubernetes-helm
                ;
              inherit (hotPotPkgs)
                manifest-tool
                ;
            };
          };
        in
        {
          inherit devShell;
          defaultPackage = toothpick;
          packages = {
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
