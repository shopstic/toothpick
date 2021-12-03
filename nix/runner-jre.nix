{ lib
, runCommandNoCC
, writeShellScript
, toothpickRunner
, jre
}:
let
  javaFacade = writeShellScript "toothpick-runner" ''
    set -euo pipefail
    export PATH="${lib.makeBinPath [ jre ]}:$PATH"

    DIR_PATH="$(dirname "$(realpath "$0")")"
    TOOTHPICK_REPORT=''${TOOTHPICK_REPORT:-""}
    RUN_ARGS=("-main")

    CONFIG_ENTRY_FILE="$PWD/.toothpick.conf"

    if test -f "''${CONFIG_ENTRY_FILE}"; then
      RUN_ARGS+=("-Dconfig.entry=''${CONFIG_ENTRY_FILE}")
    fi

    if [[ "''${TOOTHPICK_REPORT}" != "" ]]; then
      RUN_ARGS+=("dev.toothpick.app.TpIntellijReporterApp" "-Dapp.run-id=''${TOOTHPICK_REPORT}")
    else
      RUN_ARGS+=("dev.toothpick.app.TpIntellijRunnerApp" -- "$@")
    fi

    "${toothpickRunner}/bin/toothpick-runner" "''${RUN_ARGS[@]}"
  '';
  version = import ./version.nix;
in
runCommandNoCC "toothpick-runner-jre-${version}" { } ''
  mkdir -p $out/jre/bin

  find "${jre}/" -mindepth 1 -maxdepth 1 -not -path "*/bin" -print0 | xargs -0 -I{} ln -s "{}" "$out/jre/"
  find "${jre}/bin/" -mindepth 1 -maxdepth 1 -not -path "*/java" -print0 | xargs -0 -I{} ln -s "{}" "$out/jre/bin/"
  ln -s "${javaFacade}" "$out/jre/bin/java"
''