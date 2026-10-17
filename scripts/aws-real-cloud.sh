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

# Runs the real-AWS tests, then deletes everything the run created.
#
#   AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
#   IT_TEST='RealAwsIT$SecretsManager' scripts/aws-real-cloud.sh     # one suite only
#   TIER=2 scripts/aws-real-cloud.sh                                  # RDS PostgreSQL and MySQL with IAM auth
#   TIER=3 scripts/aws-real-cloud.sh                                  # ElastiCache for Valkey with IAM auth
#   TIER=4 scripts/aws-real-cloud.sh                                  # streams on MSK Serverless with IAM
#
# Tier 1 (the default):
# 1. Terraform creates an S3 bucket and two DynamoDB tables named after a fresh run id (tagged halyx-test=<id>).
# 2. RealAwsIT runs the blob, document store, messaging and parameter kits; it creates SNS topics and SQS queues
#    named after the run, and parameters and secrets under tck-<random> names, and deletes them as it goes.
# 3. Always, even if tests fail or the script is interrupted: sweep anything of this run the tests left behind,
#    terraform destroy, and list what remains (expected: nothing).
#
# Tier 2: Terraform creates RDS PostgreSQL and MySQL instances (db.t4g.micro, IAM auth on, no backups) that
# accept connections only from this machine's public address, under a master password generated for the run
# and passed through the environment only. RealAwsSqlIT logs in with RDS IAM tokens and passwords. Creating
# and destroying the instances takes 10-15 minutes each way.
#
# Tier 3: Terraform creates an ElastiCache for Valkey replication group (TLS, IAM users) and a t4g.micro bastion
# that accepts SSH only from this machine, with a key pair generated for the run. ElastiCache has no public
# endpoint, so the script forwards a local port through the bastion and gives the test JVM a hosts file that
# points the cache's real hostname at 127.0.0.1: the client verifies the real certificate and hostname.
#
# Tier 4: Terraform creates an MSK Serverless cluster (IAM access control), a bucket for claim checks and a
# t4g.small runner VM in the same VPC that accepts SSH only from this machine. MSK Serverless has no public
# endpoint and Kafka clients connect to each broker by name, so the tests run on the runner: the script builds
# them here, copies them with their classpath, and runs RealAwsStreamIT there with temporary credentials of the
# test identity (sts get-session-token, passed over SSH and never written to the runner's disk).
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
if [[ "$TIER" == 2 || "$TIER" == 3 || "$TIER" == 4 ]]; then
  CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
  [[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
  TF_VARS+=(-var "client_cidr=$CLIENT_IP/32")
fi
if [[ "$TIER" == 2 ]]; then
  # RDS master passwords can't contain / @ " or spaces; keep it alphanumeric.
  export TF_VAR_master_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  export HALYX_RDS_MASTER_PASSWORD="$TF_VAR_master_password"
fi
if [[ "$TIER" == 3 || "$TIER" == 4 ]]; then
  WORK="$(mktemp -d -t "halyx-$RUN")"   # run key, known_hosts, SSH control socket, JVM hosts file, staged tests
  ssh-keygen -q -t ed25519 -N "" -C "halyx-$RUN" -f "$WORK/key"
  TF_VARS+=(-var "ssh_public_key=$(cat "$WORK/key.pub")")
  SSH_OPTS=(-i "$WORK/key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new
            -o UserKnownHostsFile="$WORK/known_hosts" -o ConnectTimeout=10 -o BatchMode=yes)
fi
if [[ "$TIER" == 3 ]]; then
  export TF_VAR_default_user_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  LOCAL_PORT=16379
fi

log() { printf '\n== %s\n' "$*"; }
# shellcheck source=in-vpc-runner.sh
source "$ROOT/scripts/in-vpc-runner.sh"

sweep() {
  log "Sweeping resources of run $RUN created by the tests"
  for arn in $(aws sns list-topics --query "Topics[?contains(TopicArn, ':halyx-$RUN-')].TopicArn" --output text); do
    echo "  deleting topic $arn"; aws sns delete-topic --topic-arn "$arn"
  done
  for url in $(aws sqs list-queues --queue-name-prefix "halyx-$RUN-" --query 'QueueUrls' --output text 2>/dev/null | tr '\t' '\n' | grep -v None); do
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
  if [[ "$TIER" == 3 && -n "${WORK:-}" ]]; then
    [[ -S "$WORK/ssh" ]] && ssh -S "$WORK/ssh" -O exit bastion 2>/dev/null && echo "  tunnel closed"
  fi
  sweep
  log "terraform destroy"
  if (cd "$TF_DIR" && terraform destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null); then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # held the run's generated passwords
    [[ -n "${WORK:-}" ]] && rm -rf "$WORK"                                    # the run's SSH key and staged tests
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
  fi
  log "Checking nothing of run $RUN remains"
  sleep 10
  local left
  # The tagging index keeps terminated instances and their deleted volumes for a while; those two are checked
  # directly below instead.
  left=$(aws resourcegroupstaggingapi get-resources --tag-filters "Key=halyx-test,Values=$RUN" \
      --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | grep -v -E ':(instance|volume)/')
  left+=$(aws sns list-topics --query "Topics[?contains(TopicArn, ':halyx-$RUN-')].TopicArn" --output text)
  left+=$(aws sqs list-queues --queue-name-prefix "halyx-$RUN-" --query 'QueueUrls' --output text 2>/dev/null | grep -v None)
  left+=$(aws events list-event-buses --name-prefix "halyx-$RUN-" --query 'EventBuses[].Name' --output text)
  left+=$(aws events list-rules --name-prefix "halyx-$RUN-" --query 'Rules[].Name' --output text)
  if [[ "$TIER" == 2 ]]; then
    for db in pg mysql; do
      left+=$(aws rds describe-db-instances --db-instance-identifier "halyx-$RUN-$db" \
          --query 'DBInstances[].DBInstanceStatus' --output text 2>/dev/null | sed "s/^/ db halyx-$RUN-$db:/")
    done
    left+=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=halyx-$RUN-db" \
        --query 'SecurityGroups[].GroupId' --output text)
    left+=$(aws rds describe-db-subnet-groups --db-subnet-group-name "halyx-$RUN" \
        --query 'DBSubnetGroups[].DBSubnetGroupName' --output text 2>/dev/null)
  fi
  if [[ "$TIER" == 3 ]]; then
    left+=$(aws elasticache describe-replication-groups --replication-group-id "halyx-$RUN" \
        --query 'ReplicationGroups[].Status' --output text 2>/dev/null | sed "s/^/ replication group halyx-$RUN:/")
    left+=$(aws elasticache describe-users --query "Users[?starts_with(UserId, 'halyx-$RUN-')].UserId" --output text)
    left+=$(aws elasticache describe-user-groups --query "UserGroups[?UserGroupId=='halyx-$RUN'].UserGroupId" --output text)
    left+=$(aws elasticache describe-cache-subnet-groups --cache-subnet-group-name "halyx-$RUN" \
        --query 'CacheSubnetGroups[].CacheSubnetGroupName' --output text 2>/dev/null)
    left+=$(aws ec2 describe-instances --filters "Name=tag:halyx-test,Values=$RUN" \
        "Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down" \
        --query 'Reservations[].Instances[].InstanceId' --output text)
    left+=$(aws ec2 describe-volumes --filters "Name=tag:halyx-test,Values=$RUN" --query 'Volumes[].VolumeId' --output text)
    left+=$(aws ec2 describe-key-pairs --filters "Name=key-name,Values=halyx-$RUN" --query 'KeyPairs[].KeyName' --output text)
    left+=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=halyx-$RUN-bastion,halyx-$RUN-cache" \
        --query 'SecurityGroups[].GroupId' --output text)
  fi
  if [[ "$TIER" == 4 ]]; then
    left+=$(aws kafka list-clusters-v2 --cluster-name-filter "halyx-$RUN" \
        --query 'ClusterInfoList[].[ClusterName,State]' --output text | sed 's/^/ MSK cluster /')
    left+=$(aws ec2 describe-instances --filters "Name=tag:halyx-test,Values=$RUN" \
        "Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down" \
        --query 'Reservations[].Instances[].InstanceId' --output text)
    left+=$(aws ec2 describe-volumes --filters "Name=tag:halyx-test,Values=$RUN" --query 'Volumes[].VolumeId' --output text)
    left+=$(aws ec2 describe-key-pairs --filters "Name=key-name,Values=halyx-$RUN" --query 'KeyPairs[].KeyName' --output text)
    left+=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=halyx-$RUN-runner,halyx-$RUN-msk" \
        --query 'SecurityGroups[].GroupId' --output text)
    left+=$(aws s3api head-bucket --bucket "halyx-$RUN-claims" 2>/dev/null && echo " bucket halyx-$RUN-claims")
  fi
  left+=$(aws s3api head-bucket --bucket "halyx-$RUN-blobs" 2>/dev/null && echo " bucket halyx-$RUN-blobs")
  for table in items items-sorted; do
    left+=$(aws dynamodb describe-table --table-name "halyx-$RUN-$table" --query 'Table.TableStatus' --output text 2>/dev/null \
        | grep -v DELETING | sed "s/^/ table halyx-$RUN-$table:/")
  done
  if [[ -z "${left//[[:space:]]/}" ]]; then
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
  mvn -o verify -pl halyx-aws -Dit.test="${IT_TEST:-RealAwsSqlIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.aws.sql=true -Dhalyx.real.aws.run="$RUN" \
      -Dhalyx.real.aws.sql.postgres-host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
      -Dhalyx.real.aws.sql.mysql-host="$(terraform -chdir="$TF_DIR" output -raw mysql_host)" \
      -Dhalyx.real.aws.sql.master-user="$(terraform -chdir="$TF_DIR" output -raw master_user)"
  status=$?
  [[ -n "${IT_TEST:-}" ]] && exit $status
  # Spring Boot 3 and 4: Spring Data R2DBC over halyx.r2dbc-databases, logging in to PostgreSQL with the identity.
  mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
  for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
    log "Running the Spring Boot R2DBC test ($module) against real AWS"
    mvn -o verify -pl "$module" -Dit.test=RealSpringR2dbcIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
        -Dhalyx.real.spring.r2dbc=true -Dhalyx.real.spring.provider=aws \
        -Dhalyx.real.spring.r2dbc.host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
        -Dhalyx.real.spring.r2dbc.user=halyx_iam || status=1
  done
  exit $status
fi
if [[ "$TIER" == 4 ]]; then
  RUNNER="ec2-user@$(terraform -chdir="$TF_DIR" output -raw runner_ip)"
  SPRING_MODULES=(halyx-spring-boot-autoconfigure halyx-spring-boot4-tests)
  [[ -n "${IT_TEST:-}" ]] && SPRING_MODULES=()
  for module in halyx-aws "${SPRING_MODULES[@]}"; do stage_tests "$module" || exit 1; done
  log "Waiting for the runner"
  wait_for_ssh "$RUNNER" || exit 1
  for _ in $(seq 1 60); do   # cloud-init installs Java
    ssh "${SSH_OPTS[@]}" "$RUNNER" test -f /var/tmp/halyx-ready && break
    sleep 5
  done
  ssh "${SSH_OPTS[@]}" "$RUNNER" java -version 2>&1 | head -1 | sed 's/^/  /'
  # Temporary credentials of the test identity for the runner, valid for the run only.
  read -r KEY SECRET SESSION < <(aws sts get-session-token --duration-seconds 7200 \
      --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text) \
    || { echo "  could not get a session token"; exit 1; }
  CREDS=("AWS_ACCESS_KEY_ID=$KEY" "AWS_SECRET_ACCESS_KEY=$SECRET" "AWS_SESSION_TOKEN=$SESSION" "AWS_REGION=$AWS_REGION")
  BOOTSTRAP="$(terraform -chdir="$TF_DIR" output -raw bootstrap)"
  run_remote_tests "$RUNNER" halyx-aws "dev.halyx.aws.${IT_TEST:-RealAwsStreamIT}" "${CREDS[@]}" -- \
      -Dhalyx.real.aws.streams=true -Dhalyx.real.aws.run="$RUN" -Dhalyx.real.aws.streams.bootstrap="$BOOTSTRAP" \
      -Dhalyx.real.aws.streams.claims-bucket="$(terraform -chdir="$TF_DIR" output -raw claims_bucket)"
  status=$?
  # Spring Boot 3 and 4, with the Kafka clients each manages.
  for module in "${SPRING_MODULES[@]}"; do
    run_remote_tests "$RUNNER" "$module" dev.halyx.spring.stream.RealSpringStreamIT "${CREDS[@]}" -- \
        -Dhalyx.real.spring.streams=true -Dhalyx.real.spring.provider=aws \
        -Dhalyx.real.spring.bootstrap="$BOOTSTRAP" || status=1
  done
  exit $status
fi
if [[ "$TIER" == 3 ]]; then
  BASTION=$(terraform -chdir="$TF_DIR" output -raw bastion_ip)
  CACHE_HOST=$(terraform -chdir="$TF_DIR" output -raw cache_host)
  log "Tunnelling 127.0.0.1:$LOCAL_PORT to $CACHE_HOST:6379 through $BASTION"
  for _ in $(seq 1 36); do   # the bastion takes a minute or so to accept SSH
    ssh "${SSH_OPTS[@]}" "ec2-user@$BASTION" true 2>/dev/null && break
    sleep 5
  done
  ssh "${SSH_OPTS[@]}" -M -S "$WORK/ssh" -f -N -o ExitOnForwardFailure=yes \
      -L "127.0.0.1:$LOCAL_PORT:$CACHE_HOST:6379" "ec2-user@$BASTION" || { echo "  could not open the tunnel"; exit 1; }
  # The test JVM resolves only the cache's name, to the tunnel; it needs no other lookups.
  echo "127.0.0.1 $CACHE_HOST" > "$WORK/hosts"
  log "Running the ElastiCache tests against real AWS"
  mvn -o verify -pl halyx-aws -Dit.test="${IT_TEST:-RealAwsCacheIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -DargLine="-Djdk.net.hosts.file=$WORK/hosts" \
      -Dhalyx.real.aws.cache=true -Dhalyx.real.aws.run="$RUN" \
      -Dhalyx.real.aws.cache.host="$CACHE_HOST" -Dhalyx.real.aws.cache.port="$LOCAL_PORT" \
      -Dhalyx.real.aws.cache.name="$(terraform -chdir="$TF_DIR" output -raw cache_name)" \
      -Dhalyx.real.aws.cache.app-user="$(terraform -chdir="$TF_DIR" output -raw app_user)" \
      -Dhalyx.real.aws.cache.denied-user="$(terraform -chdir="$TF_DIR" output -raw denied_user)"
  status=$?
  [[ -n "${IT_TEST:-}" ]] && exit $status
  # Spring Boot 3 and 4: Cache beans and @Cacheable logging in with the cloud identity.
  mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
  for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
    log "Running the Spring Boot cache test ($module) against real AWS"
    mvn -o verify -pl "$module" -Dit.test=RealSpringCacheIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
        -DargLine="-Djdk.net.hosts.file=$WORK/hosts" \
        -Dhalyx.real.spring.cache=true -Dhalyx.real.spring.provider=aws \
        -Dhalyx.real.spring.cache-endpoint="rediss://$CACHE_HOST:$LOCAL_PORT" \
        -Dhalyx.real.spring.cache-username="$(terraform -chdir="$TF_DIR" output -raw app_user)" \
        -Dhalyx.real.spring.cache-properties="aws.elasticache.cache-name=$(terraform -chdir="$TF_DIR" output -raw cache_name),redis.dns-resolver=jdk" || status=1
  done
  exit $status
fi
BUCKET=$(terraform -chdir="$TF_DIR" output -raw bucket)
ITEMS=$(terraform -chdir="$TF_DIR" output -raw items_table)
ITEMS_SORTED=$(terraform -chdir="$TF_DIR" output -raw items_sorted_table)
EVENT_BUS=$(terraform -chdir="$TF_DIR" output -raw event_bus)
EVENTS_INBOX=$(terraform -chdir="$TF_DIR" output -raw events_inbox)

log "Running the conformance kits against real AWS"
mvn -o verify -pl halyx-aws -Dit.test="${IT_TEST:-RealAwsIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhalyx.real.aws=true -Dhalyx.real.aws.run="$RUN" -Dhalyx.real.aws.bucket="$BUCKET" \
    -Dhalyx.real.aws.items-table="$ITEMS" -Dhalyx.real.aws.items-sorted-table="$ITEMS_SORTED" \
    -Dhalyx.real.aws.event-bus="$EVENT_BUS" -Dhalyx.real.aws.events-inbox="$EVENTS_INBOX"
status=$?
[[ -n "${IT_TEST:-}" ]] && exit $status
# Spring Boot 3 and 4: an auto-configured repository on the run's table with a sort key, the event bus,
# halyx-reactor's WebFlux, reactive repository and event beans over the bucket, table and bus, @HalyxListener on
# the run's SNS topic and queue with a claim check, and spring.config.import from SSM and Secrets Manager.
mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
  log "Running the Spring Boot repository, event and reactive tests ($module) against real AWS"
  mvn -o verify -pl "$module" -Dit.test='RealSpringRepositoryIT,RealSpringEventIT,RealSpringReactiveIT,RealSpringMessagingIT,RealSpringParametersIT' -Dtest=none \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.spring.repositories=true -Dhalyx.real.spring.events=true -Dhalyx.real.spring.reactive=true \
      -Dhalyx.real.spring.messaging=true -Dhalyx.real.spring.parameters=true \
      -Dhalyx.real.spring.provider=aws -Dhalyx.real.spring.bucket="$BUCKET" \
      -Dhalyx.real.spring.table="$ITEMS_SORTED" \
      -Dhalyx.real.spring.event-bus="$EVENT_BUS" -Dhalyx.real.spring.inbox="$EVENTS_INBOX" \
      -Dhalyx.real.spring.topic="$(terraform -chdir="$TF_DIR" output -raw spring_topic)" \
      -Dhalyx.real.spring.subscription="$(terraform -chdir="$TF_DIR" output -raw spring_queue)" || status=1
done
exit $status
