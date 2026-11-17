#!/usr/bin/env bash
# Generate BoomFormalTop.v and run riscv-formal checks on it.
#
#   formal/run.sh [riscv-formal dir] [make args...]
#
# e.g. formal/run.sh -j4            # all checks
#      formal/run.sh cover_ch0      # just the cover check
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
source "$root/scripts/verification-deps.sh"
explicit=""
if [ "$#" -gt 0 ] && [ -d "$1" ]; then explicit="$1"; shift; fi
source_dir="$(boom_resolve_source riscv-formal "$explicit")"
mkdir -p "$root/target/verification/formal"
private="$(mktemp -d "$root/target/verification/formal/run.XXXXXX")"
rvf="$private/source"
boom_copy_source "$source_dir" "$rvf"
echo "Formal work directory: $private" >&2

cd "$root"
source env.sh
scripts/run-main.sh boom.system.GenFormal formal

# riscv-formal expects cores/<name>/ inside its tree. genchecks.py derives the core
# name and tree from the physical cwd, so link the files, not the directory.
mkdir -p "$rvf/cores/boom"
for f in wrapper.sv checks.cfg BoomFormalTop.v; do ln -sf "$here/$f" "$rvf/cores/boom/$f"; done
cd "$rvf/cores/boom"
rm -rf checks
python3 ../../checks/genchecks.py
make -C checks "$@"
