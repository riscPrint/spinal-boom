# gem5 for architecture exploration

gem5 is a fast, configurable model of an out-of-order CPU. We use it to try
microarchitecture ideas (queue sizes, widths, predictor and cache changes, the
future matrix scheduler) in minutes, before spending RTL time on them. The RTL
(this repo, and upstream Chisel BOOM) is still the truth: gem5 tells you which
ideas are worth building, not what the RTL will score.

The setup lives in [`gem5/`](../gem5/README.md): the Docker environment,
the gem5 config (`gem5/configs/boom.py`), the benchmark builds, and the run and
calibration scripts. The commands are in that README; this document covers
the background. Nothing in `src/` depends on gem5. Keep it that way.

This is BOOM's instance of the platform's architecture-exploration tool (the
ARCH row in [ROADMAP.md](../../ROADMAP.md)). Today it is BOOM-specific; the
plan to make it work for any RISC-V core is in §9.

Checked against gem5 **v25.1.0.1** (April 2026, the latest release as of
October 2026). The gem5 Python API changes between releases, so re-check
parameter names in `src/cpu/o3/BaseO3CPU.py` and `src/cpu/o3/IQUnit.py` when
you upgrade.

---

## 1. What gem5 is good for here, and what it isn't

Good for:

* **Relative trends.** "ROB 32 → 64 gives +X% IPC on these programs, ALU queue
  8 → 16 gives almost nothing." Sweeps that would each need a Verilator rebuild
  run as parallel gem5 jobs.
* **Bigger workloads.** gem5 runs about 0.1–1 MIPS in detailed O3 mode,
  orders of magnitude faster than the Verilated core. It can also boot Linux in
  full-system mode.
* **Finding the bottleneck.** gem5 stats break down stalls by cause (ROB full,
  IQ full, LSQ full, rename stalls, mispredicts, cache misses), which tells you
  what to change.

Not good for:

* **Absolute IPC or cycle counts.** gem5's O3 model is a generic out-of-order
  core, not BOOM. Published gem5-vs-RTL validations of RISC-V cores show
  per-benchmark cycle errors of tens of percent before calibration, mostly from
  branch prediction and the memory system.
* **Correctness.** Use ACT4, Sail and riscv-formal for that
  ([ARCHITECTURE.md §18](ARCHITECTURE.md)).
* **Checking that the port matches upstream.** Compare against upstream Chisel
  BOOM in Verilator. That comparison is cycle-exact; gem5's isn't.

Rule of thumb: an idea needs a clear win in gem5 before it is worth building,
and a gem5 win only counts once the RTL reproduces it.

---

## 2. Installing on this machine (macOS, Apple silicon)

gem5 runs natively on macOS. `gem5/build.sh` (on the host) clones v25.1.0.1
into `ref/gem5` and builds `ref/gem5/build_native/RISCV/gem5.opt`; `run.sh`
and `calibrate.sh` use it. No gem5 source patches.

* **Dependencies:** Xcode's clang (gem5 warns that clang 21 isn't on its
  tested list; it works), Homebrew `python@3.14` (gem5 embeds Python), scons in
  a venv (`~/opt/gem5-venv`, made by `build.sh`), and Homebrew `gperftools`,
  `protobuf`, `libpng`, `pkgconf`. `build.sh` prints the `brew install` line
  for anything missing.
* **Build time:** about 9 minutes at -j8 on this Mac. Later builds are
  incremental. gem5 only accepts a build directory under `build/` or one set
  up with `scons defconfig`, so `build.sh` runs defconfig for `build_native/`
  once.
* **Same results as Docker** in bare mode, to the cycle. The native run of
  the whole benchmark set takes about 2 seconds.
* Build `gem5.opt` for normal runs. `gem5.debug` is much slower; build it only
  when you need gdb inside gem5. `gem5.fast` is a little faster but drops
  `--debug-flags` tracing, which you need while calibrating; use it for big
  sweeps afterwards.

**Docker** (`gem5/Dockerfile`, `gem5/docker.sh`, Ubuntu 24.04) is still used
for one thing: building the benchmarks with GCC 13 and picolibc.
`calibrate.sh` only starts it when the binaries are out of date. The host's
Homebrew GCC 15 has no picolibc, and switching compilers changes the binaries
(and so needs an RTL rerun). The Linux build of gem5 in the container
(`ref/gem5/build/RISCV`) still works on any Linux host. gem5's official images
(ghcr.io/gem5/...) refused pulls here, probably because of stale ghcr.io
credentials, so the image is built locally.

---

## 3. Getting programs to run in gem5

gem5 runs the **same bare-metal ELFs as the RTL** (`gem5/bench/build/bare/`,
`run.sh -B`), in gem5's bare-metal full-system mode. `boom.py` switches to it
when the ELF defines `tohost` and `tohost_exit`. The CPU, caches and memory
are the same objects as in SE mode. How it works, all in `boom.py`, no gem5
C++ changes:

* **Start:** `RiscvBareMetal` loads the ELF and resets to its entry
  (0x80000000, M-mode). RAM is 512 MiB from 0x80000000, as in `test.ld`.
* **Exit:** gem5 has no HTIF. A `PcCountTracker` stops the simulation when
  `tohost_exit` starts; the script flushes the caches and reads `tohost` from
  guest memory (exit code is `tohost >> 1`). A device on the `.tohost` page
  would need new C++, because the ELF loader writes it in 64-byte chunks.
* **ROI:** the same tracker fires at each `setStats` call: the first resets
  the stats, the second dumps them. After exit, `bench_stats` (the program's
  own `mcycle`/`minstret` deltas, as on the RTL) goes to `bare.json`.
  `summarize.py` takes cycles and instret from there, and mispredicts and
  misses from the stats dump. The stats window is 5 instructions shorter than
  the program's window; cycles match.
* **Unmapped addresses:** wrong-path loads can reach addresses nothing
  responds to (there is no translation in M-mode). A `BadAddr` responder on
  the memory bus answers them, as gem5's own full-system boards do.
* **ISA:** gem5 always decodes Zba/Zbb/Zbs. Its `misa` also reports V and
  Zicbom/Zicboz, which BOOM lacks; harmless here, and `--set` can turn V off.
* **Not handled yet:** programs that write `tohost` without calling
  `tohost_exit` (the riscv-tests ISA tests); `printf` output (dropped).

Result: ROI instruction counts match the RTL exactly on all 9 benchmarks, and
`mm` runs (`gem5/CALIBRATION.md`). `calibrate.sh` uses this mode.

The benchmarks use riscv-tests' own runtime, patched
(`gem5/bench/rt/patch_syscalls.sh`) so that `printf` makes no HTIF calls and
`setStats(0)` stores the ROI cycle and instruction counts to `bench_stats`.
On the RTL, `BenchmarksSpec` picks those stores up from the D$ store stream.

Things that matter for a fair comparison:

* **Check the `insts ratio` column.** In bare mode it should be 1.000; any
  other value means the two sides ran different code.
* **Deterministic RTL memory.** Spinal's TileLink memory model randomizes
  latency and sends unnecessary probes (good for testing, bad for
  measurement). `BenchmarksSpec` uses a fixed latency (`BENCH_MEM_LATENCY`,
  default 20) and only necessary probes. gem5 uses the same latency
  (`--mem-latency`).
* The riscv-tests ROIs are short (1K–250K cycles). Add CoreMark and Embench
  for longer runs. SPEC is only practical in gem5, with SimPoints.

**SE mode (older, due for removal).** `gem5/bench/Makefile` also builds the
benchmarks as static Linux binaries (`build/linux/`, run without `-B`) for
gem5's syscall-emulation mode, where `setStats` becomes m5 ops
(`gem5/bench/rt/linux_rt.c`). It needs the Linux cross-compiler, can't read
`mcycle` (so no `mm`, and dhrystone uses `clock()`), and runs glibc's string
functions instead of riscv-tests', so instruction counts differ (dhrystone
0.90×). Its cycle counts also depend on the binary's host path: glibc's
startup reads `/proc/self/exe`, gem5 returns the host path, and the string's
length moves the heap (up to 11% on dhrystone between `/work/...` in Docker
and `/Users/...` natively). Use bare mode.

---

## 4. Mapping BOOM parameters onto gem5 O3

Start from `BaseO3CPU` (the RISC-V O3 CPU is `RiscvO3CPU`). The presets are in
`src/main/scala/boom/common/config-mixins.scala`. Every test and sim uses
`small`; `large` is the usual exploration baseline.

| BOOM (`BoomCoreParams`) | small | large | gem5 O3 parameter | Notes |
|---|---|---|---|---|
| `fetchWidth` | 4 | 8 | `fetchWidth` | BOOM counts 16-bit slots of a fetch packet; gem5 counts instructions. Use `fetchBufferSize` = `ICacheParams.fetchBytes` (8 / 16 bytes). |
| `numFetchBufferEntries` | 8 | 24 | `fetchQueueSize` | Instructions between fetch and decode. |
| `ftq.nEntries` | 16 | 32 | `numFTQEntries` with `decoupledFrontEnd=True` | The decoupled front end is new in 25.x. Check it works with RISC-V before relying on it. |
| `decodeWidth` | 1 | 3 | `decodeWidth`, `renameWidth`, `dispatchWidth`, `commitWidth` | BOOM's core width is the same value for all of these. |
| `numRobEntries` | 32 | 96 | `numROBEntries` | |
| `numIntPhysRegisters` | 52 | 100 | `numPhysIntRegs` | Both counts include the 32 architectural registers. gem5 errors out if the count is too small. |
| `numFpPhysRegisters` | 48 | 96 | `numPhysFloatRegs` | |
| `numLdqEntries` / `numStqEntries` | 8 / 8 | 24 / 24 | `LQEntries` / `SQEntries` | |
| `issueParams` (4 queues) | MEM 2×8, UNQ 1×8, ALU 1×8, FP 1×8 | MEM 2×16, UNQ 1×16, ALU 3×16, FP 1×24 | `instQueues` = 4 × `IQUnit(numEntries, fuPool)` | See §5. |
| issue widths, summed | 5 | 7 | `issueWidth` | Per-queue width comes from the FU count in that queue's pool. |
| `maxBrCount` | 8 | 16 | none | gem5 has no limit on in-flight branches. See §6. |
| `imulLatency` | 3 | 3 | `IntMult` `opLat` | |
| div | iterative, early-out | same | `IntDiv` `opLat`, `pipelined=False` | BOOM's latency depends on the data; pick an average. |
| FPU `sfma/dfmaLatency` | 4 / 4 | 4 / 4 | `FloatMultAcc` / `FloatAdd` / `FloatMult` `opLat` | |
| I$ | 16 KiB, 4-way | 32 KiB, 8-way | L1I `Cache(size, assoc)` | Hit latency 2 (`ICacheParams.latency`). |
| D$ | 16 KiB, 4-way, 2 MSHRs | 32 KiB, 8-way, 4 MSHRs | L1D `Cache(size, assoc, mshrs)` | MSHR count strongly affects memory-bound code; copy it exactly. |
| D-TLB / I-TLB | 8 / 32 ways | 16 / 32 ways | `RiscvTLB(size)` on the MMU | Only matters for programs that turn on virtual memory. |
| Branch predictor | TAGE-L (below) | same | `branchPred` | See §6. |

Branch predictor (`withTAGELBPD`), for setting up gem5's `TAGE`/`LTAGE`:

* TAGE: 6 tables with history lengths 2, 4, 8, 16, 32, 64. gem5's TAGEBase
  spaces history lengths geometrically, so `nHistoryTables=6, minHist=2,
  maxHist=64` gives the same lengths. Tag widths are 7, 7, 8, 8, 9, 9
  (`tagTableTagWidths`, which also includes the base table). Table sizes are
  128 / 128 / 256 / 256 / 128 / 128 sets, but BOOM's tables are per fetch
  packet, so treat `logTagTableSizes` as approximate.
* Base predictor BIM: 2048 sets. Loop predictor: 4 ways × 16 sets. BTB: 128 sets ×
  2 ways, plus a 24-entry fully associative micro-BTB. RAS: 32 entries
  (`numRasEntries`).

---

## 5. Issue queues

gem5 v25.1 added distributed issue queues (`instQueues`, PR #2652). Each
`IQUnit` has its own `numEntries` and its own `FUPool`. An instruction can only
go into a queue whose pool can execute it. That lets gem5 match BOOM's four
queues:

| BOOM queue | What goes in (`decode.scala:480`) | gem5 `IQUnit` FU pool |
|---|---|---|
| `IQ_MEM` | loads, store address and data (`FC_AGEN`, `FC_DGEN`) | memory read/write ports, count = MEM issue width |
| `IQ_UNQ` | mul, div, CSR, int→fp (`FC_MUL/DIV/CSR/I2F`) | `IntMultDiv`, `System_Unit`, conversions; count 1 |
| `IQ_ALU` | integer ALU and branches (`FC_ALU`) | `IntALU`, count = ALU issue width |
| `IQ_FP` | FPU, FP div/sqrt, fp→int (`FC_FPU/FDV/F2I`) | `FP_ALU`, `FP_MultDiv` |

Differences to keep in mind:

* gem5 places each instruction in the **first** queue in the list that has a
  free entry and can execute it (`InstructionQueue::findIQ`). If two pools
  overlap, the order of the list decides placement. Keep the pools disjoint so
  placement matches BOOM's decode.
* BOOM can send one uop to more than one queue (`iq_type` is a bitmask), and it
  splits stores into address and data parts. gem5 puts each instruction in
  exactly one queue.
* gem5 has a single global `dispatchWidth`. BOOM's per-queue `dispatchWidth`
  limits have no direct equivalent.
* BOOM's age-ordered collapsing queues (`numSlowEntries`, column ALU issue) are
  not modelled. gem5 issues ready instructions roughly oldest first.
* Memory dependence prediction differs: gem5 uses store sets (`SSITSize`,
  `LFSTSize`), while BOOM issues loads speculatively and replays on ordering
  failures. Expect gem5 to replay fewer loads; if load-store-heavy code
  disagrees with the RTL, look here first.

When you build the matrix scheduler, prototype its effect on wakeup, select and
age order in gem5 first. Keep in mind that gem5 models scheduling at a high
level, so a scheduler whose benefit is mostly timing (cycle time or fewer
stages) won't show up in gem5 at all.

---

## 6. Other model differences that move results

* **Branch mispredict penalty.** BOOM resolves branches late, in execute, then
  redirects through the FTQ. gem5's penalty comes from the stage delays
  (`fetchToDecodeDelay`, `decodeToRenameDelay`, `renameToIEWDelay`,
  `iewToCommitDelay`, `*ToFetchDelay`, ...). Measure BOOM's penalty on a
  microbenchmark with a known mispredict pattern, then set the gem5 delays to
  match. This calibration has the biggest effect on accuracy.
  In gem5 25.1, `commitToFetchDelay` must stay at 1: larger values deadlock
  the O3 CPU or panic in the issue queue. Use `fetchToDecodeDelay` and
  `iewToFetchDelay` instead.
* **Predictor timing.** BOOM predicts in stages (micro-BTB at F1, BTB/BIM at
  F2, TAGE at F3), and a later stage overrides an earlier one at the cost of a
  bubble. gem5 gives one prediction per fetch. Expect gem5 to look better on
  branchy code; `maxTakenPredPerCycle` and the decoupled front end help a little.
* **Branch tags.** `maxBrCount` (8 in small) stalls BOOM's decode when too many
  branches are in flight. gem5 has no such limit, so small configs will look
  too good on branchy code. If this matters, approximate it in gem5 (shrink the
  ROB or IQs for branch-heavy runs) or add a counter to gem5's rename stage.
* **Memory system.** The RTL sim has no L2. Each miss goes over TileLink to
  `SparseMemory` through Spinal's `MemoryAgent` with a fixed latency. Before
  you start exploring, give gem5 the same setup: L1s straight to `SimpleMemory`
  with the latency you measure in the RTL sim. Add an L2 and DRAM only once the
  baseline matches.
* **Prefetching.** `enablePrefetching` is off for small and large. Leave gem5's
  prefetchers off to match.
* **Serializing instructions.** CSR accesses, fences and AMOs may drain the
  pipeline differently. That matters for kernels with many CSR reads (e.g. tight
  `rdcycle` loops) but not much for normal code.

---

## 7. Workflow

1. **Calibrate once.** Set up a gem5 config matching `large` (§4–§6). Run the
   benchmark set on both the RTL (`BenchmarksSpec` with
   `BENCH_CONFIG=large`) and gem5. Tune the stage delays, memory latency
   and predictor until IPC per benchmark is within roughly 10–15% and the
   *ranking* of benchmarks matches. `gem5/calibrate.sh -c large` does the runs
   and prints the table. Write down the final numbers in `gem5/CALIBRATION.md`
   (gem5 version, what you changed, the IPC table).
2. **Explore.** Change one thing at a time from the calibrated baseline and
   compare against it, not against the RTL. Run sweeps in parallel (gem5 is
   single-threaded per run).
3. **Confirm in RTL.** Build the best one or two ideas in Spinal. Check that the
   RTL moves the same way. If it doesn't, the gem5 model is missing whatever
   limits the idea; fix the model before trusting it again.
4. **Re-calibrate** after any big RTL change, and after upgrading gem5.

Stats to watch (in `m5out/stats.txt`; names change between versions, so grep):
IPC/CPI, `rob.*Full`, `iq.*Full` / `iewIQFullEvents`, `lsq.*Full`, rename
stall reasons, `branchPred.condIncorrect` and mispredicts per kilo-instruction,
L1 miss rates and MSHR occupancy.

---

## 8. Later options (not set up)

* **SimPoints.** Use gem5 to pick short, representative pieces of long runs
  (SPEC, Linux workloads). gem5 itself handles this easily. Feeding those
  pieces into the RTL needs a way to load architectural state into BOOM, which
  is substantial work.
* **Co-simulating the RTL in gem5.** Running the Verilated core inside gem5's
  memory system isn't supported in mainline gem5 (there are research forks).
  It would also need a bridge from TileLink-C (with Probe/Release) to gem5's
  packets. Not worth it for exploration.
* **Full-system Linux.** gem5 can boot RISC-V Linux (`RiscvLinux` workload).
  This is useful for OS-heavy workloads and gives you nothing for core-only
  studies.

---

## 9. Where this is going

The aim is a tool that does what Arm Fast Models and Synopsys Platform
Architect do for us, for any RISC-V core, not only BOOM. The design:

* **Code does everything deterministic:** install, check a core's
  description, generate the gem5 config, run the RTL and gem5, collect and
  normalize stats, run measurement microbenchmarks, fit settings, score and
  report. Results are cached by a fingerprint of the RTL, description and
  settings.
* **An agent does the judgment calls, through an MCP server** that wraps the
  same commands: writing a new core's run adapter, writing and correcting its
  description, working out why a mismatch remains after fitting, and
  extending gem5 when it can't express a feature. Acceptance thresholds and
  held-out scoring stay in code.
* **Per core:** a description file (ISA string, in-order or out-of-order,
  widths, windows, issue queues, unit latencies, predictor, caches) and one
  run command (ELF in, JSON cycles, instret and event counters out). BOOM's
  presets in `gem5/configs/boom.py` become description files, and
  `BenchmarksSpec` becomes BOOM's run command.
* **Calibration in two steps:** microbenchmarks measure parameters on the
  RTL (load-to-use latency, mispredict penalty, unit latencies, effective
  window size), then an optimizer fits the rest against the RTL's stats and is
  scored on workloads it never saw.
* **One binary per benchmark** on both sides (§3, done).
* **Native, no Docker**, shipped in the platform's tool bundle.
* **gem5 extensions** for what its O3 model lacks and M6, Zen 5 and C1-Ultra
  class cores have: decoupled front end with large BTBs, micro-op cache,
  memory renaming, very wide rename.

The tasks, in order, are under ARCH in [TODO.md](../../TODO.md).

---

## References

* gem5 releases and notes: <https://github.com/gem5/gem5/releases>
* O3 parameters: `src/cpu/o3/BaseO3CPU.py`, `src/cpu/o3/IQUnit.py`,
  `src/cpu/o3/FuncUnitConfig.py`; predictors: `src/cpu/pred/BranchPredictor.py`
* Distributed IQ (PR #2652, v25.1): `InstructionQueue::findIQ` in
  `src/cpu/o3/inst_queue.cc`
* gem5 accuracy against RISC-V RTL: "Towards Accurate Performance Modeling of
  RISC-V Designs", <https://arxiv.org/abs/2106.09991>
