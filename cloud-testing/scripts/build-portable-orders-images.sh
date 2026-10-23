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

# Builds the portable orders images once: halyx/portable-orders (Spring Boot 4) and halyx/portable-orders-boot3
# (Spring Boot 3.5), from the working tree, with Cloud Native Buildpacks. eks-, gke- and aks-real-cloud.sh then push
# these same images, so every cluster runs one image, by digest.
#
#   cloud-testing/scripts/build-portable-orders-images.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION=1.0.0
LOGS="$(mktemp -d -t halyx-images)"

printf '\n== %s\n' "Building the Spring Boot 4 and 3.5 images"
cd "$ROOT" || exit 1
mvn -q install -DskipTests -Dlicense.skip=true -pl halyx-samples/portable-orders,halyx-samples/portable-orders-boot3 -am \
  || exit 1
for module in portable-orders portable-orders-boot3; do
  mvn -q -pl "halyx-samples/$module" -DskipTests spring-boot:build-image-no-fork >"$LOGS/$module.log" 2>&1 \
    || { tail -30 "$LOGS/$module.log"; exit 1; }
  echo "  halyx/$module:$VERSION $(docker image inspect -f '{{.Id}}' "halyx/$module:$VERSION")"
done
rm -rf "$LOGS"
