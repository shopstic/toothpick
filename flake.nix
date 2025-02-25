{
  description = "Toothpick";

  inputs = {
    hotPot.url = "github:shopstic/nix-hot-pot";
    nixpkgs.follows = "hotPot/nixpkgs";
    flakeUtils.follows = "hotPot/flakeUtils";
    fdbPkg.follows = "hotPot/fdbPkg";
    nix2containerPkg.follows = "hotPot/nix2containerPkg";
    helmet.url = "github:shopstic/helmet/1.26.7";
    jetski.url = "github:shopstic/jetski/1.7.10";
  };

  outputs = { self, nixpkgs, flakeUtils, hotPot, fdbPkg, nix2containerPkg, helmet, jetski }:
    flakeUtils.lib.eachSystem [ "aarch64-darwin" "x86_64-linux" "aarch64-linux" ]
      (system:
        let
          pkgs = import nixpkgs { inherit system; };
          hotPotPkgs = hotPot.packages.${system};
          hotPotLib = hotPot.lib.${system};
          nix2container = nix2containerPkg.packages.${system}.nix2container;
          nonRootShadowSetup = hotPotLib.nonRootShadowSetup;
          fdbLib = fdbPkg.packages.${system}.fdb_7.lib;
          jdkArgs = [
            "--set DYLD_LIBRARY_PATH ${fdbLib}"
            "--set LD_LIBRARY_PATH ${fdbLib}"
            "--set JDK_JAVA_OPTIONS -DFDB_LIBRARY_PATH_FDB_JAVA=${fdbLib}/libfdb_java.${if pkgs.stdenv.isDarwin then "jnilib" else "so"}"
          ];
          jreArgs = [
            ''--run "if [[ -f ./.env ]]; then source ./.env; elif [[ -f ../.env ]]; then source ../.env; fi"''
          ];
          jdk = pkgs.callPackage hotPotLib.wrapJdk {
            jdk = hotPot.packages.${system}.jdk17;
            args = pkgs.lib.concatStringsSep " " (jdkArgs ++ jreArgs);
          };
          jre = pkgs.callPackage hotPotLib.wrapJdk {
            jdk = hotPot.packages.${system}.jre17;
            args = pkgs.lib.concatStringsSep " " jreArgs;
          };

          compileJdk = jdk;
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
              inherit nonRootShadowSetup toothpick fdbLib nix2container jre;
              inherit (hotPotPkgs)
                grpc-health-probe;
            };

          toothpick-runner-jre = pkgs.callPackage ./nix/runner-jre.nix {
            inherit jre;
            toothpickRunnerBin = "${toothpick}/bin/toothpick-runner";
          };

          toothpick-runner-jre-dev = pkgs.callPackage ./nix/runner-jre.nix {
            inherit jre;
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
          denoBin = hotPotPkgs.deno;
          vscode-settings = pkgs.writeTextFile {
            name = "vscode-settings.json";
            text = builtins.toJSON {
              "deno.enable" = true;
              "deno.lint" = true;
              "deno.path" = denoBin + "/bin/deno";
              "[typescript]" = {
                "editor.defaultFormatter" = "denoland.vscode-deno";
                "editor.formatOnSave" = true;
              };
              "files.watcherExclude" = {
                "**/target" = true;
              };
              "metals.sbtScript" = sbt + "/bin/sbt";
              "nix.enableLanguageServer" = true;
              "nix.formatterPath" = pkgs.nixpkgs-fmt + "/bin/nixpkgs-fmt";
              "nix.serverSettings" = {
                "nil" = {
                  "formatting" = {
                    "command" = [ "nixpkgs-fmt" ];
                  };
                };
              };
              "nix.serverPath" = pkgs.nil + "/bin/nil";
            };
          };
          devShell = pkgs.mkShellNoCC {
            shellHook = ''
              ln -Tfs ${dev-sdks} ./.dev-sdks
              mkdir -p ./.vscode
              cat ${vscode-settings} > ./.vscode/settings.json
              if [[ -f ./.env ]]; then
                source ./.env
              fi
            '';
            buildInputs = toothpick.buildInputs ++ [
              denoBin
              helmet.defaultPackage.${system}
              jetski.defaultPackage.${system}
            ] ++ builtins.attrValues {
              inherit (pkgs)
                # skopeo
                yq-go
                awscli2
                kubernetes-helm
                ps
                amazon-ecr-credential-helper
                grpcurl
                ;
              inherit (hotPotPkgs)
                manifest-tool
                skopeo-nix2container
                deno
                ;
            };
          };
        in
        {
          inherit devShell;
          defaultPackage = toothpick;
          packages = {
            inherit intellij-scala-runners toothpick;
            devEnv = devShell.inputDerivation;
            deps = toothpick-deps;
            runner-jre = toothpick-runner-jre;
          } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
            server-image = toothpick-server-image;
          });
        }
      );
}
