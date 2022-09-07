{ lib
, runCommandNoCC
, writeShellScript
, jre
, toothpickRunnerBin ? ""
}:
let
  javaFacade = writeShellScript "toothpick-runner" ''
    set -euo pipefail

    export JAVA_HOME="${jre}"
    export PATH="${lib.makeBinPath [ jre ]}:$PATH"

    TOOTHPICK_RUNNER_BIN=''${TOOTHPICK_RUNNER_BIN:-"${toothpickRunnerBin}"}

    if [[ "$TOOTHPICK_RUNNER_BIN" == "" ]]; then
      >&2 echo "TOOTHPICK_RUNNER_BIN environment variable is not set"
      exit 1
    fi

    DIR_PATH="$(dirname "$(realpath "$0")")"
    TOOTHPICK_REPORT=''${TOOTHPICK_REPORT:-""}
    RUN_ARGS=("-main")

    TOOTHPICK_CONFIG=''${TOOTHPICK_CONFIG:-"$PWD/.toothpick.conf"}
    
    if test -f "''${TOOTHPICK_CONFIG}"; then
      RUN_ARGS+=("-Dconfig.entry=''${TOOTHPICK_CONFIG}")
    else
      >&2 echo "WARNING: No toothpick config file found at $TOOTHPICK_CONFIG"
    fi

    if [[ "''${TOOTHPICK_REPORT}" != "" ]]; then
      RUN_ARGS+=("dev.toothpick.app.TpIntellijReporterApp" "-Dapp.run-id=''${TOOTHPICK_REPORT}")
    else
      RUN_ARGS+=("dev.toothpick.app.TpIntellijRunnerApp" -- "$@")
    fi

    "$TOOTHPICK_RUNNER_BIN" "''${RUN_ARGS[@]}"
  '';
  version = import ./version.nix;
in
runCommandNoCC "toothpick-runner-jre-${version}" { } ''
  mkdir -p $out/bin

  find "${jre}/" -mindepth 1 -maxdepth 1 -not -path "*/bin" -print0 | xargs -0 -I{} ln -s "{}" "$out/"
  find "${jre}/bin/" -mindepth 1 -maxdepth 1 -not -path "*/java" -print0 | xargs -0 -I{} ln -s "{}" "$out/bin/"
  ln -s "${javaFacade}" "$out/bin/java"
''
