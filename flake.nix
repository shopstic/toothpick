{
  description = "Toothpick";

  inputs = {
    hotPot.url = "github:shopstic/nix-hot-pot";
    nixpkgs.follows = "hotPot/nixpkgs";
    flakeUtils.follows = "hotPot/flakeUtils";
    fdb.url = "github:shopstic/nix-fdb/7.1.11";
  };

  outputs = { self, nixpkgs, flakeUtils, fdb, hotPot }:
    flakeUtils.lib.eachSystem [ "aarch64-darwin" "x86_64-linux" "aarch64-linux" ]
      (system:
        let
          pkgs = import nixpkgs { inherit system; };
          hotPotPkgs = hotPot.packages.${system};

          fdbLib = fdb.packages.${system}.fdb_7.lib;
          jdkArgs = [
            "--set DYLD_LIBRARY_PATH ${fdbLib}"
            "--set LD_LIBRARY_PATH ${fdbLib}"
            "--set JDK_JAVA_OPTIONS -DFDB_LIBRARY_PATH_FDB_JAVA=${fdbLib}/libfdb_java.${if pkgs.stdenv.isDarwin then "jnilib" else "so"}"
          ];

          # sbtn doesn't yet support aarch64-linux
          toothpickSystem = if system == "aarch64-linux" then "x86_64-linux" else system;
          toothpickPkgs = import nixpkgs { system = toothpickSystem; };

          jdk = hotPot.packages.${toothpickSystem}.jdk17;
          jre = hotPot.packages.${toothpickSystem}.jre17;

          compileJdk = toothpickPkgs.callPackage hotPot.lib.wrapJdk {
            inherit jdk;
            args = toothpickPkgs.lib.concatStringsSep " " (jdkArgs ++ [ ''--run "if [[ -f ./.env ]]; then source ./.env; fi"'' ]);
          };
          sbt = toothpickPkgs.sbt.override {
            jre = {
              home = compileJdk;
            };
          };

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
              jre = jre;
            };

          toothpickRunnerJre = pkgs.callPackage ./nix/runner-jre.nix {
            toothpickRunnerBin = "${toothpick}/bin/toothpick-runner";
            jre = jre;
          };

          toothpickRunnerJreDev = pkgs.callPackage ./nix/runner-jre.nix {
            jre = jre;
          };

          jdkPrefix = "toothpick-";
          updateIntellij = pkgs.writeShellScript "update-intellij" ''
            set -euo pipefail

            THIS_PATH=$(realpath .)
            SDK_NAMES=(compile runner-dev)

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

          intellijScalaRunners = pkgs.callPackage ./nix/intellij-scala-runners.nix { };
          devSdks = pkgs.linkFarm "dev-sdks" [
            { name = "compile-jdk"; path = compileJdk; }
            { name = "runner-dev-jdk"; path = toothpickRunnerJreDev; }
            { name = "update-intellij"; path = updateIntellij; }
            { name = "intellij-scala-runners"; path = intellijScalaRunners; }
          ];
          vscodeSettings = pkgs.writeTextFile {
            name = "vscode-settings.json";
            text = builtins.toJSON {
              "files.watcherExclude" = {
                "**/target" = true;
              };
              "metals.sbtScript" = sbt + "/bin/sbt";
              "nix.enableLanguageServer" = true;
              "nix.formatterPath" = pkgs.nixpkgs-fmt + "/bin/nixpkgs-fmt";
              "nix.serverPath" = pkgs.rnix-lsp + "/bin/rnix-lsp";
            };
          };
          devShell = pkgs.mkShellNoCC {
            shellHook = ''
              ln -Tfs ${devSdks} ./.dev-sdks
              cat ${vscodeSettings} > ./.vscode/settings.json
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
            inherit intellijScalaRunners;
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
