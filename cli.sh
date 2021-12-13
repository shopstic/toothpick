#!/usr/bin/env bash
set -euo pipefail

TOOTHPICK_CONFIG="./toothpick-examples/src/test/resources/.toothpick.conf"

test_runner_create_stage() {
  local TEMP_FILE
  TEMP_FILE=$(mktemp)
  trap "rm -Rf ${TEMP_FILE}" EXIT

  1>&2 sbt --client "runner/exportClasspathToFile \"${TEMP_FILE}\""

  local RUNNER_CP
  RUNNER_CP=$(cat "${TEMP_FILE}") || exit $?

  1>&2 sbt --client "examples/Test/exportClasspathToFile \"${TEMP_FILE}\""
  local TEST_CP
  TEST_CP=$(cat "${TEMP_FILE}") || exit $?

  exec java \
    -classpath \
    "${RUNNER_CP}" \
    "-Dconfig.entry=${TOOTHPICK_CONFIG}" \
    dev.toothpick.app.TpConsoleRunnerStageApp \
    -- \
    -classpath \
    "$PWD/.dev-sdks/intellij-scala-runners/intellij-scala-runners.jar:${TEST_CP}" \
    org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner
}

test_runner_run_stage() {
  local STAGE_FILE=${1:?"Stage file is required"}
  local TEMP_FILE
  TEMP_FILE=$(mktemp)
  trap "rm -Rf ${TEMP_FILE}" EXIT

  1>&2 sbt --client "runner/exportClasspathToFile \"${TEMP_FILE}\""
  local RUNNER_CP
  RUNNER_CP=$(cat "${TEMP_FILE}") || exit $?

  exec java \
    -classpath \
    "${RUNNER_CP}" \
    "-Dconfig.entry=${TOOTHPICK_CONFIG}" \
    dev.toothpick.app.TpConsoleRunnerApp \
    "${STAGE_FILE}"
}

"$@"
