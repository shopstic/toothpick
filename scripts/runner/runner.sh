#!/usr/bin/env bash
set -euo pipefail

DIR_PATH="$(dirname "$(realpath "$0")")"
TOOTHPICK_BIN=${TOOTHPICK_BIN:-"${DIR_PATH}/toothpick-runner"}
TOOTHPICK_REPORT=${TOOTHPICK_REPORT:-""}
RUN_ARGS=("-main")

CONFIG_ENTRY_FILE="$PWD/.toothpick.conf"

if test -f "${CONFIG_ENTRY_FILE}"; then
  RUN_ARGS+=("-Dconfig.entry=${CONFIG_ENTRY_FILE}")
fi

if [[ "${TOOTHPICK_REPORT}" != "" ]]; then
  RUN_ARGS+=("dev.toothpick.app.TpIntellijReporterApp" "-Dapp.run-id=${TOOTHPICK_REPORT}")
else
  RUN_ARGS+=("dev.toothpick.app.TpIntellijRunnerApp" -- "$@")
fi

"${TOOTHPICK_BIN}" "${RUN_ARGS[@]}"
