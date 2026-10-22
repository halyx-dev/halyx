#!/usr/bin/env bash
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

# Runs the portable orders service on EKS and its end-to-end suite against it, then deletes everything the run
# created.
#
#   AWS_PROFILE=default AWS_REGION=ap-southeast-2 cloud-testing/scripts/eks-real-cloud.sh
#   EKS_NODE_TYPE=t3.small cloud-testing/scripts/eks-real-cloud.sh
#
# 1. Terraform creates, in the default VPC: an EKS cluster with EKS Pod Identity and one managed node, an ECR
#    repository, an IAM role for the service's Kubernetes service account that may use only the run's resources, and
#    the services the sample uses (S3, DynamoDB, SNS and SQS, an EventBridge bus routed to SQS, ElastiCache for Valkey
#    with an IAM user, RDS for PostgreSQL with IAM auth, SSM Parameter Store and Secrets Manager), plus the run's
#    settings and signing key. Streams are off: MSK isn't available on the test account's plan.
# 2. The script creates the IAM database user (as the run's master user, from this machine), builds the Spring Boot 4
#    and 3.5 images, pushes them, deploys each with the Helm chart and runs DeployedServiceIT against it through a
#    port-forward.
# 3. Always, even if tests fail or the script is interrupted: terraform destroy, and list what remains (expected:
#    nothing).
#
# The test identity needs EKS, ECR and IAM role permissions beyond the tier tests'; see docs/cloud-testing/aws.md.
# Creating the cluster takes about 10 minutes, and its node and the database a few more.
set -uo pipefail

export AWS_PROFILE="${AWS_PROFILE:-default}"
export AWS_REGION="${AWS_REGION:-ap-southeast-2}"
export AWS_PAGER=""
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TF_DIR="$ROOT/cloud-testing/infra/aws/k8s"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
[[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
TF_VARS=(-var "region=$AWS_REGION" -var "run_id=$RUN" -var "client_cidr=$CLIENT_IP/32")
[[ -n "${EKS_NODE_TYPE:-}" ]] && TF_VARS+=(-var "node_type=$EKS_NODE_TYPE")
# RDS master passwords can't contain / @ " or spaces; keep both alphanumeric.
export TF_VAR_master_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
export TF_VAR_cache_default_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
WORK="$(mktemp -d -t "halyx-$RUN")"   # kubeconfig, values
export KUBECONFIG="$WORK/kubeconfig"
REGISTRY=""

log() { printf '\n== %s\n' "$*"; }
# shellcheck source=k8s-portable-orders.sh
source "$ROOT/cloud-testing/scripts/k8s-portable-orders.sh"

check() {
  local what=$1; shift
  local out
  if out=$("$@" 2>/dev/null); then
    printf '%s' "$out"
  else
    printf ' CHECK FAILED (%s)' "$what"
  fi
}

teardown() {
  local status=$?
  trap - EXIT INT TERM
  [[ -n "$REGISTRY" ]] && docker logout "$REGISTRY" >/dev/null 2>&1
  # The release first, so nothing the cluster made (such as network interfaces) outlives it.
  [[ -s "$KUBECONFIG" ]] && helm uninstall portable-orders --namespace "$NAMESPACE" --wait >/dev/null 2>&1
  log "terraform destroy"
  if terraform -chdir="$TF_DIR" destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null; then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # held the run's passwords and signing key
    rm -rf "$WORK"
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR with the same -var values (run_id=$RUN)"
    [[ $status == 0 ]] && status=1   # the tests passed, but the run left something behind
  fi
  log "Checking nothing of run $RUN remains"
  sleep 10
  local left=""
  # The tagging index keeps terminated instances and deleted volumes for a while; those are checked directly below.
  left+=$(check "tagged resources" aws resourcegroupstaggingapi get-resources --tag-filters "Key=halyx-test,Values=$RUN" \
      --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | grep -v -E ':(instance|volume)/')
  left+=$(check "EKS clusters" aws eks list-clusters --query "clusters[?starts_with(@, 'halyx-$RUN')]" --output text)
  left+=$(check "ECR repositories" aws ecr describe-repositories \
      --query "repositories[?starts_with(repositoryName, 'halyx-$RUN')].repositoryName" --output text)
  left+=$(check "IAM roles" aws iam list-roles --query "Roles[?starts_with(RoleName, 'halyx-$RUN')].RoleName" --output text)
  left+=$(check "RDS" aws rds describe-db-instances --query "DBInstances[?starts_with(DBInstanceIdentifier, 'halyx-$RUN')].[DBInstanceIdentifier,DBInstanceStatus]" --output text)
  left+=$(check "ElastiCache" aws elasticache describe-replication-groups \
      --query "ReplicationGroups[?ReplicationGroupId=='halyx-$RUN'].Status" --output text)
  left+=$(check "ElastiCache users" aws elasticache describe-users --query "Users[?starts_with(UserId, 'halyx-$RUN-')].UserId" --output text)
  left+=$(check "EC2 instances" aws ec2 describe-instances --filters "Name=tag:eks:cluster-name,Values=halyx-$RUN" \
      "Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down" \
      --query 'Reservations[].Instances[].InstanceId' --output text)
  left+=$(check "security groups" aws ec2 describe-security-groups \
      --filters "Name=group-name,Values=halyx-$RUN-cache,halyx-$RUN-db" --query 'SecurityGroups[].GroupId' --output text)
  left+=$(check "SNS topics" aws sns list-topics --query "Topics[?contains(TopicArn, ':halyx-$RUN-')].TopicArn" --output text)
  left+=$(check "SQS queues" aws sqs list-queues --queue-name-prefix "halyx-$RUN-" --query 'QueueUrls' --output text | grep -v None)
  left+=$(check "event buses" aws events list-event-buses --name-prefix "halyx-$RUN" --query 'EventBuses[].Name' --output text)
  left+=$(check "parameters" aws ssm get-parameters-by-path --path "/halyx-$RUN" --recursive --query 'Parameters[].Name' --output text)
  left+=$(check "secrets" aws secretsmanager list-secrets --filters "Key=name,Values=halyx-$RUN/" --query 'SecretList[].Name' --output text)
  if [[ -z "${left//[[:space:]]/}" ]]; then
    echo "  nothing left"
  else
    echo "  STILL PRESENT (the tagging index can lag a few minutes; check again before deleting by hand):"
    [[ $status == 0 ]] && status=1   # the tests passed, but the run left something behind
    echo "$left" | tr '\t' '\n' | sed 's/^/    /'
  fi
  exit $status
}
trap teardown EXIT INT TERM

log "Account $(aws sts get-caller-identity --query Account --output text), region $AWS_REGION, run $RUN"
build_images || exit 1

log "terraform apply (the cluster takes about 10 minutes)"
terraform -chdir="$TF_DIR" init -input=false >/dev/null || exit 1
terraform -chdir="$TF_DIR" apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1
tf() { terraform -chdir="$TF_DIR" output -raw "$1"; }

aws eks update-kubeconfig --name "$(tf cluster)" --kubeconfig "$KUBECONFIG" >/dev/null || exit 1
chmod 600 "$KUBECONFIG"
kubectl get nodes -o wide | sed 's/^/  /'

REPOSITORY="$(tf repository)"
REGISTRY="${REPOSITORY%%/*}"
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null || exit 1
push_images "$REPOSITORY" || exit 1

log "Creating the IAM database user $(tf db_user)"
PGPASSWORD="$TF_VAR_master_password" docker run --rm -e PGPASSWORD postgres:17-alpine psql -v ON_ERROR_STOP=1 -q \
    "host=$(tf db_host) sslmode=require user=halyx_admin dbname=orders" \
    -c "CREATE USER $(tf db_user); GRANT rds_iam TO $(tf db_user); GRANT ALL ON SCHEMA public TO $(tf db_user);" || exit 1

# The chart's values for this run: every feature EKS can offer and the run's resource names. EKS Pod Identity binds the
# service account by name, so it needs no annotation.
terraform -chdir="$TF_DIR" output -json env | python3 -c '
import json, sys
print(json.dumps({"profiles": ["aws", "events", "cache", "sql"], "env": json.load(sys.stdin)}, indent=2))' \
  > "$WORK/values.yaml" || exit 1

export ORDERS_E2E_FEATURES="events,cache,sql" ORDERS_E2E_MAX_LINES=8 ORDERS_E2E_CURRENCY=CAD
ORDERS_E2E_SIGNING_KEY="$(tf signing_key)"
export ORDERS_E2E_SIGNING_KEY
deploy_and_test "$REPOSITORY" aws
