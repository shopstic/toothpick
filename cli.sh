#!/usr/bin/env bash
set -euo pipefail

push_helm_chart() {
  export HELM_CHART_VERSION=${1:?"Helm chart version is required"}
  export HELM_APP_VERSION=${2:?"Helm chart app version is required"}
  export HELM_CHART_REF=${3:?"Helm chart ref is required"}
  export HELM_EXPERIMENTAL_OCI=1
  # export HELM_REGISTRY_CONFIG=/home/runner/.docker/config.json

  local OUT
  OUT=$(mktemp -d)
  trap "rm -Rf ${OUT}" EXIT

  cp -R ./charts/toothpick "${OUT}/"

  yq e '.version = env(HELM_CHART_VERSION)' -i "${OUT}/toothpick/Chart.yaml"
  yq e '.appVersion = env(HELM_APP_VERSION)' -i "${OUT}/toothpick/Chart.yaml"
  yq e '.appVersion = env(HELM_APP_VERSION)' -i "${OUT}/toothpick/charts/toothpick-master/Chart.yaml"
  yq e '.appVersion = env(HELM_APP_VERSION)' -i "${OUT}/toothpick/charts/toothpick-worker/Chart.yaml"
  yq e '.toothpick-master.image.tag = env(HELM_APP_VERSION)' -i "${OUT}/toothpick/values.yaml"
  yq e '.toothpick-worker.image.tag = env(HELM_APP_VERSION)' -i "${OUT}/toothpick/values.yaml"

  helm package --app-version "${HELM_APP_VERSION}" "${OUT}/toothpick" -d "${OUT}/packaged"
  helm push "${OUT}/packaged/toothpick-${HELM_CHART_VERSION}.tgz" "${HELM_CHART_REF}"
}

ci_build_in_shell() {
  local SHELL_IMAGE=${SHELL_IMAGE:?"SHELL_IMAGE env variable is required"}
  local SERVER_BASE_IMAGE=${SERVER_BASE_IMAGE:?"SERVER_BASE_IMAGE env variable is required"}
  local GITHUB_TAG=${GITHUB_TAG:?"GITHUB_TAG env variable is required"}
  local GITHUB_TOKEN=${GITHUB_TOKEN:?"GITHUB_TOKEN env variable is required"}
  local GITHUB_WORKSPACE=${GITHUB_WORKSPACE:?"GITHUB_WORKSPACE env variable is required"}

  cat <<EOF | docker run \
    --workdir /repo \
    -i \
    --rm \
    -e SBT_OPTS="-server -XX:+UseG1GC -Xms6g -Xmx6g -Xss6m" \
    -e "GITHUB_TAG=${GITHUB_TAG}" \
    -e "GITHUB_TOKEN=${GITHUB_TOKEN}" \
    -e "SERVER_BASE_IMAGE=${SERVER_BASE_IMAGE}" \
    -v "${GITHUB_WORKSPACE}:/repo" \
    -v "${HOME}/.cache:/home/runner/.cache" \
    -v "${HOME}/.sbt:/home/runner/.sbt" \
    "${SHELL_IMAGE}" \
    bash -euo pipefail

./cli.sh ci_build

if [[ "${GITHUB_TAG}" != "" ]]; then
  ./cli.sh ci_server_stage
  ./cli.sh ci_prepare_runner_stage
fi

EOF

}

ci_build() {
  sbt --client 'set ThisBuild / scalacOptions += "-Werror"'
  sbt --client show ThisBuild / scalacOptions | tail -n4
  sbt --client cq
  sbt --client compile
  sbt --client Test / compile
  sbt --client printWarnings
  sbt --client Test / printWarnings
  sbt --client test
}

ci_server_stage() {
  local SERVER_BASE_IMAGE=${SERVER_BASE_IMAGE:?"SERVER_BASE_IMAGE env variable is required"}

  sbt --client runner / stage
  sbt --client "set server / dockerBaseImage := \"${SERVER_BASE_IMAGE}\""
  sbt --client 'server / Docker / stage'
}

ci_prepare_runner_stage() {
  local STAGE_PATH="./toothpick-runner/target/universal/stage"

  if test -e "${STAGE_PATH}"; then
    rm -Rf "${STAGE_PATH}"
  fi

  sbt --client 'runner / stage'

  mkdir "${STAGE_PATH}/app-lib"
  mv "${STAGE_PATH}/lib/dev.toothpick."* "${STAGE_PATH}/app-lib/"
  cp ./scripts/runner/runner.sh "${STAGE_PATH}/bin/runner.sh"
  cp ./scripts/runner/Dockerfile "${STAGE_PATH}/Dockerfile"
  chmod +x "${STAGE_PATH}/bin/"*
}

proxy_jdk() {
  local FROM_JDK_HOME=${1:?"FROM_JDK_HOME is required"}
  local TO_JDK_HOME=${2:?"TO_JDK_HOME is required"}
  local JAVA_BIN_REPLACEMENT=${3:?"JAVA_BIN_REPLACEMENT is required"}

  FROM_JDK_HOME=$(realpath "${FROM_JDK_HOME}")
  TO_JDK_HOME=$(realpath "${TO_JDK_HOME}")
  JAVA_BIN_REPLACEMENT=$(realpath "${JAVA_BIN_REPLACEMENT}")

  rm -Rf "${TO_JDK_HOME}"
  mkdir -p "${TO_JDK_HOME}" "${TO_JDK_HOME}/bin"
  find "${FROM_JDK_HOME}" -mindepth 1 -maxdepth 1 -not -path "*/bin" -print0 | xargs -0 -I{} ln -s "{}" "${TO_JDK_HOME}/"
  find "${FROM_JDK_HOME}/bin" -mindepth 1 -maxdepth 1 -not -path "*/java" -print0 | xargs -0 -I{} ln -s "{}" "${TO_JDK_HOME}/bin/"
  ln -s "${JAVA_BIN_REPLACEMENT}" "${TO_JDK_HOME}/bin/java"
}

"$@"
