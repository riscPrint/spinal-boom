# Fetch PMP rebase validation

Validation date: 2026-11-08. Worktree: `spinal-boom-pmp`. Branch: `fetch-pmp`.
The base is master `8e37b01323b8ff510e67cbaab8d6769eebe52be9`.
No push or merge was done. No other BOOM worktree was built or edited.

## Rebased commits

| Before | After | Change |
|---|---|---|
| `9a24662` | `ff02b98` | Check instruction PMP per parcel. |
| `b7adcf5` | `2b3162e` | Record the first regression results. |
| `d6aaf48` | `b7d5641` | Kill fetch for a denied first parcel. |
| `c0e517c` | `1eb12bd` | Add U-mode boundary checks. |
| `a898693` | `f8c2d94` | Record the denied-fetch regressions. |

## Conflict decisions

The first fix conflicted in `src/main/scala/boom/ifu/frontend.scala`.
Keep the refactored translation and replay stage payloads.
Insert `REPLAY_PMP_AE` at F0 and read it at F1.
Insert `PMP_AE` at F1 and read it at F2.
The existing StageLinks capture both payloads unconditionally.
Do not add a stage, payload hold, reset value, or ready path.
The generated small RTL confirms both unconditional register captures.

The denied-first-parcel fix conflicted in the same file.
Keep the F2 translation, miss, and replay reads from their stage payloads.
Keep the fix's fault expression, replay kill, and fault delivery without bytes.
The saved-half and per-instruction fault paths remain unchanged.
`git range-diff` shows only these stage conversions and the harness resolution
below in the two changed patches. The other three patches are unchanged.

`src/test/scala/boom/sim/BoomSim.scala` also conflicted.
Keep both optional arguments: `cycleTrace` and `forbiddenFetchRange`.
The first records gate observations. The second checks every valid TileLink
A-channel Get, including a backpressured Get, against the denied test range.

## Gate input contract

Use the existing `golden-39fb5f6` root. All runs use check mode.
The unfiltered quick invocation exits 1 before compilation with:

```text
EQUIV ERROR: case selection differs from golden manifest: missing=[], added=['vm--fetch_pmp']
```

The runner discovers VM cases from `tests/vm/*.S`. It applies `EQ_FILTER`
to the discovered cases and to the golden manifest. It requires equal case
sets, then checks ELF hash, seed, memory latency, cycle limit, and schema
for each selected preset and case before compilation.

Use this filter for all three sets:

```sh
export EQ_FILTER='^(?!vm--fetch_pmp$)'
```

This excludes only the new ELF. All existing golden cases remain selected.
No golden was created or replaced. No runner or comparison rule was changed.
The existing VM ELF hashes matched the golden input contracts.

All runs used `source env.sh`, the RISC-V GCC path, the supplied `BENCH_DIR`,
`EQ_MODE=check`, `EQ_PARALLELISM=2`, `EQ_CPUS_PER_WORKER=5`, default seed 1,
and suite memory timing. All gates and full suites ran under
`../risc-v-workstation/scripts/with-lock.sh boom`. Commands used `nice -n 10`.

| Set | Cases per config | Configs | Compared | Differences | Seconds |
|---|---:|---:|---:|---:|---:|
| Quick | 53 | 5 | 265/265 | 0 | 823.6 |
| Extra | 36 | 5 | 180/180 | 0 | 419.7 |
| Full | 333 | 5 | 1,665/1,665 | 0 | 1368.3 |

There is no existing-case difference to explain. Each check compares all five
observation files, including post-tohost activity. This is bounded simulation
evidence under the gate's observation contract, not formal equivalence.

`ae_ptw` is in all three sets. It includes PMP-denied page-table reads for
loads and stores, plus out-of-range page-table reads. These are data/PTW
access faults. They do not test denied instruction parcels. `fs_dirty` and
`instret_trap` do not test PMP denial. Full also includes `pmpaddr`, which
tests PMP CSR readback. The new `fetch_pmp` tests instruction-parcel denial
and runs separately in both VM suites, with the TileLink guard enabled.
ACT4 also exercises the fetch PMP boundary cases described in
`ACT4_TRIAGE.md`; the 15 repaired cases remain repaired.

Gate reports:

- `target/equivalence/20261108-024229-4012/report.json`
- `target/equivalence/20261108-025613-87200/report.json`
- `target/equivalence/20261108-030312-28933/report.json`

## Suite results

| Suite | Result |
|---|---|
| ACT4, small, seed 1 | 604 pass, 16 fail, 620 total |
| riscv-tests, small, RVFI, seed 1 | 321/321 pass |
| riscv-tests, medium, seed 1 | 321/321 pass |
| VM, small | 4/4 pass, TileLink guard enabled for fetch_pmp |
| VM, medium | 4/4 pass, TileLink guard enabled for fetch_pmp |
| Unit, pipeline, and elaboration specs | 55/55 pass, 14 suites |
| LdLdOrderingSpec, large qsort, seed 975830445 | 1/1 pass |
| Python gate harness tests | 6/6 pass |
| Large benchmarks, rebased branch | 9/9 pass |
| Large benchmarks, master 8e37b01 | 9/9 pass |

All selected Scala tests ran. No suite aborted. No test was canceled,
ignored, or pending. The ISA runner retains its three exclusions.
VM suites use their existing random-seed behavior; the logs record each seed.
ACT4's 16 failures remain failures, including the two SC debug assertions.

All 620 ACT4 ELF hashes matched the read-only `final-elf-manifest.json`.
The ELF directory was `../spinal-boom-act4/target/act4-triage/final-elfs/boom-rv64gc/elfs`.
The exact failure set matches the pre-rebase `target/fetch-pmp/act4.log`:
no added or removed name.

The failure names are:

- `priv/ExceptionsSv/ExceptionsSv_sv39_exceptions_Smode-00`
- `priv/ExceptionsSv/ExceptionsSv_sv39_exceptions_Umode-00`
- `priv/ExceptionsSvSm/ExceptionsSvSm_sv39_exceptions_mprv_S_Mmode-00`
- `priv/ExceptionsSvSm/ExceptionsSvSm_sv39_exceptions_mprv_U_Mmode-00`
- `priv/ExceptionsSvZalrsc/ExceptionsSvZalrsc_sv39_exceptions_Zalrsc_Smode-00`
- `priv/ExceptionsSvZalrsc/ExceptionsSvZalrsc_sv39_exceptions_Zalrsc_Umode-00`
- `priv/ExceptionsSvZalrscSm/ExceptionsSvZalrscSm_sv39_exceptions_Zalrsc_mprv_S_Mmode-00`
- `priv/Sm/Sm_mcsr_cntr-00`
- `priv/Sstvecd/Sstvecd-00`
- `priv/Sv/Sv_sv39_canonical_Smode-00`
- `priv/Sv/Sv_sv39_canonical_Umode-00`
- `priv/SvPMP/SvPMP_sv39_pmp_on_pa_Smode-00`
- `priv/SvPMP/SvPMP_sv39_pmp_on_pa_Umode-00`
- `priv/ZicntrU/ZicntrU-00`
- `rv64i/Zalrsc/Zalrsc-sc.d-00`
- `rv64i/Zalrsc/Zalrsc-sc.w-00`

## Large benchmark cycles

Use seed 1 and fixed 20-cycle memory latency on both revisions.
Both runs used the supplied benchmark directory.
The master run used this same worktree. It temporarily replaced only the six
changed production source files with their `8e37b01` versions.
Before the run, `git diff 8e37b01 -- src/main` was empty.
A `finally` block restored every saved file byte for byte.
The worktree was clean after restoration.

All nine CSV rows match exactly, including cycles, instret, and IPC.

| Benchmark | Cycles on both revisions |
|---|---:|
| dhrystone | 114149 |
| median | 4462 |
| memcpy | 9152 |
| multiply | 10007 |
| qsort | 225130 |
| rsort | 79090 |
| spmv | 22044 |
| towers | 4181 |
| vvadd | 1267 |

## Local evidence

`target/pmp-rebase/` holds the runner scripts, command logs, status log,
ACT4 manifest check, exact failure-set comparison, both benchmark CSV files,
benchmark comparison, and source-restoration check. `run.sh` records the
exact environment and test selections. `master-bench.py` records the
same-worktree master measurement and restoration procedure.
`git diff --check` passed after source restoration.
