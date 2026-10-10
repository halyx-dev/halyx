#!/usr/bin/env bash
# Runs VaultKubernetesLoginIT against Vault on a local minikube cluster:
#   1. starts (or reuses) the minikube profile "stratum"
#   2. deploys Vault in dev mode with permission to review service account tokens
#   3. enables Kubernetes auth with a role bound to the "stratum-app" service account
#   4. port-forwards Vault, issues a real service account token, and runs the test
# Usage: scripts/vault-kubernetes-it.sh [--delete]   (--delete removes the profile afterwards)
set -euo pipefail

PROFILE=stratum
CTX=(--context "$PROFILE")
NS=stratum-vault
LOCAL_PORT=18200
WORK=$(mktemp -d)
trap 'kill "${PF_PID:-0}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

if ! minikube -p "$PROFILE" status >/dev/null 2>&1; then
  minikube start -p "$PROFILE" --driver=docker --cpus=2 --memory=3072
fi

kubectl "${CTX[@]}" apply -f - <<YAML
apiVersion: v1
kind: Namespace
metadata: { name: $NS }
---
apiVersion: v1
kind: ServiceAccount
metadata: { name: vault, namespace: $NS }
---
# Vault calls the TokenReview API to validate the JWTs clients log in with.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: { name: stratum-vault-token-review }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: system:auth-delegator }
subjects: [ { kind: ServiceAccount, name: vault, namespace: $NS } ]
---
apiVersion: v1
kind: ServiceAccount
metadata: { name: stratum-app, namespace: $NS }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: vault, namespace: $NS }
spec:
  replicas: 1
  selector: { matchLabels: { app: vault } }
  template:
    metadata: { labels: { app: vault } }
    spec:
      serviceAccountName: vault
      containers:
        - name: vault
          image: hashicorp/vault:2.1.2
          args: [ "server", "-dev", "-dev-listen-address=0.0.0.0:8200" ]
          env: [ { name: VAULT_DEV_ROOT_TOKEN_ID, value: root-token } ]
          ports: [ { containerPort: 8200 } ]
          readinessProbe: { httpGet: { path: /v1/sys/health, port: 8200 } }
YAML
kubectl "${CTX[@]}" -n "$NS" rollout status deployment/vault --timeout=180s

vault() { kubectl "${CTX[@]}" -n "$NS" exec -i deploy/vault -- env VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=root-token vault "$@"; }
vault auth enable kubernetes 2>/dev/null || true
# In a pod, Vault uses its own service account token and the cluster CA to call TokenReview.
vault write auth/kubernetes/config kubernetes_host=https://kubernetes.default.svc
vault policy write stratum-app - <<'HCL'
path "secret/*" { capabilities = ["create", "read", "update", "delete", "list"] }
HCL
vault write auth/kubernetes/role/stratum-app \
  bound_service_account_names=stratum-app bound_service_account_namespaces="$NS" \
  policies=stratum-app ttl=10m

kubectl "${CTX[@]}" -n "$NS" port-forward deploy/vault "$LOCAL_PORT:8200" >/dev/null 2>&1 &
PF_PID=$!
for _ in $(seq 1 30); do curl -sf "http://127.0.0.1:$LOCAL_PORT/v1/sys/health" >/dev/null && break; sleep 1; done

# A real, short-lived service account token, exactly what a pod gets projected.
kubectl "${CTX[@]}" -n "$NS" create token stratum-app --duration=10m > "$WORK/token"

cd "$(dirname "$0")/.."
STRATUM_VAULT_K8S_ADDR="http://127.0.0.1:$LOCAL_PORT" \
STRATUM_VAULT_K8S_ROLE=stratum-app \
STRATUM_VAULT_K8S_JWT="$WORK/token" \
  mvn -q verify -pl stratum-vault -Dit.test=VaultKubernetesLoginIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false

if [[ "${1:-}" == "--delete" ]]; then
  minikube delete -p "$PROFILE"
fi
