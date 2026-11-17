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

# Runs the cross-cloud key tests: one envelope sealed for the long-lived tck-data keys on AWS, Azure and Google Cloud
# at once, on Spring Boot 3 and 4.
#
#   cloud-testing/scripts/cross-cloud-keys.sh
#   AWS_REGION=ap-southeast-2 GCP_PROJECT=my-project cloud-testing/scripts/cross-cloud-keys.sh
#
# The tests act as each cloud's test identity, all in one JVM, as the per-cloud scripts set them up:
#   AWS:          the AWS_PROFILE (default "default") profile, the test user, in AWS_REGION (default ap-southeast-2)
#   Azure:        the service principal in AZURE_CREDENTIALS_FILE (default ~/.azure/halyx-test.env)
#   Google Cloud: Application Default Credentials impersonating the test service account (GCP_IMPERSONATE)
#
# The keys are the long-lived ones in cloud-testing/infra/<cloud>/keys, applied once by an admin. The Azure vault and
# the Google Cloud key ring are read from those stacks' Terraform state on this machine, so run this where they were
# applied. RealSpringCrossCloudKeysIT seals data for all three keys and opens it on each cloud alone, and adds, moves
# and removes keys without touching the encrypted body. Sealing and opening create nothing in any cloud, so there is
# no teardown; the cost is a few dozen key operations.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# AWS
export AWS_PROFILE="${AWS_PROFILE:-default}"
export AWS_REGION="${AWS_REGION:-ap-southeast-2}"
aws kms describe-key --key-id alias/tck-data --query KeyMetadata.KeyId --output text >/dev/null 2>&1 || {
  echo "alias/tck-data isn't in $AWS_REGION for profile $AWS_PROFILE: apply cloud-testing/infra/aws/keys first"
  echo "(see docs/cloud-testing/aws.md)"; exit 1; }

# Azure: the service principal's variables are exported for the JVM's default credential chain.
CREDS="${AZURE_CREDENTIALS_FILE:-$HOME/.azure/halyx-test.env}"
[[ -r "$CREDS" ]] || { echo "No service principal credentials at $CREDS (see docs/cloud-testing/azure.md)"; exit 1; }
set -a; source "$CREDS"; set +a
for v in AZURE_CLIENT_ID AZURE_CLIENT_SECRET AZURE_TENANT_ID; do
  [[ -n "${!v:-}" ]] || { echo "$v is missing from $CREDS"; exit 1; }
done
VAULT_URL="$(terraform -chdir="$ROOT/cloud-testing/infra/azure/keys" output -raw vault_url 2>/dev/null)"
[[ "$VAULT_URL" == https://* ]] || {
  echo "No state in cloud-testing/infra/azure/keys: apply it first (see docs/cloud-testing/azure.md)"; exit 1; }

# Google Cloud
PROJECT="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
[[ -n "$PROJECT" ]] || { echo "Set GCP_PROJECT or a gcloud default project"; exit 1; }
IMPERSONATE="${GCP_IMPERSONATE:-halyx-test@$PROJECT.iam.gserviceaccount.com}"
export GOOGLE_CLOUD_PROJECT="$PROJECT"
gcloud auth application-default print-access-token >/dev/null 2>&1 || {
  echo "Application Default Credentials can't get a token; run:"
  echo "  gcloud auth application-default login --impersonate-service-account=$IMPERSONATE"; exit 1; }
KEY_RING="$(terraform -chdir="$ROOT/cloud-testing/infra/gcp/keys" output -raw global_key_ring 2>/dev/null)"
[[ "$KEY_RING" == projects/* ]] || {
  echo "No state in cloud-testing/infra/gcp/keys: apply it first (see docs/cloud-testing/gcp.md)"; exit 1; }

cd "$ROOT" || exit 1
mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
status=0
for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
  printf '\n== %s\n' "Running the cross-cloud key tests ($module) on AWS, Azure and Google Cloud"
  mvn -o verify -pl "$module" -Dit.test=RealSpringCrossCloudKeysIT -Dtest=none \
      -Dsurefire.failIfNoSpecifiedTests=false -Dhalyx.real.spring.cross-cloud-keys=true \
      -Dhalyx.real.spring.aws-region="$AWS_REGION" \
      -Dhalyx.real.spring.azure-vault-url="$VAULT_URL" \
      -Dhalyx.real.spring.gcp-project="$PROJECT" -Dhalyx.real.spring.gcp-key-ring="$KEY_RING" || status=1
done
exit $status
