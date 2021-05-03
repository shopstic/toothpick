#!/usr/bin/env bash
set -euo pipefail

DIR_PATH="$(dirname "$(realpath "$0")")"
RUN_ARGS=("-main"  "dev.toothpick.app.TpIntellijRunnerApp")

CONFIG_ENTRY_FILE="$PWD/.toothpick.conf"

if test -f "${CONFIG_ENTRY_FILE}"; then
  RUN_ARGS+=("-Dconfig.entry=${CONFIG_ENTRY_FILE}")
fi

"${DIR_PATH}/toothpick-runner/target/universal/stage/bin/toothpick-runner" "${RUN_ARGS[@]}" -- "$@"
