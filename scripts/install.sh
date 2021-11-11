#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME=${JAVA_HOME:?"JAVA_HOME env variable is required"}

TOOTHPICK_VERSION=${1:-"latest"}
DESTINATION=${2:-"${HOME}/.toothpick"}
TEMP_DIR=$(mktemp -d)
trap "rm -Rf ${TEMP_DIR}" EXIT

RUNNER_PATH="${DESTINATION}/runner"
mkdir -p "${RUNNER_PATH}"

docker pull shopstic/toothpick-runner-bin:"${TOOTHPICK_VERSION}"
docker save shopstic/toothpick-runner-bin:"${TOOTHPICK_VERSION}" | tar -x -C "${TEMP_DIR}" -
(cd "${TEMP_DIR}" && jq '.[0].Layers | .[]' -r ./manifest.json | xargs -I{} tar -x -C "${RUNNER_PATH}" -f {})

JDK_FACADE_PATH="${DESTINATION}/jdk"

if test -e "${JDK_FACADE_PATH}"; then
  rm -Rf "${JDK_FACADE_PATH}"
fi

mkdir -p "${JDK_FACADE_PATH}/bin"

find "${JAVA_HOME}" -mindepth 1 -maxdepth 1 -not -path "*/bin" -print0 | xargs -0 -I{} ln -s "{}" "${JDK_FACADE_PATH}/"
find "${JAVA_HOME}/bin" -mindepth 1 -maxdepth 1 -not -path "*/java" -print0 | xargs -0 -I{} ln -s "{}" "${JDK_FACADE_PATH}/bin/"
ln -s "${RUNNER_PATH}/bin/runner.sh" "${JDK_FACADE_PATH}/bin/java"

echo "Successfully installed Toothpick version '${TOOTHPICK_VERSION}' to ${DESTINATION}"