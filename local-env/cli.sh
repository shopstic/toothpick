#!/usr/bin/env bash
set -euo pipefail

create_temp_dir() {
  local TEMP_DIR

  # Prefer to store compiled YAML files temporarily in memory only,
  # since secrets will be decrypted in the YAML files prior to installation
  if test -d /dev/shm; then
    TEMP_DIR="/dev/shm/$(mktemp -u XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX)"
    mkdir -p "${TEMP_DIR}"
  else
    TEMP_DIR=$(mktemp -d)
  fi

  echo "${TEMP_DIR}"
}

install() {
  local BUNDLE_MODULE=${1:?"Path to a bundle module is required"}

  local TEMP_DIR
  TEMP_DIR=$(create_temp_dir)
  trap "rm -Rf ${TEMP_DIR}" EXIT

  local BUNDLE_META
  BUNDLE_META=$(helmet ensure-whitelisted --path "${BUNDLE_MODULE}")

  local RELEASE_ID
  local RELEASE_NAMESPACE
  RELEASE_ID=$(yq e .releaseId <(echo "${BUNDLE_META}"))
  RELEASE_NAMESPACE=$(yq e .releaseNamespace <(echo "${BUNDLE_META}"))
  helmet compile --version="1.0.0" --source="${BUNDLE_MODULE}" --destination="${TEMP_DIR}"
  helmet install --name="${RELEASE_ID}" --namespace="${RELEASE_NAMESPACE}" --source="${TEMP_DIR}" --debug "${@:2}"
}

uninstall() {
  local BUNDLE_MODULE=${1:?"Path to a bundle module is required"}
  local NO_CONFIRM=${2:-""}

  if [[ "${NO_CONFIRM}" != "--no-confirm" ]]; then
    read -p "Going to uninstall '${BUNDLE_MODULE}', are you sure? (y/n) " -n 1 -r
    echo
  fi
  
  if [[ "${NO_CONFIRM}" == "--no-confirm" || $REPLY =~ ^[Yy]$ ]]
  then
    local BUNDLE_META
    BUNDLE_META=$(helmet ensure-whitelisted --path "${BUNDLE_MODULE}")

    local RELEASE_ID
    local RELEASE_NAMESPACE
    RELEASE_ID=$(yq e .releaseId <(echo "${BUNDLE_META}"))
    RELEASE_NAMESPACE=$(yq e .releaseNamespace <(echo "${BUNDLE_META}"))
    helmet uninstall --name "${RELEASE_ID}" --namespace "${RELEASE_NAMESPACE}"
  else
    exit 1
  fi
}

update_local_fdb_connection_string() {
  kubectl get -n dev configmap dev-fdb-connection-string -o=jsonpath='{.data.connectionString}' | tee ./env/private/cluster.file
  echo
}

"$@"