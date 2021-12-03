#!/usr/bin/env bash
set -euo pipefail

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
