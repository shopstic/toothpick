#!/usr/bin/env bash
set -euo pipefail

docker build ./images/shell
IMAGE_ID=$(docker build -q ./images/shell)

docker run \
  -it \
  --rm \
  --privileged \
  --hostname=toothpick-shell \
  -e GITHUB_TOKEN \
  -v "${HOME}/Library/Caches/Coursier:/root/.cache/coursier" \
  -v "${HOME}/Library/Caches/com.thesamet.scalapb.protocbridge.protocbridge:/root/.cache/protocbridge" \
  -v "${PWD}:${PWD}" \
  -w "${PWD}" \
  "${IMAGE_ID}" \
  bash -l
