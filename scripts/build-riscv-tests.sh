#!/usr/bin/env bash
# Build ISA ELFs through the workstation. Extra options pass through unchanged.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
build="${RISCV_TESTS_BUILD:-$root/target/verification/riscv-tests}"
exec "${RVW:-rvw}" regress riscv-tests-build \
  --out "$build" --elf-dir "${RISCV_TESTS:-$build/isa}" --xlen 64 "$@"
