# spinal-boom

Follow the workspace rules in `../AGENTS.md`. This worktree is a faithful SpinalHDL
1.15.0 port of BOOM v4; start with `PORTING_NOTES.md` and `docs/ARCHITECTURE.md`.
Keep simulation probes optional and off by default. Do not infer architectural or
silicon correctness from agreement with a recorded simulation.

## Simulation and tests

Run from this repository after `source env.sh`. The RISC-V toolchain is at
`/opt/homebrew/opt/riscv-gnu-toolchain/bin`. Tests report through `tohost`.

| What | Command | Options (env) |
|------|---------|---------------|
| riscv-tests ISA suite | `sbt "testOnly boom.sim.RiscvTestsSpec"` | `RISCV_TESTS_FILTER`, `_CONFIG`, `_ENVS`, `_SEED`, `_VERBOSE`, `_WAVE`, `_RVFI` |
| Directed VM tests | `tests/vm/build.sh`, then `sbt "testOnly boom.sim.VmTestsSpec"` | `VM_TESTS_VERBOSE` |
| Benchmarks | `sbt "testOnly boom.sim.BenchmarksSpec"` | `BENCH_FILTER`, `_CONFIG`, `_SEED`, `_MEM_LATENCY`, `_MAX_CYCLES` |
| Cycle equivalence | `EQ_MODE=record scripts/cycle-equivalence.py`, then `EQ_MODE=check scripts/cycle-equivalence.py` | `EQ_SET=quick\|full\|extra`, `EQ_PRESETS` (all five presets by default; the five `SimConfigs` extras for `extra`), `EQ_FILTER`, `EQ_PARALLELISM`, `EQ_MEM_LATENCY=suite\|random\|N`, `EQ_SEED`; see [the harness note](docs/CYCLE_EQUIVALENCE.md) |
| ACT4 | `act4/build.sh ../ref/act4`, then `sbt "testOnly boom.sim.Act4Spec"` | `ACT4_FILTER`, `_RVFI`, `_VERBOSE`, `_WAVE` |
| riscv-formal | `formal/run.sh ../ref/riscv-formal <check or -jN>` | See `formal/README.md` |

For long commands, wait at least 60 seconds between checks or redirect output to
a log and inspect it after the expected completion time. Do not spawn additional
agents when acting as an implementation sub-agent.
