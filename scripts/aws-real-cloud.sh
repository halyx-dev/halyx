#!/usr/bin/env bash
# Runs the tier 1 conformance kits against real AWS, then deletes everything the run created.
#
#   AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
#   IT_TEST='RealAwsIT$SecretsManager' scripts/aws-real-cloud.sh     # one suite only
#
# 1. Terraform creates an S3 bucket and two DynamoDB tables named after a fresh run id (tagged cirrus-test=<id>).
# 2. RealAwsIT runs the blob, document store, messaging and parameter kits; it creates SNS topics and SQS queues
#    named after the run, and parameters and secrets under tck-<random> names, and deletes them as it goes.
# 3. Always, even if tests fail or the script is interrupted: sweep anything of this run the tests left behind,
#    terraform destroy, and list what remains (expected: nothing).
set -uo pipefail

export AWS_PROFILE="${AWS_PROFILE:-default}"
export AWS_REGION="${AWS_REGION:-ap-southeast-2}"
export AWS_PAGER=""
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF_DIR="$ROOT/infra/aws/tier1"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
START="$(date -u +%Y-%m-%dT%H:%M:%S)"
TF_VARS=(-var "region=$AWS_REGION" -var "run_id=$RUN")

log() { printf '\n== %s\n' "$*"; }

sweep() {
  log "Sweeping resources of run $RUN created by the tests"
  for arn in $(aws sns list-topics --query "Topics[?contains(TopicArn, ':cirrus-$RUN-')].TopicArn" --output text); do
    echo "  deleting topic $arn"; aws sns delete-topic --topic-arn "$arn"
  done
  for url in $(aws sqs list-queues --queue-name-prefix "cirrus-$RUN-" --query 'QueueUrls' --output text 2>/dev/null | tr '\t' '\n' | grep -v None); do
    echo "  deleting queue $url"; aws sqs delete-queue --queue-url "$url"
  done
  # Parameters and secrets the parameter kit created during this run (names start with tck-).
  for name in $(aws ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/tck-" \
      --query "Parameters[?LastModifiedDate>=\`$START\`].Name" --output text); do
    echo "  deleting parameter $name"; aws ssm delete-parameter --name "$name"
  done
  for id in $(aws secretsmanager list-secrets --filters "Key=name,Values=tck-" \
      --query "SecretList[?CreatedDate>=\`$START\`].ARN" --output text); do
    echo "  deleting secret $id"; aws secretsmanager delete-secret --secret-id "$id" --force-delete-without-recovery >/dev/null
  done
}

teardown() {
  local status=$?
  trap - EXIT INT TERM
  sweep
  log "terraform destroy"
  (cd "$TF_DIR" && terraform destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null) \
      && echo "  destroyed" || echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
  log "Checking nothing of run $RUN remains"
  sleep 10
  local left
  left=$(aws resourcegroupstaggingapi get-resources --tag-filters "Key=cirrus-test,Values=$RUN" \
      --query 'ResourceTagMappingList[].ResourceARN' --output text)
  left+=$(aws sns list-topics --query "Topics[?contains(TopicArn, ':cirrus-$RUN-')].TopicArn" --output text)
  left+=$(aws sqs list-queues --queue-name-prefix "cirrus-$RUN-" --query 'QueueUrls' --output text 2>/dev/null | grep -v None)
  left+=$(aws s3api head-bucket --bucket "cirrus-$RUN-blobs" 2>/dev/null && echo " bucket cirrus-$RUN-blobs")
  for table in items items-sorted; do
    left+=$(aws dynamodb describe-table --table-name "cirrus-$RUN-$table" --query 'Table.TableStatus' --output text 2>/dev/null \
        | grep -v DELETING | sed "s/^/ table cirrus-$RUN-$table:/")
  done
  if [[ -z "${left// /}" ]]; then
    echo "  nothing left"
  else
    echo "  STILL PRESENT (the tagging index can lag a few minutes; check again before deleting by hand):"
    echo "$left" | tr '\t' '\n' | sed 's/^/    /'
  fi
  exit $status
}
trap teardown EXIT INT TERM

log "Account $(aws sts get-caller-identity --query Account --output text), region $AWS_REGION, run $RUN"
log "terraform apply"
cd "$TF_DIR"
terraform init -input=false >/dev/null || exit 1
terraform apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1
BUCKET=$(terraform output -raw bucket)
ITEMS=$(terraform output -raw items_table)
ITEMS_SORTED=$(terraform output -raw items_sorted_table)

log "Running the conformance kits against real AWS"
cd "$ROOT"
mvn -o verify -pl cirrus-aws -Dit.test="${IT_TEST:-RealAwsIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dcirrus.real.aws=true -Dcirrus.real.aws.run="$RUN" -Dcirrus.real.aws.bucket="$BUCKET" \
    -Dcirrus.real.aws.items-table="$ITEMS" -Dcirrus.real.aws.items-sorted-table="$ITEMS_SORTED"
