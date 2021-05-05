#!/usr/bin/env bash
set -euo pipefail

DIR_PATH="$(dirname "$(realpath "$0")")"
export TOOTHPICK_BIN="${DIR_PATH}/toothpick-runner/target/universal/stage/bin/toothpick-runner"

"${DIR_PATH}/scripts/runner/runner.sh" "$@"
