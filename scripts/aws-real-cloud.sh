#!/usr/bin/env bash
# Runs the real-AWS tests, then deletes everything the run created.
#
#   AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
#   IT_TEST='RealAwsIT$SecretsManager' scripts/aws-real-cloud.sh     # one suite only
#   TIER=2 scripts/aws-real-cloud.sh                                  # RDS PostgreSQL and MySQL with IAM auth
#
# Tier 1 (the default):
# 1. Terraform creates an S3 bucket and two DynamoDB tables named after a fresh run id (tagged cirrus-test=<id>).
# 2. RealAwsIT runs the blob, document store, messaging and parameter kits; it creates SNS topics and SQS queues
#    named after the run, and parameters and secrets under tck-<random> names, and deletes them as it goes.
# 3. Always, even if tests fail or the script is interrupted: sweep anything of this run the tests left behind,
#    terraform destroy, and list what remains (expected: nothing).
#
# Tier 2: Terraform creates RDS PostgreSQL and MySQL instances (db.t4g.micro, IAM auth on, no backups) that
# accept connections only from this machine's public address, under a master password generated for the run
# and passed through the environment only. RealAwsSqlIT logs in with RDS IAM tokens and passwords. Creating
# and destroying the instances takes 10-15 minutes each way.
set -uo pipefail

export AWS_PROFILE="${AWS_PROFILE:-default}"
export AWS_REGION="${AWS_REGION:-ap-southeast-2}"
export AWS_PAGER=""
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TIER="${TIER:-1}"
TF_DIR="$ROOT/infra/aws/tier$TIER"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
START="$(date -u +%Y-%m-%dT%H:%M:%S)"
TF_VARS=(-var "region=$AWS_REGION" -var "run_id=$RUN")
if [[ "$TIER" == 2 ]]; then
  CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
  [[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
  TF_VARS+=(-var "client_cidr=$CLIENT_IP/32")
  # RDS master passwords can't contain / @ " or spaces; keep it alphanumeric.
  export TF_VAR_master_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  export CIRRUS_RDS_MASTER_PASSWORD="$TF_VAR_master_password"
fi

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
  if (cd "$TF_DIR" && terraform destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null); then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # held the run's master password
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
  fi
  log "Checking nothing of run $RUN remains"
  sleep 10
  local left
  left=$(aws resourcegroupstaggingapi get-resources --tag-filters "Key=cirrus-test,Values=$RUN" \
      --query 'ResourceTagMappingList[].ResourceARN' --output text)
  left+=$(aws sns list-topics --query "Topics[?contains(TopicArn, ':cirrus-$RUN-')].TopicArn" --output text)
  left+=$(aws sqs list-queues --queue-name-prefix "cirrus-$RUN-" --query 'QueueUrls' --output text 2>/dev/null | grep -v None)
  if [[ "$TIER" == 2 ]]; then
    for db in pg mysql; do
      left+=$(aws rds describe-db-instances --db-instance-identifier "cirrus-$RUN-$db" \
          --query 'DBInstances[].DBInstanceStatus' --output text 2>/dev/null | sed "s/^/ db cirrus-$RUN-$db:/")
    done
    left+=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=cirrus-$RUN-db" \
        --query 'SecurityGroups[].GroupId' --output text)
    left+=$(aws rds describe-db-subnet-groups --db-subnet-group-name "cirrus-$RUN" \
        --query 'DBSubnetGroups[].DBSubnetGroupName' --output text 2>/dev/null)
  fi
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
cd "$ROOT"
if [[ "$TIER" == 2 ]]; then
  log "Running the RDS tests against real AWS"
  mvn -o verify -pl cirrus-aws -Dit.test="${IT_TEST:-RealAwsSqlIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dcirrus.real.aws.sql=true -Dcirrus.real.aws.run="$RUN" \
      -Dcirrus.real.aws.sql.postgres-host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
      -Dcirrus.real.aws.sql.mysql-host="$(terraform -chdir="$TF_DIR" output -raw mysql_host)" \
      -Dcirrus.real.aws.sql.master-user="$(terraform -chdir="$TF_DIR" output -raw master_user)"
  exit $?
fi
BUCKET=$(terraform -chdir="$TF_DIR" output -raw bucket)
ITEMS=$(terraform -chdir="$TF_DIR" output -raw items_table)
ITEMS_SORTED=$(terraform -chdir="$TF_DIR" output -raw items_sorted_table)

log "Running the conformance kits against real AWS"
mvn -o verify -pl cirrus-aws -Dit.test="${IT_TEST:-RealAwsIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dcirrus.real.aws=true -Dcirrus.real.aws.run="$RUN" -Dcirrus.real.aws.bucket="$BUCKET" \
    -Dcirrus.real.aws.items-table="$ITEMS" -Dcirrus.real.aws.items-sorted-table="$ITEMS_SORTED"
