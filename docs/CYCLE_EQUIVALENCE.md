# Cycle-equivalence harness

This regression gate compares observations from the current core with recordings
from `0cb0b69`, before replacing manual stage chains with Spinal's pipeline API.
All new instrumentation is in the test tree or the existing optional Konata probe.
The probe is off by default and does not drive the core.

## Run

```sh
source env.sh
export PATH=/opt/homebrew/opt/riscv-gnu-toolchain/bin:$PATH
tests/vm/build.sh
# Build gem5/bench/build/bare/*.riscv using gem5/bench/Makefile, or supply BENCH_DIR.
EQ_MODE=record EQ_SET=quick scripts/cycle-equivalence.py
EQ_MODE=check  EQ_SET=quick scripts/cycle-equivalence.py
EQ_MODE=record EQ_SET=full  scripts/cycle-equivalence.py
EQ_MODE=check  EQ_SET=full  scripts/cycle-equivalence.py
```

Record refuses to overwrite a golden set. Use a fresh `EQ_GOLDEN` directory for an
intentional new reference. Failed recordings stay in a staging directory and are
never published as a complete set. Golden data is local and ignored by Git.
Check defaults to `quick`; missing goldens, incompatible inputs, zero selected
cases, simulation failure, timeout and any trace difference all exit nonzero.

| Environment | Default | Meaning |
|-------------|---------|---------|
| `EQ_MODE` | `check` | `record` or `check` |
| `EQ_SET` | `quick` | `quick` or `full` |
| `EQ_PRESETS` | `small,medium,large,mega,giga` | Comma-separated presets |
| `EQ_FILTER` | `.` | Python regex searched against `isa--NAME`, `vm--NAME`, `bench--NAME` |
| `EQ_PARALLELISM` | `2` | Maximum concurrent preset JVMs; each preset compiles once |
| `EQ_MEM_LATENCY` | `suite` | Existing spec timing: seeded random ISA/VM, fixed 20 for benchmarks; `random` or nonnegative N overrides every case |
| `EQ_SEED` | `1` | Base seed; each case adds the first 32 SHA-256 bits of its ID, modulo 2^31 |
| `EQ_GOLDEN` | `golden` | Golden root; sets are separated by test set and memory mode |
| `EQ_ELF` | unset | Run one ELF instead of the named test set |
| `EQ_MAX_CYCLES` | ISA/VM 200000, benchmark 50000000 | Override the simulation limit |
| `EQ_CPUS_PER_WORKER` | host CPUs / parallelism | JVM processor budget; Spinal may independently use all CPUs for C++ builds |
| `RISCV_TESTS` | `../ref/riscv-tests/build/isa` | Existing ISA binaries |
| `BENCH_DIR` | `gem5/bench/build/bare` | Existing benchmark binaries |

Use identical options for record and check. A filtered check can select a subset
of a complete recording. For example:

```sh
EQ_PRESETS=small EQ_FILTER='isa--rv64ui-p-beq$' scripts/cycle-equivalence.py
EQ_MODE=record EQ_MEM_LATENCY=20 EQ_GOLDEN=golden/fixed scripts/cycle-equivalence.py
```

The entry point compiles Scala through sbt once, then starts one JVM per preset.
Each uses `BoomSim.compile(konata = true)` and its own
`simWorkspace/equivalence/<preset>/` root, isolating Spinal's JVM-local
`tmp/job_N` paths as well as its model/cache directories. `target/equivalence/<run>/` holds the
compile log, per-preset simulator logs, job lists, check traces, and `report.json`
with wall time and per-preset results. No dependencies are added.

## Golden files

A complete set has `golden/<quick|full>/<suite|random|fixed-N>/manifest.json`, plus a
folder `<preset>/<case>/` containing:

- `case.json`: trace schema, ELF SHA-256, actual seed, memory latency and cycle limit.
- `commit.tsv.zst`: every architectural trace-valid instruction, plus exception and
  interrupt events, with cycle, retire slot, PC, raw instruction, destination type
  and register, written data, privilege, cause and tval. The existing core trace is
  one cycle behind ROB commit; destination metadata is delayed to match it.
- `tilelink.tsv.zst`: every fired A/B/C/D/E beat at the merged simulation memory
  port, with cycle and every elaborated payload field. Enums use their wire
  encoding. Multibeat transfers are never collapsed into transactions.
- `control.tsv.zst`: every sampling cycle's redirect valid/flush/target, ROB rollback,
  branch mispredict/ROB index, D-cache s2 valid/hit/nack/type, and LSU nack valids.
  Inactive payloads are marked `-`. These explicit values serve the optional
  internal-state observation without hiding differences behind a digest.
- `konata.log.zst`: stage transitions and flushes, including wrong-path uops, using
  the existing tracer. Raw instruction labels avoid dependencies on objdump text.
- `result.tsv.zst`: exact sampled duration, pass/fail result and Konata tracking mismatch count.

If `zstd` is unavailable, the same files use `.gz`. Comparisons stream decompressed
text, including EOF, and report the first differing line and both cycle numbers
for each differing file. Konata cycles are reconstructed from its `C`/`C=` cursor.
Cycles start at zero on the first `onSamplings` callback, as in Konata. The existing
10-cycle setup and 100-cycle completion polling remain unchanged; observations
continue through the final polling interval, including any post-tohost activity.
The manifest records the source revision, tracked diff hash, Java/Verilator
versions, selected cases/presets and recording runtime. Check validates input
contracts before compilation; source changes are expected during refactoring.

## Workloads and coverage

Quick selects 47 ISA tests across p/v environments: branches, direct/indirect
jumps, loads/stores and ordering, fence.i, integer multiply/divide, AMO/LR-SC,
single/double FP, compressed instructions, CSRs, traps, virtual memory and bit
manipulation. It also runs every directed VM test (currently `ae_ptw`) and
`median`, `qsort`, `multiply`.

Full selects exactly the 11 suite prefixes and p/v environments used by
`RiscvTestsSpec`, with its three exclusions: misaligned data tests and
`rv64mi-p-instret_overflow`. Currently this is 321 ISA tests, one VM test and all
nine benchmark ELFs. It runs every case on every selected preset. The concrete
case list and seeds are preserved in each golden set.

This is bounded simulation evidence, not formal equivalence or an ISA oracle.
It does not observe every internal register, cache array, stalled TileLink cycle,
pre-fetch-buffer uop or analog/hardware behavior. TileLink is observed at the
merged memory port, not each tile-side client. Invalid internal payloads are
masked. Cached store address/data that never reach TileLink are not directly
compared, nor are complete cache contents; unused payload fields on a fired bus beat are still compared verbatim.
The existing Konata reconstruction can lose tracking; its mismatch count is
recorded, not silently treated as proof of complete uop coverage. Commit privilege
and trap PC/data inherit the timing conventions of the existing core trace.
Only the chosen ELF inputs, memory timing, seed, presets, and finite run duration
are covered. Tests do not add interrupt injection beyond BoomSim's existing platform.

## Reference-run observations

An exploratory run with `EQ_MEM_LATENCY=random` hit `lsu:L1241` on
`large/bench--qsort` and `giga/bench--qsort` with seed 975830445. A separate
large-only rerun with isolated scratch paths reproduced `lsu:L1241` at simulation
time 501030. This was the reference core, with only observation ports added.
The failed recordings were not published. The default
`suite` timing follows `BenchmarksSpec` (fixed 20 cycles for benchmarks) and
`RiscvTestsSpec`/`VmTestsSpec` (seeded random memory). Explicit timing overrides
remain useful for separate stress runs; they are not assumed to pass every ELF.

## Measured validation

On this 10-CPU Apple Silicon machine with 32 GiB RAM, Java 21.0.7 and Verilator
5.049, using parallelism 2, the isolated quick recording completed all 255
ELF/preset runs in 823.871 seconds (13m44s). This includes Scala compilation,
fresh elaborations, native model builds, simulation and compression. Every run
passed and every Konata tracking-mismatch count was zero. The compressed quick
set occupies about 226 MB. A fresh-JVM quick replay checked all 255 runs with
zero differences in 621.964 seconds (10m22s). The standalone branch recording
also matched its suite recording in every file, checking test-order independence
for that case. Full recording completed all 1,655 runs in 1395.841 seconds
(23m16s), with every result passing and zero Konata tracking mismatches. Full
replay checked all 1,655 runs with zero differences in 1400.418 seconds
(23m20s). Native model caches were warm for the replay and full runs; elaboration
was repeated for every preset in every invocation.

## Timing-change detection

A temporary `m2sPipe()` on the frontend's I-cache D response added one register
stage. Checking `small/isa--rv64ui-p-beq` against the quick golden exited 1:

- `tilelink.tsv`, line 24: golden cycle 154, actual 155.
- `commit.tsv`, line 3: golden cycle 157, actual 159, with the same PC/instruction/write data.
- `control.tsv`, line 161: different D-cache s2 activity at cycle 159.
- `konata.log`, line 32: golden cycle 148, actual 150.

Both runs reported `Pass`, 1910 sampled cycles, and zero tracking mismatches. Thus
the program's pass result and the completion polling count alone missed this
change. The frontend source was restored byte-for-byte to `0cb0b69`; a new check
then passed with zero differences in 20.4 seconds. The temporary change is not
part of the implementation. Its patch and failing/passing reports remain under
`target/equivalence/` locally.

The runner compares completed Fail/Timeout traces before rejecting those runs.
Compression/comparison exceptions terminate the preset's owned process group,
so an unread output pipe cannot leave a simulator running while error handling
waits for it. Six comparator/cleanup regressions pass, covering cycle shifts,
beat data, EOF, gzip, zstd cursor handling, and early error cleanup.

An explicit `EQ_MEM_LATENCY=20` branch record/check also passed. The existing
`RiscvTestsSpec` branch smoke passed with Konata/probes disabled. CLI checks reject
an empty filter, invalid preset, changed seed, missing selected golden case, and
attempted golden overwrite before simulation. The reference ISA ELFs were reused
from `../ref/riscv-tests/build/isa`, the nine benchmark ELFs were copied from the
existing main checkout's bare build, and the VM ELF was built in this worktree;
the recorded SHA-256 contracts pin those exact inputs.
