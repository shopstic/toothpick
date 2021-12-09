#!/usr/bin/env bash
set -euo pipefail

get_release_version() {
  local VERSION_SBT
  VERSION_SBT=$(cat ./version.sbt) || exit $?

  local REGEX='ThisBuild / version := "([^"]+)"'

  if [[ $VERSION_SBT =~ $REGEX ]]; then
    local CURRENT_VERSION="${BASH_REMATCH[1]}"

    if [[ $CURRENT_VERSION =~ -SNAPSHOT$ ]]; then
      CURRENT_VERSION=${CURRENT_VERSION/-SNAPSHOT/}
      local GIT_SHA
      local GIT_COMMIT_COUNT
      local SHORTENED_COMMIT_SHA

      GIT_SHA=$(git rev-parse HEAD) || exit $?
      SHORTENED_COMMIT_SHA=$(echo "${GIT_SHA}" | cut -c 1-7) || exit $?
      GIT_COMMIT_COUNT=$(git rev-list --count HEAD) || exit $?

      echo "${CURRENT_VERSION}+${GIT_COMMIT_COUNT}-${SHORTENED_COMMIT_SHA}"
    else
      echo "${CURRENT_VERSION}"
    fi
  else
    >&2 echo "Cannot determine version from version.sbt"
    exit 1
  fi
}

push_helm_chart() {
  export HELM_CHART_VERSION=${1:?"Helm chart version is required"}
  export HELM_APP_VERSION=${2:?"Helm chart app version is required"}
  export HELM_CHART_REF=${3:?"Helm chart ref is required"}
  export HELM_EXPERIMENTAL_OCI=1

  local OUT
  OUT=$(mktemp -d)
  # trap "rm -Rf $cd {OUT}" EXIT

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

"$@"
