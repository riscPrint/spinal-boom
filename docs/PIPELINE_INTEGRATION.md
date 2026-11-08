# Pipeline integration at master 39fb5f6

## Source and scope

Merge commit `f4311d5fbec2695f6556185d60102107ee378fe2` joins
master `39fb5f62d9906e41bbf76580d03cbef4e5b62d27` and pipeline step 7
`fd3d2234dd8f93f46880cfac8317765985e85380` on `pipeline-integrate`.
No push or merge into master was done. No other work checkout was changed.

## Conflict decisions

| File | Resolution |
|---|---|
| `.gitignore` | Keep the audit output rule and the local golden and Python cache rules. |
| `PORTING_NOTES.md` | Keep the conditional-register audit and all pipeline conventions and reports. |
| `src/main/scala/boom/exu/core.scala` | Keep master's `countedRetire` expression. Delay its popcount through the one-stage `FixedPipeline`. Keep the converted exception stage. |
| `src/test/scala/boom/sim/RiscvTestsSpec.scala` | Use `SimConfigs` so the five presets and extra gate configs remain available. Keep master's test exclusions. |

The FP-load FS Dirty update and the LSU load-load replay merged without a
conflict. The core diff against step 7 contains both ACT4 fixes. The LSU diff
contains the replay. The matrix issue-unit files and config changes match
master byte for byte. `rob.scala`, frontend files, and the other harness files
merged without a conflict.

## Golden source

The golden worktree is `../spinal-boom-golden`. Its harness commits are
`8d37d1f` and `2ef945a`, based on master `39fb5f6`. The scripts and test
instrumentation come from `dc80150` and `b3498b1`. No later harness-only fix
was present in the relevant path history on `pipeline-api`.

`dc80150` also added fields to the production simulation-top probe. Those
fields now live in test-only `CycleObservations`, in both worktrees. The test
compile adds output ports with explicit names. It does not add a core driver.
The original Konata probe remains optional. Before recording and after all
gates, `git diff 39fb5f6 -- src/main` in the golden worktree was empty.

The golden root is `/Users/mahir/.cache/spinal-boom/golden-39fb5f6`.
Record and check used parallelism 2, 5 CPUs per worker, suite memory timing,
and the default base seed 1. The benchmark directory was
`/Users/mahir/fun/saviour/risc-v-workstation/examples/boom/out/arch/bench`.
Both worktrees built the same three VM programs with `tests/vm/build.sh`.
All gates and suites used `../risc-v-workstation/scripts/with-lock.sh boom`.

The initial launch found a missing executable bit on the copied golden gate
script. It exited 126 before simulation. Commit `2ef945a` fixes the mode.
The restarted recordings completed. No failed recording was published.

## Gate results

| Set | Record | Check | Differences |
|---|---:|---:|---:|
| Quick | 265/265 | 265/265 | 0 |
| Extra | 180/180 | 180/180 | 0 |
| Full | 1,665/1,665 | 1,665/1,665 | 0 |

Master has three VM programs rather than one. This adds ten cases per set
relative to the earlier step 7 totals. No golden or comparison rule was
changed to accept a refactor difference.

The record reports are in the golden worktree under `target/equivalence/`:
`20261108-004414-88248`, `20261108-005653-88346`, and
`20261108-010320-86484`. The check reports are in this worktree under
`target/equivalence/`: `20261108-012527-74480`,
`20261108-013834-47001`, and `20261108-014531-90707`.
Copies of all six reports and `gate-results.json` are in `target/integration/`.

See `CYCLE_EQUIVALENCE.md` for input contracts, trace coverage, and the
re-record procedure after intentional behavior changes.

## Suite results

| Check | Result |
|---|---:|
| Unit and elaboration specs, 13 suites | 52/52 pass |
| Small riscv-tests with RVFI | 321/321 pass |
| VM tests | 3/3 pass |
| ACT4, private manifest ELFs, seed 1 | 589 pass, 31 fail, 620 total |
| LdLdOrderingSpec | 1/1 pass |
| IssueMatrixSpec | 3/3 pass |
| Harness Python unit tests | 6/6 pass |

All selected tests ran. No suite aborted. No test was canceled, ignored or
pending. The ISA runner keeps its three existing exclusions. The 52 specs
include the three master `ScopedRegisterSpec` tests in addition to the 49
step 7 specs. `LdLdOrderingSpec` ran large qsort with randomized memory timing
and seed 975830445.

ACT4 used the read-only directory
`../spinal-boom-act4/target/act4-triage/final-elfs/boom-rv64gc/elfs`.
All 620 ELF hashes matched `final-elf-manifest.json`. The 31 failing names
match `../spinal-boom-act4/target/act4-triage/regressions.log` exactly:
no added failure and no removed failure. These remain failures. The two SC
debug assertions remain included in that count.

Logs are in `target/integration/`: `specs.log`, `isa-rvfi.log`, `vm.log`,
`act4.log`, `ordering-matrix.log` and `harness-unit.log`.
`act4-comparison.json` records the exact failing names and set comparison.
`master-preservation.json` records the static matrix/config source checks.
Both worktrees passed `Test/compile`. `git diff --check` passed.

## Remaining pipeline work

The PTW L2 lookup is already converted. The TODO row that lists it as a
remaining chain is stale. Keep these separate from transaction stage chains:

- Independent unreset ROB-flush captures, whose distinct initial values matter.
- SRAM read ports and alignment registers.
- Freelist allocation selection and its state.
- PTW replacement snapshots and event flags.
- MSHR metadata waits, allocation selection and hazard counters.
- Queues, FSMs, pointers, counters, CAMs, rename storage and ROB storage.
- Assertion-only and trace observation delays.

The user retains the master fast-forward and commit-tour work. This branch
contains the architecture and gate documentation updates. Shared workspace
TODO and ROADMAP files were not changed from this isolated worktree.
