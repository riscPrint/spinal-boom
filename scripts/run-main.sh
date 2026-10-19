#!/usr/bin/env bash
# Run a main class without starting sbt each time.
#
#   scripts/run-main.sh boom.system.GenFormal formal
#
# sbt runs (compile + export the runtime classpath) only when a file under src/main,
# project/ or build.sbt is newer than the cached classpath, or a file was added or
# removed. Otherwise this is a plain `java`, and no sbt server is left running.
#
# Runs from the repo root, so relative arguments resolve against it (as with
# `sbt runMain`). JVM options: RUN_MAIN_JVM_OPTS (default below), then JAVA_OPTS.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
source env.sh

cp_file="target/run-main.classpath"
list_file="target/run-main.sources"
# sbt's own output under project/target and project/project/target doesn't count.
inputs() { find src/main project build.sbt -name target -prune -o -type f "$@" -print; }
sources="$(inputs | LC_ALL=C sort)"

if [[ ! -s "$cp_file" ]] || [[ "$sources" != "$(cat "$list_file" 2>/dev/null)" ]] ||
   [[ -n "$(inputs -newer "$cp_file" | head -1)" ]]; then
  mkdir -p target
  trap 'rm -f "$cp_file.tmp" "$cp_file.start"' EXIT
  touch "$cp_file.start"   # edits made while sbt runs must still count as newer
  sbt -batch --error "export Runtime/fullClasspath" | tail -1 > "$cp_file.tmp"
  touch -r "$cp_file.start" "$cp_file.tmp"
  mv "$cp_file.tmp" "$cp_file"
  rm -f "$cp_file.start"   # the EXIT trap doesn't run once we exec java
  printf '%s' "$sources" > "$list_file"
fi

read -ra jvm_opts <<< "${RUN_MAIN_JVM_OPTS:--Xss64m -Xmx8g -XX:+UseParallelGC} ${JAVA_OPTS:-}"
# ${a[@]+...} keeps bash 3.2 (macOS /bin/bash) happy when the array is empty under set -u.
exec java ${jvm_opts[@]+"${jvm_opts[@]}"} -cp "$(cat "$cp_file")" "$@"
