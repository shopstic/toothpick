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
          fdbLibSystem = if system == "aarch64-darwin" then "x86_64-darwin" else system;
          toothpickSystem = if system == "aarch64-linux" then "x86_64-linux" else system;
          toothpickPkgs = import nixpkgs { system = toothpickSystem; };

          jdkArgs = [
            "--set DYLD_LIBRARY_PATH ${fdb.defaultPackage.${fdbLibSystem}}/lib"
            "--set LD_LIBRARY_PATH ${fdb.defaultPackage.${fdbLibSystem}}/lib"
          ];

          runJdk = pkgs.callPackage hotPot.lib.wrapJdk {
            jdk = (import nixpkgs { system = fdbLibSystem; }).jdk11;
            args = pkgs.lib.concatStringsSep " " jdkArgs;
          };
          compileJdk = pkgs.callPackage hotPot.lib.wrapJdk {
            jdk = pkgs.jdk11;
            args = pkgs.lib.concatStringsSep " " (jdkArgs ++ [ ''--run "if [[ -f ./.env ]]; then source ./.env; fi"'' ]);
          };
          sbt = pkgs.sbt.override {
            jre = {
              home = compileJdk;
            };
          };

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
            toothpickRunnerBin = "${toothpick}/bin/toothpick-runner";
            jre = pkgs.jdk11_headless;
          };

          toothpickRunnerJreDev = pkgs.callPackage ./nix/runner-jre.nix {
            jre = pkgs.jdk11_headless;
          };

          jdkPrefix = "toothpick-";
          updateIntellij = pkgs.writeShellScript "update-intellij" ''
            set -euo pipefail

            THIS_PATH=$(realpath .)
            SDK_NAMES=(compile run runner-dev)

            for SDK_NAME in "''${SDK_NAMES[@]}"
            do
              find ~/Library/Application\ Support/JetBrains/ -mindepth 1 -maxdepth 1 -name "IntelliJIdea*" -type d | \
                xargs -I%%%% bash -c "echo \"Adding ${jdkPrefix}''${SDK_NAME} to %%%%/options/jdk.table.xml\" && ${hotPotPkgs.intellij-helper}/bin/intellij-helper \
                update-jdk-table-xml \
                --name ${jdkPrefix}''${SDK_NAME} \
                --jdkPath \"''${THIS_PATH}\"/.dev-sdks/\"''${SDK_NAME}\"-jdk \
                --jdkTableXmlPath \"%%%%/options/jdk.table.xml\" \
                --inPlace=true"
            done
          '';
          devSdks = pkgs.linkFarm "dev-sdks" [
            { name = "compile-jdk"; path = compileJdk; }
            { name = "run-jdk"; path = runJdk; }
            { name = "runner-dev-jdk"; path = toothpickRunnerJreDev; }
            { name = "update-intellij"; path = updateIntellij; }
          ];
          devShell = pkgs.mkShellNoCC {
            shellHook = ''
              ln -Tfs ${devSdks} ./.dev-sdks
            '';
            buildInputs = toothpick.buildInputs ++ builtins.attrValues {
              inherit (pkgs)
                skopeo
                yq-go
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
