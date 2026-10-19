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

# Sourced by the real-cloud scripts for services that are reachable only inside a VPC and can't be tunnelled,
# such as managed Kafka, whose clients connect to each broker by the name the cluster advertises. The tests are
# built here, copied to a runner VM inside the VPC with their classpath, and run there with the JUnit console
# launcher; the reports come back into the module's target/failsafe-reports.
#
# Expects ROOT, WORK, SSH_OPTS and log() from the calling script.

JUNIT_CONSOLE_VERSION="${JUNIT_CONSOLE_VERSION:-6.1.3}"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"

# stage_tests <module>: builds the module and what it depends on, then stages its tests in $WORK/stage-<module>
# and its jars in $WORK/jars, which every module shares, under their Maven repository paths.
stage_tests() {
  local module=$1 stage="$WORK/stage-$1" console
  log "Building $module and staging its tests"
  rm -rf "$stage" && mkdir -p "$stage/classes" "$WORK/jars"
  (cd "$ROOT" && mvn -o -q install -DskipTests -pl "$module" -am) || return 1
  console="$M2_REPO/org/junit/platform/junit-platform-console-standalone/$JUNIT_CONSOLE_VERSION/junit-platform-console-standalone-$JUNIT_CONSOLE_VERSION.jar"
  [[ -f "$console" ]] || (cd "$ROOT" && mvn -q dependency:get \
      -Dartifact="org.junit.platform:junit-platform-console-standalone:$JUNIT_CONSOLE_VERSION") || return 1
  cp "$console" "$WORK/jars/junit-console.jar"
  (cd "$ROOT" && mvn -o -q dependency:build-classpath -pl "$module" -Dmdep.includeScope=test \
      -Dmdep.outputFile="$WORK/classpath-$module.txt") || return 1
  # The console launcher brings JUnit itself; a second copy on the classpath would register its engines twice.
  local classpath="classes/test:classes/main:../jars/junit-console.jar" jar relative count=0
  while read -r jar; do
    relative="${jar#"$M2_REPO"/}"
    mkdir -p "$WORK/jars/$(dirname "$relative")"
    cp -p "$jar" "$WORK/jars/$relative"
    classpath+=":../jars/$relative"
    count=$((count + 1))
  done < <(tr ':' '\n' < "$WORK/classpath-$module.txt" \
      | grep -v -E '/org/junit/(jupiter|platform)/|/org/opentest4j/|/org/apiguardian/')
  printf '%s' "$classpath" > "$stage/classpath"
  cp -R "$ROOT/$module/target/test-classes" "$stage/classes/test"
  if [[ -d "$ROOT/$module/target/classes" ]]; then
    cp -R "$ROOT/$module/target/classes" "$stage/classes/main"
  else
    mkdir "$stage/classes/main"    # a test-only module
  fi
  echo "  $count jars; all staged jars $(du -sh "$WORK/jars" | cut -f1)"
}

# wait_for_ssh <user@host>: the VM takes a minute or so to accept SSH.
wait_for_ssh() {
  for _ in $(seq 1 60); do
    ssh "${SSH_OPTS[@]}" "$1" true 2>/dev/null && return 0
    sleep 5
  done
  echo "  $1 does not accept SSH"; return 1
}

# run_remote_tests <user@host> <module> <test class> <env assignments...> -- <JVM system properties...>
# Copies the staged tests (only jars the runner doesn't have yet), runs them there and fetches the reports. The
# environment assignments go through the SSH session's stdin, so they're neither on the runner's disk nor in its
# process list.
run_remote_tests() {
  local target=$1 module=$2 test=$3; shift 3
  local env=() props=()
  while [[ $# -gt 0 && "$1" != -- ]]; do env+=("$1"); shift; done
  [[ "${1:-}" == -- ]] && shift
  props=("$@")
  local remote="halyx/$module" rsh="ssh ${SSH_OPTS[*]}"
  log "Copying the $module tests to the runner"
  ssh "${SSH_OPTS[@]}" "$target" "mkdir -p halyx/jars && rm -rf $remote" || return 1
  rsync -a -e "$rsh" "$WORK/jars/" "$target:halyx/jars/" || return 1
  rsync -a -e "$rsh" "$WORK/stage-$module/" "$target:$remote/" || return 1
  log "Running $test on the runner"
  {
    for assignment in "${env[@]}"; do printf 'export %q\n' "$assignment"; done
    printf 'cd %q\n' "$remote"
    printf 'exec java -Xmx1g -cp "$(cat classpath)"'
    for prop in "${props[@]}"; do printf ' %q' "$prop"; done
    printf ' org.junit.platform.console.ConsoleLauncher execute --disable-banner --details=tree'
    printf ' --fail-if-no-tests %q --reports-dir=reports\n' "--select-class=$test"
  } | ssh "${SSH_OPTS[@]}" "$target" bash -s
  local status=$?
  mkdir -p "$ROOT/$module/target/failsafe-reports"
  scp -q "${SSH_OPTS[@]}" "$target:$remote/reports/*.xml" "$ROOT/$module/target/failsafe-reports/" \
    && echo "  reports in $module/target/failsafe-reports"
  return $status
}
