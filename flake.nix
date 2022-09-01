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

          jdk = hotPot.packages.${system}.jdk17;
          jre = hotPot.packages.${system}.jre17;

          compileJdk = pkgs.callPackage hotPot.lib.wrapJdk {
            inherit jdk;
            args = pkgs.lib.concatStringsSep " " (jdkArgs ++ [ ''--run "if [[ -f ./.env ]]; then source ./.env; fi"'' ]);
          };
          sbt = pkgs.sbt.override {
            jre = {
              home = compileJdk;
            };
          };

          toothpick-deps = pkgs.callPackage ./nix/deps.nix {
            inherit sbt jdk;
          };

          toothpick = pkgs.callPackage ./nix/toothpick.nix {
            inherit sbt jdk toothpick-deps;
          };

          toothpick-server-image = pkgs.callPackage ./nix/server-image.nix
            {
              inherit toothpick;
              inherit fdbLib;
              inherit (hotPotPkgs)
                nix2container;
              jre = jre;
            };

          toothpick-runner-jre = pkgs.callPackage ./nix/runner-jre.nix {
            toothpickRunnerBin = "${toothpick}/bin/toothpick-runner";
            jre = jre;
          };

          toothpick-runner-jre-dev = pkgs.callPackage ./nix/runner-jre.nix {
            jre = jre;
          };

          jdkPrefix = "toothpick-";
          update-intellij = pkgs.writeShellScript "update-intellij" ''
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

          intellij-scala-runners = pkgs.callPackage ./nix/intellij-scala-runners.nix { };
          dev-sdks = pkgs.linkFarm "dev-sdks" [
            { name = "compile-jdk"; path = compileJdk; }
            { name = "runner-dev-jdk"; path = toothpick-runner-jre-dev; }
            { name = "update-intellij"; path = update-intellij; }
            { name = "intellij-scala-runners"; path = intellij-scala-runners; }
          ];
          vscode-settings = pkgs.writeTextFile {
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
              ln -Tfs ${dev-sdks} ./.dev-sdks
              cat ${vscode-settings} > ./.vscode/settings.json
            '';
            buildInputs = toothpick.buildInputs ++ builtins.attrValues {
              inherit (pkgs)
                # skopeo
                yq-go
                awscli2
                kubernetes-helm
                ;
              inherit (hotPotPkgs)
                manifest-tool
                skopeo-nix2container
                ;
            };
          };
        in
        {
          inherit devShell;
          defaultPackage = toothpick;
          packages = {
            inherit intellij-scala-runners;
            devEnv = devShell.inputDerivation;
            deps = toothpick-deps;
            server = toothpick.server;
            runner-jre = toothpick-runner-jre;
          } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
            server-image = toothpick-server-image;
          });
        }
      );
}
