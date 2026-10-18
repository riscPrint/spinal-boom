#!/usr/bin/env bash
# Generate BoomFormalTop.v and run riscv-formal checks on it.
#
#   formal/run.sh [riscv-formal dir] [make args...]
#
# e.g. formal/run.sh ../ref/riscv-formal -j4            # all checks
#      formal/run.sh ../ref/riscv-formal cover_ch0      # just the cover check
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
rvf="$(cd "${1:-$root/../ref/riscv-formal}" && pwd)"
shift || true

cd "$root"
source env.sh
sbt -batch "runMain boom.system.GenFormal formal"

# riscv-formal expects cores/<name>/ inside its tree. genchecks.py derives the core
# name and tree from the physical cwd, so link the files, not the directory.
mkdir -p "$rvf/cores/boom"
for f in wrapper.sv checks.cfg BoomFormalTop.v; do ln -sf "$here/$f" "$rvf/cores/boom/$f"; done
cd "$rvf/cores/boom"
rm -rf checks
python3 ../../checks/genchecks.py
make -C checks "$@"
