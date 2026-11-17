#!/usr/bin/env bash
# Build ISA ELFs through the workstation. Extra options pass through unchanged.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
exec "${RVW:-rvw}" regress riscv-tests-build \
  --out "${RISCV_TESTS:-$root/target/verification/riscv-tests/isa}" --xlen 64 "$@"
