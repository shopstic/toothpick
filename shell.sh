#!/usr/bin/env bash
set -euo pipefail

CURRENT_UID=$(id -u)
CURRENT_GID=$(id -g)

BUILD_ARG=("--build-arg" "RUNNER_UID=${CURRENT_UID}" "--build-arg" "RUNNER_GID=${CURRENT_GID}")
docker build "${BUILD_ARG[@]}" ./images/shell
IMAGE_ID=$(docker build -q "${BUILD_ARG[@]}" ./images/shell | head -n1)

docker run \
  -it \
  --rm \
  --privileged \
  --hostname=toothpick-shell \
  -e GITHUB_TOKEN \
  -v "${HOME}/Library/Caches/Coursier:/home/runner/.cache/coursier" \
  -v "${HOME}/.sbt:/home/runner/.sbt" \
  -v "${HOME}/Library/Caches/com.thesamet.scalapb.protocbridge.protocbridge:/home/runner/.cache/protocbridge" \
  -v "${PWD}:${PWD}" \
  -w "${PWD}" \
  "${IMAGE_ID}" \
  bash -l
