#
# Copyright 2026 Kamran Zafar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Shared by eks-, gke- and aks-real-cloud.sh: pushes the portable orders images, deploys them with the Helm chart and
# runs the end-to-end suite against each. Sourced; the caller sets ROOT, RUN and WORK, defines log, and exports
# KUBECONFIG for the run's cluster.
#
# The images are built once, by build-portable-orders-images.sh, and every cluster runs those same images: a push
# keeps an image's digest, and each run records the digest its pods run.
#
# The Spring Boot 4 image is deployed and tested first, then the Boot 3.5 image replaces it in the same release and
# the suite runs again: the same chart and values, the same cloud resources.

NAMESPACE=portable-orders
CHART="$ROOT/halyx-samples/portable-orders/deploy/helm/portable-orders"
IMAGE_VERSION=1.0.0-SNAPSHOT
LOCAL_PORT="${LOCAL_PORT:-18080}"
REPORTS="$ROOT/cloud-testing/reports"

# The two images, as build-portable-orders-images.sh built them. A run never builds them itself: concurrent runs would
# each build their own, and the clusters would no longer run one image. REBUILD_IMAGES=1 builds them first, for a run
# on its own.
build_images() {
  if [[ "${REBUILD_IMAGES:-0}" == 1 ]]; then
    "$ROOT/cloud-testing/scripts/build-portable-orders-images.sh" || return 1
  fi
  local image
  for image in portable-orders portable-orders-boot3; do
    if ! docker image inspect "halyx/$image:$IMAGE_VERSION" >/dev/null 2>&1; then
      echo "  halyx/$image:$IMAGE_VERSION isn't built: run cloud-testing/scripts/build-portable-orders-images.sh first"
      return 1
    fi
    echo "  halyx/$image:$IMAGE_VERSION $(docker image inspect -f '{{.Id}}' "halyx/$image:$IMAGE_VERSION")"
  done
}

# Tags both images as <repository>:boot4 and <repository>:boot3, pushes them, and records each pushed digest in
# PUSHED_boot4 and PUSHED_boot3; the caller has logged in.
push_images() {
  local repository=$1
  log "Pushing the images to $repository"
  local tag image digest
  for tag in boot4 boot3; do
    image="halyx/portable-orders:$IMAGE_VERSION"
    [[ $tag == boot3 ]] && image="halyx/portable-orders-boot3:$IMAGE_VERSION"
    docker tag "$image" "$repository:$tag" || return 1
    digest=$(docker push "$repository:$tag" | sed -n 's/.*digest: \(sha256:[0-9a-f]*\).*/\1/p' | tail -1)
    [[ -n "$digest" ]] || { echo "  pushing $tag failed"; return 1; }
    printf -v "PUSHED_$tag" '%s' "$digest"
    echo "  $tag: $digest (image $(docker image inspect -f '{{.Id}}' "$image"))"
  done
}

# Installs or upgrades the release with the run's values file and the given image, and waits until it is ready.
deploy() {
  local repository=$1 tag=$2
  log "Deploying $repository:$tag"
  if ! helm upgrade --install portable-orders "$CHART" --namespace "$NAMESPACE" --create-namespace \
      -f "$WORK/values.yaml" --set image.repository="$repository" --set image.tag="$tag" --set image.pullPolicy=Always \
      --wait --timeout 10m; then
    echo "  the release isn't ready:"
    kubectl -n "$NAMESPACE" get pods -o wide
    kubectl -n "$NAMESPACE" describe pods | tail -40
    kubectl -n "$NAMESPACE" logs deploy/portable-orders --tail=200
    return 1
  fi
  kubectl -n "$NAMESPACE" get pods -o wide | sed 's/^/  /'
  # What the pods run, by digest, against what was pushed: the same image on every cluster.
  local pushed="PUSHED_$tag" running
  running=$(kubectl -n "$NAMESPACE" get pods -l app.kubernetes.io/instance=portable-orders -o json | python3 -c '
import json, sys
pods = [p for p in json.load(sys.stdin)["items"]
        if p["status"].get("phase") == "Running" and "deletionTimestamp" not in p["metadata"]]
print("\n".join(sorted({c["imageID"].split("@")[-1] for p in pods for c in p["status"]["containerStatuses"]})))')
  echo "  running $running"
  mkdir -p "$REPORTS"
  echo "$RUN $tag pushed=${!pushed:-?} running=$running" >> "$REPORTS/$RUN-digests.txt"
  if [[ -n "${!pushed:-}" && "$running" != "${!pushed}" ]]; then
    echo "  THE PODS DON'T RUN THE PUSHED IMAGE"
    return 1
  fi
}

# Runs DeployedServiceIT from the given module through a port-forward to the service. ORDERS_E2E_FEATURES,
# ORDERS_E2E_MAX_LINES, ORDERS_E2E_CURRENCY and ORDERS_E2E_SIGNING_KEY describe what the deployment should be.
run_suite() {
  local module=$1 cloud=$2 label=$3
  log "Running the end-to-end suite ($label) through a port-forward"
  kubectl -n "$NAMESPACE" port-forward svc/portable-orders "$LOCAL_PORT:80" >"$WORK/port-forward.log" 2>&1 &
  local forward=$!
  for _ in $(seq 1 30); do
    curl -sf "http://localhost:$LOCAL_PORT/actuator/health/readiness" >/dev/null && break
    sleep 1
  done
  mvn -q verify -pl "halyx-samples/$module" -Dit.test=DeployedServiceIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dlicense.skip=true -Dorders.e2e.url="http://localhost:$LOCAL_PORT" -Dorders.e2e.cloud="$cloud" \
      -Dorders.e2e.features="$ORDERS_E2E_FEATURES" -Dorders.e2e.max-lines="$ORDERS_E2E_MAX_LINES" \
      -Dorders.e2e.currency="$ORDERS_E2E_CURRENCY" -Dorders.e2e.signing-key="$ORDERS_E2E_SIGNING_KEY"
  local status=$?
  kill "$forward" 2>/dev/null
  mkdir -p "$REPORTS"
  local report="$ROOT/halyx-samples/$module/target/failsafe-reports/dev.halyx.samples.orders.e2e.DeployedServiceIT.txt"
  if [[ -f "$report" ]]; then
    cp "$report" "$REPORTS/$RUN-$label.txt"
    grep "Tests run" "$report" | sed 's/^/  /'
  fi
  if [[ $status != 0 ]]; then
    echo "  failed; the service's log:"
    kubectl -n "$NAMESPACE" logs deploy/portable-orders --tail=300 | grep -E "WARN|ERROR|Exception|Caused by" | head -60
  fi
  return $status
}

# Both images in turn: Boot 4, then Boot 3.5.
deploy_and_test() {
  local repository=$1 cloud=$2
  local status=0
  deploy "$repository" boot4 || return 1
  run_suite portable-orders "$cloud" "$cloud-boot4" || status=1
  deploy "$repository" boot3 || return 1
  run_suite portable-orders-boot3 "$cloud" "$cloud-boot3" || status=1
  return $status
}
