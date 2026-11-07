#!/usr/bin/env bash
# Invoke under the machine-wide BOOM lock from this worktree.
set -u
source env.sh
export PATH="/opt/homebrew/opt/riscv-gnu-toolchain/bin:$PATH"
mkdir -p audit-results
: > audit-results/exit-codes.txt
failed=0
run() {
  local name="$1"; shift
  "$@" > "audit-results/$name.log" 2>&1
  local status=$?
  printf '%s %s\n' "$name" "$status" >> audit-results/exit-codes.txt
  if (( status != 0 )); then failed=1; fi
}
run semantics sbt 'testOnly rocketchip.ScopedRegisterSpec'
run small-rvfi env RISCV_TESTS_CONFIG=small RISCV_TESTS_RVFI=1 RISCV_TESTS_SEED=1 sbt 'testOnly boom.sim.RiscvTestsSpec'
run medium env RISCV_TESTS_CONFIG=medium RISCV_TESTS_SEED=1 sbt 'testOnly boom.sim.RiscvTestsSpec'
run vm-build bash tests/vm/build.sh
run vm sbt 'testOnly boom.sim.VmTestsSpec'
if test -d ../ref/act4/work/boom-rv64gc/elfs; then
  run act4 sbt 'testOnly boom.sim.Act4Spec'
fi
run large-bench env BENCH_DIR=/Users/mahir/fun/saviour/risc-v-workstation/examples/boom/out/arch/bench BENCH_CONFIG=large sbt 'testOnly boom.sim.BenchmarksSpec'
exit "$failed"
