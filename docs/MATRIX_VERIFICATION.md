# Age-matrix issue verification

Date: 2026-11-07. Worktree: `spinal-boom-matrix`. Branch: `port-matrix-issue`.
Base: `fdd21f446f7511a1cfa478628b9052b4e737d064`.
Implementation: `9bc971e`. Partial selector fix: `5e4224b`.

## Results

| Check | Config | Result |
|---|---|---|
| RiscvTestsSpec, RVFI on, seed 1 | small-matrix-full | 321/321 pass |
| RiscvTestsSpec, RVFI on, seed 1 | medium-matrix-full | 321/321 pass |
| VmTestsSpec, rebuilt tests/vm ELFs | small-matrix-full | 1/1 pass |
| BenchmarksSpec, each of the four variants below | small | 3/3 pass per variant |
| IssueMatrixSpec, after fix | Six slots, two ports, both select modes; six-bit selection tree | 3/3 pass |
| BoomElabSpec, default small and medium tiles | Collapsing defaults | 1/1 test pass, two tiles |
| MatrixElaborate | Six slots, full/partial, banked/unbanked | Four elaborations pass |

The ISA tests retain the existing exclusions. RVFI uses the existing RV64IMC
monitor. It does not check every tested instruction against an ISA model.
The partial selectors were checked by the unit regression and benchmarks.
The full ISA suites used full selection. Banked variants were elaborated;
no banked core program run was added. These results do not prove all configs.

## IPC

Each benchmark uses seed 1 and fixed memory latency 20. Every variant uses
the same existing ELF. All variants retired the same ROI instruction count
for each program. The values below are ROI counters, not whole-program counters.

| Program | Variant | Cycles | Instructions | IPC |
|---|---|---:|---:|---:|
| dhrystone | matrix-full | 206902 | 192025 | 0.9281 |
| dhrystone | collapsing-full | 206902 | 192025 | 0.9281 |
| dhrystone | matrix-partial | 206902 | 192025 | 0.9281 |
| dhrystone | collapsing-partial | 212782 | 192025 | 0.9024 |
| qsort | matrix-full | 269408 | 122916 | 0.4562 |
| qsort | collapsing-full | 269408 | 122916 | 0.4562 |
| qsort | matrix-partial | 268966 | 122916 | 0.4570 |
| qsort | collapsing-partial | 275378 | 122916 | 0.4464 |
| median | matrix-full | 6892 | 4000 | 0.5804 |
| median | collapsing-full | 6734 | 4000 | 0.5940 |
| median | matrix-partial | 6892 | 4000 | 0.5804 |
| median | collapsing-partial | 7016 | 4000 | 0.5701 |

Matrix IPC change relative to collapsing in the same select mode:

| Program | Full | Partial |
|---|---:|---:|
| dhrystone | +0.00% | +2.84% |
| qsort | +0.00% | +2.38% |
| median | -2.29% | +1.80% |

These are one-seed measurements on the small preset. They do not establish
performance on other presets or timing, area, or power in hardware.

## Upstream bug and deviations

The first unit run passed 2/3 tests. The partial matrix selector failed its
upstream assertion at `issue-unit-matrix:L185`, at simulation time 240.
It used age row `i` for candidate `j`. The fix uses row `j`, as the full
selector does. The final run passed 3/3. The assertion remains enabled.

`PORTING_NOTES.md` records the fix. It also records the recursive selection
tree in place of Chisel `Definition`/`Instance`, UInt age rows, and the
simulation preset suffixes. Slot behavior, dispatch wakeups, and the upstream
assertions are preserved. No default preset enables matrix issue.

## Commands and evidence

All Verilator builds and core runs used the shared `boom` lock. JVM work
used `-XX:ActiveProcessorCount=2`. A local `SPINAL_MAKE_CMD` wrapper limited
native builds to two jobs. Runs used `nice -n 10`.

Set `RISCV_TESTS_CONFIG=small-matrix-full` or `medium-matrix-full` and
`RISCV_TESTS_RVFI=1 RISCV_TESTS_SEED=1` for `testOnly boom.sim.RiscvTestsSpec`.
Run `tests/vm/build.sh`, then use `VM_TESTS_CONFIG=small-matrix-full` for
`testOnly boom.sim.VmTestsSpec`.

For `testOnly boom.sim.BenchmarksSpec`, set `BENCH_CONFIG` to each variant
in the IPC table. Set `BENCH_FILTER='^(dhrystone|qsort|median)$'` and
`BENCH_DIR=/Users/mahir/fun/saviour/risc-v-workstation/examples/boom/out/arch/bench`.
The ELFs already existed. No benchmark build was needed.

Use `testOnly boom.IssueMatrixSpec` for the regression and selection tree.
Use `testOnly boom.BoomElabSpec -- -z "default small and medium"` for defaults.
Use `Test/runMain boom.MatrixElaborate` for the four matrix elaborations.

Raw logs are in `target/matrix-verification/`: `unit.log` is the expected
pre-fix failure; `unit-final.log` is the final pass. Other logs are
`default-elab.log`, `isa-small.log`, `isa-medium.log`, `vm-small.log`, and
`bench-<variant>.log`. The logs and local run script are not committed.
The four benchmark CSVs are in `gem5/results/rtl-<variant>.csv`.

A separate short Icarus attempt failed before simulation because the installed
`iverilog-vpi` script has a malformed `CC` assignment. That result is a tool
failure. The final unit results use Verilator.

ELF SHA-256 values:

- `dhrystone`: `6f3f67fa54c24e2c08001f99bea13de9b36cc7118b87386fcd37eee1f33b26b3`
- `qsort`: `b83bb3d482f7ab4aef1810a1041dce0f3111a67ddd004b12391f8472bde69a6f`
- `median`: `51805061759314e70b8d86490061fee643c4640fb2210e441759bde08dd9b66d`
