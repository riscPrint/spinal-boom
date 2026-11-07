# BOOM v4 in SpinalHDL: architecture and port

This document explains how the out-of-order BOOM v4 core works, how this
repository expresses it in SpinalHDL, and how it is verified. It is meant to be
read alongside the code, in the order of the commit history. Section 1 maps
commits to sections; every later section names the files and signals it
describes, so you can open them as you read.

Names follow the code. `uop` is a micro-op (`boom.common.MicroOp`); "the small
config" is `BoomConfigs.small()`, the configuration every test and formal run
uses. Sizes quoted without qualification are for the small config.

Contents

1. Reading guide: commits and build points
2. The core at a glance
3. The Chisel-to-Spinal layer
4. The rocket-chip pieces BOOM uses
5. Life of an instruction
6. Frontend: fetch, prediction and the FTQ
7. Decode, branch tags and dispatch
8. Rename: four renamers
9. Issue: queues, wakeup and speculation
10. Register read and execution
11. Branch resolution and recovery
12. The reorder buffer
13. The load/store unit
14. Address translation: NBDTLB, I-TLB and PTW
15. The non-blocking data cache
16. CSRs, privilege, exceptions and interrupts
17. The tile and the simulation SoC
18. Verification
19. Upstream bugs fixed in the port
20. Known differences and limitations
21. File map

---

## 1. Reading guide: commits and build points

The history builds the port bottom-up: the support layer, then the core in
pipeline order, then verification, then the two bug fixes.

| # | Commit | Read with | Builds alone? |
|---|--------|-----------|---------------|
| 1 | build: sbt project | §3, `PORTING_NOTES.md` | yes |
| 2 | rocketchip: config and util support layer | §3, §4.1 | no (needs #5–9) |
| 3 | hardfloat: port of berkeley-hardfloat | §4.6 | no |
| 4 | rocketchip.tilelink: client metadata and edge helpers | §4.2 | no |
| 5 | rocketchip: ISA tables and core parameters | §4.1 | no |
| 6 | rocketchip: ALU, multiplier/divider and RVC expander | §10 | no |
| 7 | rocketchip: CSR file, PMP and breakpoints | §16 | no |
| 8 | rocketchip: TLB, PTW and HellaCache interfaces | §14 | no |
| 9 | rocketchip.tile: FPU | §4.6 | **yes**: the rocket-chip layer is complete |
| 10 | boom.common: parameters, micro-op, constants and utilities | §2, §5 | no (needs #11–18) |
| 11 | boom.ifu: branch predictor components | §6.3 | no |
| 12 | boom.ifu: frontend, I$, fetch target queue and fetch buffer | §6 | no |
| 13 | boom.exu: decode and rename | §7, §8 | no |
| 14 | boom.exu: dispatch, issue queues and register files | §7.4, §9, §10.1 | no |
| 15 | boom.exu: functional and execution units, FP pipeline | §10 (and the RVFI operand hooks, §18.3) | no |
| 16 | boom.exu: RVFI trace tracker (port only) | §18.3 | no |
| 17 | boom.exu: reorder buffer and core | §11, §12 (and the RVFI wiring, §18.3) | no |
| 18 | boom.lsu: NBDTLB, non-blocking D$, MSHRs and LSU | §13–15 | **yes**: the core is complete |
| 19 | boom: tile, config fragments and elaboration tests | §17 | yes |
| 20 | formal: riscv-formal RVFI monitors for simulation | §18.3 | yes |
| 21 | sim: Verilator harness and riscv-tests | §17.2, §18.2 | yes |
| 22 | formal: riscv-formal harness | §18.4 | yes |
| 23 | act4: riscv-arch-test (ACT4) configuration and runner | §18.5 | yes |
| 24 | lsu: keep misaligned and access-faulting AMO/SC out of the D$ | §19.1 | yes |
| 25 | lsu/tlb: honour the PTW's page fault in the NBDTLB | §19.2 | yes |
| 26 | docs: architecture and porting paper | this document | yes |
| 27 | rvfi: report no memory write for a failed SC | §18.3 | yes |
| 28 | docs: corrections from review | this document, `PORTING_NOTES.md` | yes |
| 29 | lsu/tlb: drop an NBDTLB refill raced by sfence.vma | §19.3 | yes |
| 30 | lsu/tlb: keep the PTW's ae_ptw in the NBDTLB | §19.4 | yes |

Within each layer the packages refer to each other in a cycle
(`rocketchip.config` → `rocketchip.tile` → `rocketchip.rocket` → `config`, and
`boom.common` ↔ `boom.ifu` ↔ `boom.exu` ↔ `boom.lsu`), so a layer compiles only
once its last commit is in. The split is for reading, not bisecting. Commits 18
onward each compile with `sbt Test/compile`.

The RVFI port is spread over three commits: the operand and branch hooks in the
execution units arrive with commit 15, the tracker in 16, and its wiring into
the core in 17. All of it is behind `enableRVFI` and absent from upstream.

Commit 18 contains the LSU and NBDTLB with upstream's behaviour (translated to
Spinal as §3 describes); commits 24, 25, 29 and 30 change that behaviour. `git show <commit>` on each shows the fix in
isolation, and reverting one restores upstream behaviour.

---

## 2. The core at a glance

BOOM (Berkeley Out-of-Order Machine) is a superscalar, out-of-order RV64GC core.
Version 4, the one ported here, differs from the BOOM v3 described in most
papers in several ways that this document returns to:

- **Four issue queues**: memory (`IQ_MEM`), "unique" (`IQ_UNQ`: multiply,
  divide, CSR, integer-to-FP), ALU (`IQ_ALU`) and FP (`IQ_FP`).
- **Execution units own their pipeline registers.** Each unit has its own ARB
  (register-read arbitration), RRD (register read) and EXE stages.
- **Speculative wakeup** with bypass hints and "rebusy" cancellation (§9.3).
- **Separate immediate and predicate register files.** Immediates are renamed
  into a small physical file (§8.4), and short-forward branches write
  predicate registers (§8.3, §6.5).
- **Single-cycle rollback.** The rename map table keeps a committed copy and
  the free list a speculative-allocation mask. An exception restores both at
  once instead of walking the ROB (§8.1, §12.4).
- **Stores split into AGEN and DGEN.** A store, SC or AMO occupies one issue
  slot but issues twice: address generation and data generation (§9.2). Loads
  and LR need only AGEN.

The small configuration:

| Structure | Small config | Where set |
|-----------|--------------|-----------|
| Fetch | 4 × 16-bit parcels (8 bytes) per cycle, 1 bank | `fetchWidth = 4` |
| Decode / rename / dispatch / commit width | 1 | `decodeWidth = 1` |
| ROB | 32 entries | `numRobEntries` |
| Issue queues | MEM 8 entries × 2-wide, UNQ 8 × 1, ALU 8 × 1, FP 8 × 1 | `issueParams` |
| Physical registers | 52 int, 48 FP, 32 immediate | `numIntPhysRegisters`, ... |
| Branch tags (in-flight branches) | 8 | `maxBrCount` |
| LDQ / STQ | 8 / 8 | `numLdqEntries`, `numStqEntries` |
| FTQ / fetch buffer | 16 / 8 | `ftq`, `numFetchBufferEntries` |
| I$ / D$ | 16 KiB each: 64 sets × 4 ways × 64 B lines | `icache`, `dcache` |
| D$ MSHRs | 2 (+1 MMIO) | `DCacheParams.nMSHRs` |
| D-TLB / L2 TLB | 8 base-page slots (2 tags × 4 sectors) + 4 superpage + 1 special / 512 entries, direct-mapped | `nTLBWays`, `nL2TLBEntries` |
| Branch predictor | TAGE-L: FA micro-BTB, BIM, BTB, TAGE, loop | `withTAGELBPD` |
| FPU | F and D, 4-cycle FMA, iterative div/sqrt | `fpu` |
| Privilege / VM | M, S, U; Sv39; 8 PMP entries | `useVM`, `nPMPs` |

`BoomCoreParams` (`boom/common/parameters.scala`) lists every knob, and
`HasBoomCoreParameters` derives the widths from them (`aluWidth`, `memWidth`,
`robAddrSz`, `maxPregSz`, ...). The configs in `config-mixins.scala`
(`small`, `medium`, `large`, `mega`, `megaTapeout`, `giga`) are plain functions
returning `BoomTileParams`. Rocket-chip expresses them as CDE `Config`
fragments instead.

Block diagram of the small core, with the `boom/` file that holds each part:

```
  ┌──────────────────────────── BoomFrontend (ifu/frontend.scala) ───────────────────────────┐
  │  F0 next-PC ─► F1 I$ + ITLB + µBTB ─► F2 I$ data, RVC, BIM/BTB ─► F3 predecode, TAGE/loop │
  │                                                         ─► F4 RAS, SFB ─► FetchBuffer      │
  │                                                                           FTQ (PCs, ghist)│
  └──────────────────────────────────────────────┬───────────────────────────────────────────┘
                                                 │ fetchpacket (uops)
  ┌─────────────────────────────── BoomCore (exu/core.scala) ────────────────────────────────┐
  │ Decode+Ren1 ─► Ren2/Dispatch ─► ROB (rob.scala)                                           │
  │   int/FP/pred/imm rename      ├─► mem IQ ─► MemExeUnit ×2 (AGEN, DGEN) ───────┐            │
  │                               ├─► unq IQ ─► UniqueExeUnit (MUL, DIV, CSR, I2F)│            │
  │                               ├─► alu IQ ─► ALUExeUnit (ALU, branch)          │            │
  │                               └─► FpPipeline (FP IQ, FP RF, FMA, FDIV, F2I)   │            │
  │   int RF (BankedRF), imm RF, pred RF, branch RF, bypass network                │            │
  │   rocket CSRFile                                                               │            │
  └────────────────────────────────────────────────────────────────────────────────┼───────────┘
                                                                                   │ agen/dgen
  ┌─ LSU (lsu/lsu.scala): LDQ, STQ, NBDTLB, ordering search, forwarding ───────────┴───────────┐
  └──────────────┬─────────────────────────────────────────────────────────────────────────────┘
                 │ LSUDMemIO                          PTW (rocket) ◄── ITLB and DTLB misses
  ┌─ BoomNonBlockingDCache (lsu/dcache.scala, mshrs.scala): meta/data arrays, MSHRs, WB, probe ─┐
  └──────────────┬──────────────────────────────────────────────────────────────────────────────┘
                 │ TileLink TL-C (dmem)          TileLink TL-UH Get (imem, from the I$)
```

---

## 3. The Chisel-to-Spinal layer

BOOM is written in Chisel and leans on Chisel's semantics in hundreds of small
places. The port keeps the code's shape (same names, same structure, comments
kept) and makes the semantic differences explicit. `PORTING_NOTES.md` lists
every decision; this section covers the ones you will meet most while reading.

### 3.1 Elaboration settings: `SpinalBoomConfig`

`boom/common/SpinalBoomConfig.scala`. Every elaboration (tests, simulation,
formal) goes through `SpinalBoomConfig()`:

- **Last connect wins.** Chisel code routinely writes `w := x` and then
  overrides `w.field := y`, or assigns a register in only some branches.
  `PhaseAllowAssignmentOverride` tags every signal with
  `allowAssignmentOverride` and every register with `AllowPartialyAssignedTag`.
  Spinal already resolves overrides in statement order; the tags only silence
  its overlap and partial-assignment checks. Combinational latch and undriven
  checks stay on.
- **DontCare is zero.** firtool lowers `DontCare` to 0 and upstream relies on
  that (for example the LSU's integer writeback leaves `fflags.valid` as
  DontCare). `dontCareGenAsZero = true` makes `assignDontCare()` do the same.
- **Out-of-range literals are allowed**, as in Chisel, because width-generic
  code compares against constants wider than the signal.
- **Synchronous, active-high reset**, as Chisel's default `Reset`.
- **`PhaseAssertLocations`** (port only) gives each message-less assertion its
  `File.scala:Lline` as the message, so a simulation failure names the
  assertion that fired.

### 3.2 Bit packing and `Cat`

Chisel packs a bundle with its first field at the MSB; Spinal puts it at the
LSB. Internal round trips (`asBits` / `assignFromBits`) do not care, but some
layouts are architectural: `mstatus`, the PTE format read from memory, CSR
values. Those go through `rocketchip.util.ChiselLayout.asUInt/fromUInt`.

`Cat(a, b)` and `Cat(seq: _*)` follow Chisel (first argument is the MSB). Never
call Spinal's own `Cat(seq)` without `: _*`, because it is LSB-first.

### 3.3 Memories: `readSyncChisel`

Chisel's `SyncReadMem` leaves read-during-write to the same address
*undefined* by default. In practice the firtool flow used with chipyard lowers
it in simulation to a registered address followed by an asynchronous read, so
a same-cycle write to the address being read is visible (write-first).
Spinal's `readSync` returns the old data (read-first). The port chooses to
reproduce the write-first lowering, so that simulation matches what upstream
BOOM was tested with: every synchronous-read memory goes through
`readSyncChisel` (in `rocketchip/util/package.scala`), `SyncReadVecMem` or
`SyncReadMemC`. This is a compatibility choice, not a language guarantee;
which collisions upstream actually relies on has not been audited.

### 3.4 Interfaces and indexing

- `Valid`/`Decoupled` become `Flow`/`Stream` (`.bits` becomes `.payload`).
- `x(idx: UInt)` on a `UInt` in Chisel is `(x >> idx)(0)`: 0 when out of range.
  The busy table and LSU forwarding rely on that, so the port uses the shift
  (`bitAt` in the LSU).
- Chisel truncates a too-wide `Vec` index; Spinal demands an exact width. The
  port uses `vec.at(idx)` / `vec.write(idx)(f)` (or `.resized`).
- Rocket-chip's `Seq.apply(UInt)` becomes `seq.read(idx)` with the same
  semantics (MSBs ignored, non-power-of-two reflected).
- Bundles taking `(implicit p: Parameters)` extend `ParameterizedBundle`, which
  clones them reflectively. Traits that declare hardware fields must
  `extend Bundle` (for example `HasBoomUOP`) or Spinal does not see the fields.

### 3.5 Structural changes

- **No diplomacy.** Rocket-chip negotiates bus shapes, memory maps and
  interrupts with diplomacy, which has no Spinal equivalent. The port gives
  them explicitly: `rocketchip.config.Parameters(tile, system)` holds the tile
  parameters and a `SystemParams` with `paddrBits` and the memory map (address
  sets, region types, `supportsAcquireT`, ...). Each cache states its bus
  parameters (`ICache.busParams`, `BoomNonBlockingDCache.busParams`).
- **Submodules created under `when`** in Chisel are hoisted out of the `when`
  in Spinal; their inputs are don't-care when unused.
- **Chisel `withReset(reset || clear)` on a `Queue`** becomes
  `Queue(..., hasFlush = true)` with `flush := clear`.
- **Struct-of-arrays queues.** The LDQ and STQ are vectors of registers per
  field (`ldq_valid`, `ldq_addr`, ...) with `ldq_read(idx)` / `stq_read(idx)`
  gathering an entry. This keeps per-field writes (`ldq_executed.write(i)(...)`)
  simple in Spinal, where upstream assigns fields of `ldq(i)` directly.

---

## 4. The rocket-chip pieces BOOM uses

BOOM is not standalone: it uses rocket-chip's CSR file, TLB, page-table walker,
PMP checker, ALU, multiplier, RVC expander, FPU building blocks and TileLink
helpers. These were ported into `rocketchip.*` as pure Spinal, rather than
re-implemented, so their behaviour matches what BOOM was designed against.

### 4.1 Configuration: `rocketchip.config` and `rocketchip.tile`

`Parameters` is a small stand-in for CDE: a case class of `TileParams` and
`SystemParams`, with `Field`/`View`/`alter` for the few places that look up
keys. `tile/Core.scala` defines `CoreParams` and `TileParams`, the traits
`BoomCoreParams` and `BoomTileParams` implement, and `HasCoreParameters`, which
derives `xLen`, `vaddrBits`, `paddrBits`, `pgLevels` and friends.

### 4.2 TileLink: `rocketchip.tilelink`

The buses are Spinal's own `spinal.lib.bus.tilelink`. `tilelink/Edges.scala`
adds the rocket `TLEdgeOut` helpers BOOM's caches call (`AcquireBlock`, `Get`,
`Put`, `Release`, `ProbeAck`, `GrantAck`, `count`/`done`/`addr_inc`, `MaskGen`)
and `TLArbiter.lowest`. `tilelink/Metadata.scala` is `ClientMetadata`, the
TL-C permission state machine (Nothing/Branch/Trunk/Dirty) with `onAccess`,
`onGrant`, `onProbe` and `onCacheControl`. Spinal's TileLink and rocket's agree
on opcode and permission encodings, so these are direct ports.

Spinal's TileLink has no Arithmetic/Logical (atomic) or Hint messages. That is
equivalent to rocket-chip with no manager advertising atomics: AMOs to cached
memory run in the D$ as usual, and AMOs to uncached regions take access faults.

### 4.3 CSR file

`rocket/CSR.scala` is rocket's `CSRFile`, used unchanged. It implements M/S/U,
trap entry and return, delegation, interrupts, counters (2 HPM counters on the
small config), PMP registers, `satp` and debug. BOOM accesses CSRs from execution,
non-speculatively because CSR instructions are serialised, and reports
retirement and traps to it from commit (§16). Vector CSRs are not supported (`require(!usingVector)`). Hypervisor
logic is ported but dead, because BOOM sets `useHypervisor = false`.

### 4.4 TLB, PTW, PMP

`rocket/TLB.scala` is the I-TLB. BOOM's data side uses its own non-blocking
NBDTLB (§14). `rocket/PTW.scala` is the shared page-table walker with its L2
TLB, `rocket/PMP.scala` the PMP checker. `rocket/HellaCache.scala` supplies the
request/response bundles the PTW uses to read PTEs through BOOM's D$.

### 4.5 Integer units and RVC

`rocket/ALU.scala`, `rocket/Multiplier.scala` (the iterative `MulDiv` and
`PipelinedMultiplier`) and `rocket/RVC.scala` (the compressed-instruction
expander) are used by BOOM's functional units and frontend.

### 4.6 FPU

`hardfloat/` is berkeley-hardfloat: recoded floating point (one extra exponent
bit, so subnormals become normal), `MulAddRecFN`, `DivSqrtRecFN_small`,
conversions and rounding. `tile/FPU.scala` is rocket's `FPUDecoder`, `FPToInt`,
`IntToFP`, `FPToFP` and FMA pipes on top of it. BOOM's FP registers hold
recoded values (65 bits for D), and values cross to integer or memory through
`ieee(...)` / `recode(...)`.

---

## 5. Life of an instruction

The pipeline is easiest to learn by following one instruction. Take
`add x5, x6, x7` on the small config. Stage names match the code's prefixes.

| Stage | Prefix in code | What happens | File |
|-------|----------------|--------------|------|
| F0 | `s0_` | next fetch PC selected; I$ tag/data read starts; predictors indexed | `frontend.scala` |
| F1 | `s1_`, `f1_` | I-TLB translates; I$ compares tags; micro-BTB redirects | `frontend.scala`, `icache.scala` |
| F2 | `s2_`, `f2_` | I$ data returns; 16-bit parcels split into instructions; RVC expanded; BIM/BTB redirects | `frontend.scala` |
| F3 | `f3` queue | predecode finds branches, JAL targets; TAGE/loop prediction; redirect on disagreement | `frontend.scala` |
| F4 | `f4` queue | RAS read/write; short-forward-branch detection; enqueue into fetch buffer and FTQ | `frontend.scala` |
| DEC / REN1 | `dec_`, `ren1_` | decode table → uop; branch tag allocated; map table read | `core.scala`, `decode.scala`, `rename-*.scala` |
| REN2 / DIS | `ren2_`, `dis_` | physical register allocated; busy bits read; ROB, LDQ/STQ and issue-queue entries written | `rename-stage.scala`, `core.scala`, `rob.scala` |
| ISS | issue unit | uop waits for operands, then is selected | `issue-unit-age-ordered.scala` |
| ARB | `arb_uop` | register-file read ports arbitrated | `execution-unit.scala` |
| RRD | `rrd_uop` | register file read, bypass network checked | `execution-unit.scala` |
| EXE | `exe_uop` | ALU computes; result written to the register file and bypass | `functional-unit.scala` |
| WB | ROB writeback | ROB entry marked not busy (registered once more) | `core.scala`, `rob.scala` |
| COM | ROB head | committed; stale physical register freed; FTQ entry released | `rob.scala`, `rename-*.scala` |

Each uop carries the information later stages need instead of looking it up:
its `ftq_idx` and `pc_lob` (low PC bits) instead of a full PC, its `br_mask`
(which in-flight branches it depends on), `rob_idx`, `ldq_idx`/`stq_idx`, the
physical registers (`pdst`, `prs1`, `prs2`, `prs3`, `stale_pdst`, `ppred`,
`pimm`) and their busy bits. Everything that can be killed by a branch
misprediction checks `IsKilledByBranch(brupdate, ...)` and clears resolved bits
with `UpdateBrMask(brupdate, ...)` every cycle (`boom/util/util.scala`).

---

## 6. Frontend: fetch, prediction and the FTQ

`boom/ifu/frontend.scala`, class `BoomFrontend`.

### 6.1 Fetch stages

The frontend is a decoupled pipeline that predicts and fetches without
waiting for the core.

- **F0 (next-PC select).** `s0_vpc` comes from, in priority order (later
  assignments in the file win): the reset vector (`jump_to_reset`), an
  `sfence` replay, a core redirect (`io.cpu.redirect_flush`), an F4 correction,
  an F3 correction, an F2 correction, a replay of an F2 fetch that missed or
  stalled, and the F1 prediction. `s0_valid` starts the I$ read and the
  predictor read (`bpd.io.f0_req`).
- **F1.** The I-TLB (`tlb`, rocket's TLB with `size = log2(fetchBytes)`)
  translates `s1_vpc`; the I$ gets the physical address as `s1_paddr` and
  compares tags. The micro-BTB's F1 prediction (`bpd.io.resp.f1`) picks
  `f1_predicted_target`, the default next fetch.
- **F2.** The I$ returns 8 bytes. A loop over banks and 16-bit slots
  (`f2_fetch_bundle`) builds up to four instructions, handling an instruction
  that straddles the previous packet (`f2_prev_half`, `edge_inst`) and
  expanding RVC (`ExpandRVC`). If the I$ missed or F3 is full, F0 replays this
  fetch (`s0_is_replay`, reusing the translation). BIM and BTB predictions
  arrive here; if they disagree with what F1 assumed, F2 redirects F0 and
  clears F1 (`f1_clear`).
- **F3.** A one-entry queue (`f3`) plus `f3_bpd_queue` for the slower
  predictor response. `BranchDecode` predecodes each instruction: branch type,
  JAL target, whether it is a call/return, whether it is a short forward
  branch. JAL/JALR always redirect; conditional branches redirect if the
  predictor says taken. If a JAL's BTB target was wrong, a correction is
  queued for the BTB (`f4_btb_corrections`). F3 redirects F0 when the final
  prediction disagrees with what is in flight behind it.
- **F4.** Another one-entry queue (`f4`). The RAS is read here (it takes a
  cycle) and returns override the target (`f4_decoded_target`). Calls push the
  return address. Short-forward branches are detected (§6.5). The packet then
  enters the fetch buffer and the FTQ together; both must have room.

Each later stage can only redirect if it knows better, and each redirect
squashes the younger stages. A mismatch in the **global history** an earlier
stage assumed is also a reason to redirect (`f2_correct_f1_ghist`, ...), which
keeps predictor indices consistent (`enableGHistStallRepair`).

### 6.2 Instruction cache

`boom/ifu/icache.scala`. Virtually indexed, physically tagged, blocking
(one refill at a time), 4-way with LFSR replacement. A miss in F2
(`s2_miss`) sends a TileLink `Get` for the whole 64-byte line; refill beats
are written as they arrive and the valid bit is set when the last one lands.
`fence.i` invalidates every line (`io.invalidate`). Instruction access faults
come from the I-TLB's PMA checks and the frontend's parcel PMP checks, not from the
cache: `resp.ae` is left don't-care (zero), as upstream, so a TileLink error on
a refill is not reported.

### 6.2.1 Instruction PMP checks

The frontend checks PMP for each two-byte parcel in F1, using the I-TLB's
physical address and the current privilege and PMP registers. It aligns the
address to the first fetch bank. Each parcel has a `PMPChecker(1)`.
The I-TLB uses `checkInstructionPMP = false` for this frontend only.
It still checks PMA, debug-region access, PTE permissions, and PTW faults.
Data TLB behavior and the default rocket TLB PMP checks stay unchanged.

F2 receives the registered parcel faults with the translation. Replay saves
these bits with the translation response. An RVC instruction uses one parcel
fault. A 32-bit instruction uses the OR of its two parcel faults. The saved
half instruction carries its first parcel fault through F2, F3, and F4 repair
paths. The next bank or packet supplies the second parcel fault. Across a page
boundary, each parcel uses its own page translation.

`FetchBundle.pmp_ae_if` holds one fault bit per instruction slot.
`pmp_ae_first` records whether the first parcel failed. The fetch buffer copies
this state into each micro-op. The ROB uses it to set `mtval` to the first
faulting parcel. This also handles a second-parcel fault within one packet.
The existing packet access-fault bit keeps its PMA and PTW meaning.
Page-fault state and handling do not change.

PMP faults do not kill an entire I-cache response. The frontend needs its
instruction bytes to determine instruction length. Younger instructions can
be fetched, but the normal precise trap flush prevents their retirement.
There is no new pipeline stage or scheduled cycle. This change fixes upstream
issue #786 in the port. Directed checks are in `tests/vm/fetch_pmp.S`.

### 6.3 Branch predictors

`boom/ifu/bpd/`. The predictor is a pipeline of composable banks, each a
`BranchPredictorBank` with responses at F1, F2 and F3. Each bank takes the
previous bank's response (`resp_in`) and may override it. `withTAGELBPD`
(config-mixins) chains them for the default config:

```
FA2 micro-BTB ─► BIM ─► BTB ─► TAGE ─► loop
   (F1)          (F2)    (F2)   (F3)     (F3)
```

- **Micro-BTB** (`faubtb2.scala`, 24 ways, fully associative, flops): taken
  targets in F1, so a predicted-taken branch costs no bubble.
- **BIM** (`bim.scala`, 2048 sets): bimodal 2-bit counters.
- **BTB** (`btb.scala`, 128 sets × 2 ways, 13-bit offsets): targets.
- **TAGE** (`tage.scala`): tables indexed by PC hashed with geometric
  global-history lengths, tagged, with a usefulness counter.
- **Loop predictor** (`loop.scala`): overrides the direction for branches with
  a stable trip count.

`BranchPredictor` (`predictor.scala`) splits a fetch packet across banks when
`nBanks = 2` (large configs) and merges the responses. Each predictor keeps
metadata (`f3_meta`) that rides with the packet and comes back with the update,
so it can update the exact entries it read. `ComposedBranchPredictorBank` packs
all components' metadata into one word of `bpdMaxMetaLength` bits.

**Global history** (`GlobalHistory` in `frontend-types.scala`) is speculative:
F1–F4 update it as they predict, the FTQ remembers the history each packet
saw, and a misprediction restores it from the FTQ (§11.2). It also carries
`ras_idx`, the RAS pointer, so the RAS is repaired together with the history.

### 6.4 Fetch target queue

`boom/ifu/fetch-target-queue.scala`. One entry per fetch packet in flight: the
packet's PC, global and local history, predictor metadata, the CFI (control
flow instruction) the frontend predicted taken (`cfi_idx`, `cfi_type`,
`cfi_is_call/ret`), the branch mask, and the RAS top. Uops carry only
`ftq_idx` plus `pc_lob`, so the core reads PCs from the FTQ when it needs them,
through three read ports (`arb_ftq_reqs` / `rrd_ftq_resps`): port 0 for
flushes, mispredicts and exceptions, ports 1–2 for the ALU's JALR and branch
target checks.

Three pointers: `enq_ptr` (next free), `deq_ptr` (oldest uncommitted, advanced
by `io.deq` from commit) and `bpd_ptr` (next entry to send as a predictor
update). Updates are sent after commit (`do_commit_update`) so predictors learn
only from the true path. After a misprediction the FTQ also sends a
`is_mispredict_update` and then `is_repair_update`s for the following entries,
which let local-history predictors undo speculative state.

### 6.5 Short-forward branches

A short-forward branch (SFB) is a conditional branch that jumps a few
instructions forward, landing in the current or the next fetch packet. BOOM
can turn such a branch into predication (`enableSFBOpt`, on by default). F3
records candidates (`sfbs`, `sfb_masks`, `shadowable_mask`); a candidate needs
every instruction it skips to be "shadowable" (`BranchDecode.shadowable`:
simple ALU ops with suitable register use). F4 accepts a candidate only if it
is *not* the packet's predicted-taken CFI: a branch predicted taken stays
ordinary control flow. For example, `bne a0, a1, +8; addi a2, a2, 1` with the
branch predicted not taken becomes a predicated `addi`; the same branch
predicted taken redirects fetch as usual. Accepted shadowed uops are marked
`is_sfb_shadow`. At rename the branch gets a predicate register (§8.3); the
shadowed uops read it and, when predicated off, copy their old destination
value instead of computing (`ldst_is_rs1`). An accepted SFB never redirects
fetch and cannot mispredict.

### 6.6 Fetch buffer

`boom/ifu/fetch-buffer.scala`. Converts each fetch packet into uops (one per
valid instruction) and queues them, 8 entries, decoupling fetch from decode.
`io.clear` empties it on any redirect.

---

## 7. Decode, branch tags and dispatch

### 7.1 Decode

`boom/exu/decode.scala`, `DecodeUnit`. A decode table (built with
`rocketchip.util.DecodeLogic`, minimised) produces the control signals: which
issue queue (`iq_type`), which functional units (`fu_code`), operand types,
immediate type, memory command, and the flags `is_unique` (must execute alone
on an empty pipeline: CSR writes, fences, `sfence.vma`, ...) and
`flush_on_commit` (refetch after commit). It also decides whether the
immediate is short enough to carry in the uop (§8.4), raises illegal
instruction exceptions using the CSR file's decode port, and turns pending
interrupts into an exception on the decoded uop.

### 7.2 Branch tags

`BranchMaskGenerationLogic` (`boom/exu/decode.scala`) hands out one of
`maxBrCount` tags to each uop that may mispredict (`allocate_brtag`:
conditional branches that are not SFBs, and JALRs; JALs are resolved in the
frontend). Every younger uop's `br_mask` has that tag's bit set until the
branch resolves. When the tags run out, decode stalls (`branch_mask_full`).

### 7.3 Decode-stage hazards

`core.scala`, "Decode/Rename1 pipeline logic". A decoded uop stalls if
dispatch is not ready, the ROB is rolling back, a branch is mispredicting this
cycle, the frontend is being redirected, branch tags are exhausted, or it
carries a frontend exception that has to wait for the pipeline to drain (the
exception needs the FTQ read port to fetch `badaddr`).

### 7.4 Dispatch

`core.scala`, "Dispatch Stage", and `dispatch.scala`. The uop leaving rename
stage 2 is dispatched in one cycle to:

- the ROB (`rob.io.enq_*`; the uop's `rob_idx` comes from the ROB tail),
- the LDQ/STQ (`io.lsu.dis_uops`; the LSU returns `dis_ldq_idx`/`dis_stq_idx`),
- the issue queues selected by `iq_type`, which is a mask (`BasicDispatcher`;
  the FP queue lives in `FpPipeline`). Usually one queue, but an FP store goes
  to two: the memory queue for its address (AGEN) and the FP queue, which
  reads the FP register and sends the data to the LSU (`FC_F2I`, DGEN through
  `FpPipeline.io.dgen`),
- the immediate file and the branch-info file (`immregfile`, `bregfile`), one
  cycle later.

`BasicDispatcher` is conservative: a slot is ready only if *every* issue queue
can accept it, including queues the uop does not go to. Dispatch stalls on a
full ROB, LDQ or STQ, any full issue queue, a rename stall (no free
physical register), or a unique instruction that is waiting for the ROB to
empty (`wait_for_empty_pipeline`). A uop dispatched behind a unique
instruction in the same packet also waits.

---

## 8. Rename: four renamers

`boom/exu/rename/`. BOOM v4 renames four kinds of state, each with its own
`AbstractRenameStage` subclass, all running in lockstep (`core.scala`,
"Register Rename Stage"):

| Renamer | Renames | Physical file | Class |
|---------|---------|---------------|-------|
| `rename_stage` | integer registers x1–x31 | 52 entries | `RenameStage(float=false)` |
| `fp_rename_stage` | FP registers f0–f31 | 48 entries | `RenameStage(float=true)` |
| `pred_rename_stage` | SFB predicates | one per FTQ entry | `PredRenameStage` |
| `imm_rename_stage` | long immediates | 32 entries | `ImmRenameStage` |

Rename has two stages. **Ren1** (the decode cycle) reads the map table;
**Ren2** (a pipeline register, `r_uop`) allocates the new physical register,
reads the busy table and is also the dispatch stage. `BypassAllocations` lets
a uop see a destination allocated by an older uop in the same packet or in the
cycle just dispatched.

### 8.1 Map table

`rename-maptable.scala`. `map_table` maps each logical register to its current
physical register. Three things update it:

- **Rename** writes new mappings (`remap_reqs`).
- **Branch snapshots.** When a branch is renamed, the table as it will be
  after that branch is saved in `br_snapshots(br_tag)`. On a mispredict the
  table is restored from the snapshot of the mispredicted branch in one cycle.
- **The committed map table** (`com_map_table`) is updated at commit with each
  committing uop's mapping. On rollback (an exception, §12.4) `map_table` is
  replaced by `com_map_table` in one cycle. BOOM v3 instead walked the ROB
  backwards to undo mappings.

### 8.2 Free list and busy table

`rename-freelist.scala`. `free_list` is a bitmask; `SelectFirstN` picks free
registers each cycle into a one-entry buffer per slot (`r_sel`), so allocation
is ready before the uop arrives. Registers come back to the free list:

- at **commit**, the `stale_pdst` (the previous mapping of the uop's
  destination) is freed (`dealloc`);
- on a **mispredict**, every register allocated after the branch is freed in
  one cycle (`br_alloc_lists(br_tag)`, which tracks allocations per branch);
- on **rollback**, every register allocated but not committed is freed
  (`spec_alloc_list`, cleared at commit via `despec`).

An assertion checks after every rollback that exactly `numPhysRegs - 32`
registers are free (`"[freelist] Leaking physical registers."`).

`rename-busytable.scala`: one busy bit per physical register, set when
allocated, cleared by wakeups (§9.3) and set again by a "rebusy" when a
speculative wakeup turns out wrong.

### 8.3 Predicate renaming

`PredRenameStage`. An SFB branch writes a predicate register named by its own
`ftq_idx` (one SFB per fetch packet), and the shadowed uops that follow get
that register as `ppred` with `ppred_busy` set. The ALU writes the predicate
register file (`pregfile`) when the branch executes, and wakes the shadowed
uops through `pred_wakeup_port`.

### 8.4 Immediate renaming

`ImmRenameStage`. Decode packs every immediate into `imm_packed` (20 bits).
If the sign-extended immediate fits in `immPregSz` bits (5 on the small
config), it is carried in the uop's `pimm` field (`imm_sel = IS_SH`).
Otherwise rename allocates one of 32 immediate registers, dispatch writes the
packed immediate there, and the execution unit reads it in RRD
(`HasImmrfReadPort`). The read itself frees the register, because each
immediate has exactly one reader. This keeps the issue-queue entries narrow.

---

## 9. Issue: queues, wakeup and speculation

### 9.1 Issue queues

`issue-units/issue-unit.scala`, `issue-unit-age-ordered.scala`,
`issue-slot.scala`. Three integer-side issue units are built in `core.scala`
(`mem_iss_unit`, `unq_iss_unit`, `alu_iss_unit`); the FP one is in
`FpPipeline`. The default is `IssueUnitCollapsing`: an age-ordered queue that
shifts entries towards slot 0 as older ones issue, so slot order is age order
and selection is a priority encoder from slot 0. Slots can be "slow" (shift one
per cycle) or "fast" (shift up to `dispatchWidth`).
`IssueUnitBanked` is the column-issue variant for wide configs.

Each `IssueSlot` holds one uop and watches the wakeup ports. A slot requests
issue when its operands are ready:

- `iss_ready`: `prs1`, `prs2` (and `prs3` for FP) and the predicate not busy;
- for memory slots, `agen_ready` (`prs1` ready: the address) and `dgen_ready`
  (`prs2` ready: the store data) separately.

The issue unit grants up to `issueWidth` requests per cycle, matching each
uop's `fu_code` against what each execution unit can accept this cycle
(`fu_types`, from the units' `io_ready_fu_types`; the iterative divider, for
example, is not ready while busy).

### 9.1.1 Age-matrix issue unit

`issue-unit-matrix.scala` keeps each uop in a fixed slot. Each matrix row
marks the older entries. Dispatch selects empty slots in index order.
New entries record surviving entries and earlier dispatch lanes as older.
Deallocation clears the corresponding matrix row and column.

`useFullIssueSel=true` selects the oldest ready entry for each issue port.
Each port removes its selection from the later ports. The partial selector
uses `ParallelFindOne` trees with alternating index preference. Both modes
match functional unit types and use the same wakeup and store handling.
`IssueUnitBanked` can use a matrix unit in each bank.

The default remains `useMatrixIssue=false`. `BoomConfigs.withMatrixIssue`
enables every queue. Simulation config names accept `small-matrix-full`,
`medium-matrix-partial`, and equivalent names for the other presets.
Use `RISCV_TESTS_CONFIG`, `VM_TESTS_CONFIG`, or `BENCH_CONFIG` to select one.
Use `-collapsing-full` or `-collapsing-partial` for comparisons.
See [matrix verification](MATRIX_VERIFICATION.md) for the ISA, VM, unit,
elaboration, and IPC results.

### 9.2 Stores and AMOs: AGEN and DGEN in one slot

A store needs an address (rs1 + imm) and data (rs2), which may be ready at
different times. The memory slot issues it twice (`isMem` in `IssueSlot`):
once as AGEN (address generation, to the LSU's TLB path) and once as DGEN
(data generation, writes the STQ data). For the DGEN issue the slot rewrites
the uop so rs2 travels on the rs1 read port. Flags `iw_issued_partial_agen` /
`iw_issued_partial_dgen` record which half went first; the slot stays valid
until both have. On the small config the memory queue is 2-wide:
`mem_exe_unit_0` does DGEN only and `mem_exe_unit_1` does AGEN and DGEN
(`hasAGen = w >= memWidth - lsuWidth`). FP store data comes from the FP
pipeline's own DGEN port.

### 9.3 Wakeup, bypass hints and rebusy

The scheduler wakes dependents speculatively so that back-to-back dependent
ALU ops issue in consecutive cycles:

- **ALU fast wakeup.** `ALUExeUnit.io_fast_wakeup` fires in the cycle a uop is
  *issued*, with `bypassable = true` and `speculative_mask` naming the ALU. A
  woken dependent records `iw_p1_bypass_hint` (its operand will be on the
  bypass network, so it does not need a register-file read port) and
  `iw_p1_speculative_child` (which ALU it depends on).
- **Load wakeup.** The LSU wakes a load's dependents before the data arrives,
  assuming a D$ hit (`io.core.iwakeups`, "Task 4" in the LSU). The load-to-use
  delay is 5 cycles by default (4 with `enableFastLoadUse`).
- **Rebusy.** If the load misses or is killed, the LSU sends a wakeup with
  `rebusy = true`. Slots and the busy table mark the register busy again
  (`rebusied_prs1`). A dependent that already issued is caught in ARB: it
  replays (`replayArb`), and if it was an ALU op its own dependents are
  rebusied in turn through `io_child_rebusy` and the speculative-child masks.
- **Squash.** If an execution unit loses register-file read-port arbitration
  (`io_squash_iss`), the grant is squashed and the uop re-issues with bypass
  hints cleared (it will read the register file next time).

An issued uop is not removed at once: `iw_issued` keeps it for one more cycle,
and it leaves only if it was not rebusied (`next_valid := rebusied`).

---

## 10. Register read and execution

### 10.1 Register files

`register-read/regfile.scala`. `BankedRF` (integer: one bank on the small
config) and `FullyPortedRF` (immediate, predicate, branch-info). The register
files use an arbitration interface: execution units request read ports in ARB
(`arb_read_reqs`) and get data in RRD (`rrd_read_resps`). The integer file has
3 physical read ports shared by the units' 6 logical readers, which is why
read-port arbitration and bypass hints exist. Writes come from the LSU (load
data), the long-latency arbiter (`ll_arb`: multiplier, divider, CSR, FP-to-int)
and each ALU. The FP register file is inside `FpPipeline`.

The **branch-info file** (`bregfile`) holds, per branch tag, the LDQ/STQ tail
indices at dispatch, so a mispredicting branch can reset the LSU queues
without carrying those indices in every branch uop.

### 10.2 Execution units

`execution-units/execution-unit.scala`. Every unit extends `ExecutionUnit`,
which owns three stage registers: `arb_uop` (from issue), `rrd_uop` and
`exe_uop`, each cleared by branch kills and flushes. Traits add ports:
`HasIrfReadPorts` (integer reads, bypass check in RRD), `HasImmrfReadPort`,
`HasPrfReadPort`, `HasBrfReadPort`, `HasFtqReadPort` (PC for JALR/branches),
`HasFrfReadPorts`.

| Unit | Functional units | Results |
|------|------------------|---------|
| `ALUExeUnit` | rocket ALU + branch resolution (`ALUUnit`) | integer RF, bypass, `io_brinfo` |
| `UniqueExeUnit` | pipelined multiplier, iterative divider, CSR access, int-to-FP | `ll_arb` → integer RF; `io_ifpu_resp` → FP pipeline |
| `MemExeUnit` | AGEN (address add, to LSU), DGEN (store data to LSU) | LSU |
| `FPExeUnit` (in `FpPipeline`) | FMA/FPU, FDIV/FSQRT, FP-to-int | FP RF; `to_int` → `ll_arb` |

`functional-unit.scala` holds the functional units. `ALUUnit` computes
results and resolves branches: it compares the actual direction and target
with the prediction (target from the FTQ's next PC for JALR) and produces a
`BrResolutionInfo` (`mispredict`, `taken`, `pc_sel`, `jalr_target`,
`target_offset`). For SFB branches it writes the predicate instead.

### 10.3 FP pipeline

`fp-pipeline.scala`. A self-contained back end. FP uops are renamed by
`fp_rename_stage` in the core and dispatched into `dis_uops`; the pipeline has
its own issue queue, FP register file (recoded 65-bit values), FMA units,
FDIV/FSQRT and writeback arbiters.
Loads write it through `ll_wports` (recoding the memory value); integer-to-FP
results arrive through `from_int`; FP-to-int and FP compares leave through
`to_int`. FP stores send data to the LSU through `dgen`.

---

## 11. Branch resolution and recovery

`core.scala`, "Dealing with branch resolutions".

### 11.1 Two-phase branch update

Each ALU's `io_brinfo` is registered into `brinfos`. From these the core builds
`brupdate`, which goes to every structure that holds uops:

- **`b1`** (same cycle): `resolve_mask` (tags whose branches resolved) and
  `mispredict_mask`. Every uop clears resolved bits from its `br_mask`
  (`UpdateBrMask`), and anything whose mask hits `mispredict_mask` is killed
  (`IsKilledByBranch`).
- **`b2`** (registered): the oldest mispredicting branch's uop and target
  info. Used to restore state: map table snapshot, free list, ROB tail, LDQ/STQ
  tails (from the branch-info file), FTQ, issue queues.

### 11.2 Redirecting fetch

On `b2.mispredict` the core reads the branch's FTQ entry (port 0) and computes
the correct target: `pc + 2/4` for not taken, `pc + target_offset` for a taken
branch, or `jalr_target`. It sends `redirect_pc`, `redirect_ftq_idx` and a
repaired global history (`redirect_ghist`, built by updating the FTQ entry's
history with the actual outcome). The FTQ resets its `enq_ptr` past the branch
and rewrites that entry's CFI fields (`redirect_new_entry`), so the eventual
commit update teaches the predictor the right outcome. The RAS top is repaired
from the entry (`ras_update`).

### 11.3 Exceptions and flushes

Timing: in cycle T the excepting uop is at the ROB head and the ROB throws it
(`exception_thrown`, a wire), raising `com_xcpt` and `flush` combinationally in
the same cycle; a `flush_on_commit` instruction raises `flush` in its commit
cycle. The core registers `flush` and redirects the frontend in T+1 (the
execution units and LSU see the registered flush as their kill). The ROB sees
`RegNext(RegNext(exception_thrown))` in T+2 and spends T+3 in `s_rollback`.
The redirect goes to the CSR file's `evec` for exceptions and `xret`,
to the same PC for `refetch`, or to the next PC for `flush_on_commit`
instructions (`FlushTypes`). The global history is cleared on these flushes.

---

## 12. The reorder buffer

`boom/exu/rob.scala`, class `Rob`.

### 12.1 Organisation

The ROB is `numRobRows` rows of `coreWidth` banks; `rob_idx` is
row ++ bank. Per entry it keeps valid, busy, unsafe, exception and predicated
bits in registers. The uop is split: the fields commit needs often
(`RobCompactUop`) go in a 1R1W memory, the rest in a register vector, and the
commit uop is reassembled from both. Pointers: `rob_head`, `rob_tail` and
`rob_pnr`.

### 12.2 Writeback

`wb_resps` (one per integer write port plus the FP ports) clear `busy`.
Stores clear `busy` separately when both address and data are in the STQ
(`lsu_clr_bsy`). The LSU clears `unsafe` for memory ops once they can no
longer cause a misspeculation (`lsu_clr_unsafe`).

### 12.3 Commit

A row commits from the head when its valid entries are not busy and have no
exception (`can_commit`). Commit is in order within the row and stops at the
first instruction that cannot commit. `io.commit` drives: the rename stages
(free `stale_pdst`, update the committed map table), the LSU (mark stores
committed, free loads), the FTQ (`deq`), the CSR file (`retire`, fflags) and
the trace/RVFI outputs.

### 12.4 Exceptions and rollback

The ROB keeps only the oldest exception (`r_xcpt_uop`, `r_xcpt_badvaddr`),
from three sources: exceptions set at dispatch (decode-time: illegal
instruction, frontend faults, interrupts), the LSU (`lxcpt`: misaligned, page
and access faults) and CSR replays. When the excepting uop reaches the head,
the ROB throws it (`exception_thrown`): `com_xcpt` goes to the CSR file and
`flush` to the frontend (§11.3 has the cycle-by-cycle timing). Three cycles
later the state machine spends one cycle in `s_rollback`: the tail is set to the head, the rename stages
restore the committed map table and free the speculative allocations. BOOM v3
spent one cycle per ROB row unwinding here.

"Mini-exceptions" (`MINI_EXCEPTION_MEM_ORDERING`,
`MINI_EXCEPTION_CSR_REPLAY`) use the same path but do not trap: they flush
and refetch from the instruction.

### 12.5 Unique instructions

A dispatched `is_unique` uop moves the ROB to `s_wait_till_empty`, during
which nothing else dispatches until it commits. Combined with dispatch waiting
for an empty ROB, unique instructions (CSR writes, fences, `sfence.vma`,
`wfi`, ...) execute alone and non-speculatively.

### 12.6 Point of no return

`rob_pnr` advances past entries that are no longer `unsafe` (cannot
mispredict, fault or replay). Everything older than the PNR will commit. The
issue units can use it to delay speculative issue (`enableConservativeSNI`,
off by default).

---

## 13. The load/store unit

`boom/lsu/lsu.scala`, class `LSU` (about 2100 lines, the largest file).

### 13.1 Queues

- **LDQ** (8 entries): per load, the address (`ldq_addr`, valid once
  translated), whether it is still virtual (TLB miss), uncacheable,
  `executed`, `succeeded`, `order_fail`, `observed`, its byte mask,
  `next_stq_idx` (the STQ tail at dispatch: every store before that is
  older), and the store it forwarded from.
- **STQ** (8 entries): address, data, `committed`, `succeeded`, and
  `next_ldq_idx`. Pointers: `stq_tail` (dispatch), `stq_commit_head` (next to
  commit), `stq_execute_head` (next to send to the D$), `stq_head` (next to
  free once the D$ acknowledged).

Indices carry an extra wrap bit (`1 + ldqAddrSz` bits) for age comparisons
(`IsOlderLSU`, `IdxAgeOt`).

### 13.2 Scheduling: can-fire and will-fire

Every cycle the LSU decides what uses its TLB port, its D$ port and its
load-CAM search port (`lsu_sched`, in priority order):

1. `sfence` (TLB)
2. store commit, if the STQ is almost full (D$)
3. incoming load AGEN (TLB, D$, CAM)
4. incoming load AGEN without D$ access, if the D$ port is taken (TLB, CAM)
5. incoming store AGEN (TLB, CAM)
6. D$ release search (CAM: marks loads to a released line `observed`)
7. PTW (`hella`) request (TLB, D$)
8. PTW request wakeup after a nack (D$)
9. store address retry after a TLB miss (TLB, CAM)
10. load retry after a TLB miss (TLB, D$, CAM)
11. load wakeup: a translated load that has not executed (D$, CAM)
12. store commit (D$)

Incoming AGENs cannot be back-pressured, which is why they rank high. TLB
misses go to `retry_queue` and come back through 9/10.

### 13.3 Loads

A load is translated in the EXE cycle and, on a TLB hit, sent to the D$ in
the same cycle (`will_fire_load_agen_exec`). The next cycle (the "mem" or LCAM
stage) it searches the STQ:

- `ldst_addr_matches`: older stores (by `next_stq_idx`) whose bytes overlap
  the load. `ldst_forward_matches`: those that also have their data and cover
  every byte the load reads.
- Any overlap, or any older AMO or fence, kills the D$ request (`s1_kill`) and
  leaves the load not executed, so it is retried by a later load wakeup.
- If the youngest overlapping store is also the youngest one that can forward
  (`ForwardingAgeLogic`), its data is **forwarded** in the writeback cycle
  instead (`wb_ldst_forward_valid`).
- On a 1-wide LSU, a load that keeps conflicting is held back
  (`block_load_wakeup`) so the store it waits for can drain.
- Load-to-store forwarding: a younger store whose data register is this load's
  destination could take the loaded value directly (`stld_prs2_matches`).
  The match needs `enableLoadToStoreForwarding` and the STQ write additionally
  needs `enableStLdForwarding`, which is off by default, so the small config
  does not do this.
- Loads to uncacheable addresses wait until they are at the ROB head
  (`commit_load_at_rob_head`) with all older stores gone.

If the D$ nacks (MSHR full, bank conflict, line being evicted), `executed` is
cleared and the load is retried through "load wakeup".

### 13.4 Memory ordering

The same CAM search detects ordering violations:

- **Store–load.** When a store's address becomes known (`do_st_search`), any
  younger load to an overlapping address that already executed without
  forwarding from this store (or from a younger one) gets `order_fail`. When
  that load reaches the ROB head it raises `MINI_EXCEPTION_MEM_ORDERING` and
  the pipeline refetches from it.
- **Load–load.** RVWMO requires same-address loads to appear in order once
  another hart could observe the line. A D$ release marks matching loads
  `observed`; a younger executed load that sees an older one still pending is
  killed and replayed. If the older load is nacked and retries after the
  younger load executed and was observed, the younger load gets `order_fail`.
  The existing mini-exception path flushes and refetches it. The port restores
  this replay from BOOM v4's commented code and BOOM v3; v4 asserts instead.
  These checks are stronger than RVWMO requires.

### 13.5 Stores and AMOs

Stores wait in the STQ until committed. A committed store with address and
data enters `stq_execute_queue` (4 entries) at `stq_execute_head` and is sent
to the D$ by "store commit"; `stq_head` frees the entry once the D$
acknowledges (`succeeded`). A nacked store flushes the execute queue and
rewinds `stq_execute_head`.

AMOs and SC are store-queue entries that also return data. LR is a load
(LDQ only) that sets a reservation in the D$ (§15.4). Unlike plain
stores, **an AMO enters the execute queue before it commits**
(`stq_enq_e.payload.committed || stq_enq_e.payload.uop.is_amo`), because it is
unique: it reaches the head of the ROB alone and must not wait for itself. Its
STQ address is held invalid if translation faulted (the "Prevent AMOs from
executing!" line), so it never reaches the execute queue. Section 19.1 is about
that line.

Fences are STQ entries too: a committed `fence` at `stq_head` waits until the
D$ reports `ordered` (no outstanding misses), forcing it with `force_order`.

### 13.6 Exceptions

TLB results are checked in EXE: misaligned (`ma_ld`/`ma_st`), page fault
(`pf_ld`/`pf_st`), access fault (`ae_ld`/`ae_st`) and breakpoints. The next
cycle (`mem_xcpt_*`) the oldest one is reported to the ROB (`lxcpt`) and the
entry's uop gets `exception := True`.

### 13.7 PTW port

The PTW reads PTEs through the D$. The LSU fronts this with a small state
machine (`hella_state`: `h_ready`, `h_s1`, `h_s2`, `h_wait`, `h_replay`,
`h_dead`) that makes the LSU's D$ port look like rocket's HellaCache to the PTW.

---

## 14. Address translation: NBDTLB, I-TLB and PTW

**NBDTLB** (`boom/lsu/tlb.scala`) is BOOM's data TLB, derived from an older
version of rocket's TLB but non-blocking: a miss returns `miss` and the LSU
retries, so other memory ops proceed while the PTW walks. Its 8 ways
(`nTLBWays`) are rocket-style sectored entries (one tag for 4 consecutive
pages), plus superpage entries and a special entry for PMP-fragmented pages;
all are searched associatively. It checks
permissions, PMAs (cacheable, AMO support) and PMPs, and has one port per LSU
lane. Each entry stores the PPN, the PTE permission bits (`sr`, `sw`, `sx`,
`pr`, `pw`, `px`, `u`, `g`), access-fault flags (`ae_ptw`, `ae_final`, §19.4) and PMA attributes. Section
19.2 adds `pf`.

The **I-TLB** is rocket's `TLB` (`rocket/TLB.scala`), instantiated by the
frontend. The frontend checks instruction PMP per parcel (section 6.2.1).

The **PTW** (`rocket/PTW.scala`) serves both TLBs (and a third, unused port)
through an arbiter: it walks the Sv39 page table through the D$, caching leaf
entries in a 512-entry L2 TLB. A walk returns the PTE, its level and three fault
flags: `ae_ptw` (a PTE read itself was denied by PMP/PMA), `ae_final` (the
final physical page is not accessible) and `pf` (a valid leaf PTE uses
reserved encodings). Other invalid PTEs come back with no permissions and fault
through the permission check. The NBDTLB stores all three (`pf` since §19.2,
`ae_ptw` since §19.4).

---

## 15. The non-blocking data cache

`boom/lsu/dcache.scala` (`BoomNonBlockingDCache`) and `mshrs.scala`.

### 15.1 Pipeline

Three stages, per LSU lane:

- **s0**: one request is chosen from the LSU, an MSHR replay, the writeback
  unit, the prober, the prefetcher or an MSHR metadata read (`s0_req`,
  `s0_type`). Tag (meta) and data arrays are read.
- **s1**: tags compared (`s1_tag_eq_way`), the LSU may kill the request
  (`s1_kill`, for example a load that must not forward).
- **s2**: hit is "tag match and the coherence state permits the access without
  changing" (`s2_hit`, using `ClientMetadata.onAccess`). Hits respond (loads)
  or write (stores, through the AMO ALU). Misses go to the MSHRs. A request is
  **nacked** if the MSHRs are full, the line is being probed or evicted, a data
  bank conflicts, or the set is being written back. The LSU retries nacked
  requests.

Replacement is random. The data array is `BoomDuplicatedDataArray` (one copy per
lane) or `BoomBankedDataArray` (`numDCacheBanks > 1`).

### 15.2 MSHRs

`BoomMSHR` tracks one outstanding miss through these states:

1. `s_refill_req` / `s_refill_resp`: acquire the line (`AcquireBlock`, `NtoB`
   for reads, `NtoT` for writes) and write the `GrantData` beats into the
   **line buffer**, not the cache.
2. `s_drain_rpq_loads`: answer waiting plain loads directly from the line
   buffer. If the replay queue empties without any load being answered (all
   the requests were speculative and got killed), the line is dropped and
   never installed (`commit_line` stays false). LR is excluded here: it must
   go through the cache pipeline to set its reservation.
3. `s_meta_read` … `s_wb_resp`: read the victim's metadata and, if dirty, clear
   it and evict through the writeback unit.
4. `s_commit_line`: copy the line buffer into the data array.
5. `s_drain_rpq`: replay the remaining requests (stores, AMOs, LR/SC) through
   the cache pipeline; stores update the pending coherence state.
6. `s_meta_write_req`: write the tag and coherence state, then send `GrantAck`. Secondary
misses to the same line join an existing MSHR if the original request had
enough permission. `BoomIOMSHR` handles uncached accesses (`Get`/`Put`); TileLink
atomics are not available (§4.2), so uncached AMOs never reach one.

### 15.3 Coherence

The D$ is a TileLink TL-C client: it acquires lines, answers probes
(`BoomProbeUnit`, downgrading or invalidating and sending `ProbeAck[Data]`),
and releases dirty victims (`BoomWritebackUnit`, `ReleaseData`). Clean
victims are dropped without a Release, as in upstream (§17.2 explains a
consequence for the simulation memory model).

### 15.4 LR/SC

An LR reserves the **cache line** (`lrsc_addr` is the block address) and
starts `lrsc_count` at `lrscCycles - 1` (79). The reservation counts as valid
only while `lrsc_count > lrscBackoff`, so the final cycles are a back-off window
in which an SC fails. An SC succeeds if the reservation is valid and its line
matches. The reservation is cleared by any later successful access through
lane 0's hit or replay path (including the SC itself) and by a miss to the
reserved line that lacks permission. The debug counter `debug_sc_fail_cnt`
asserts after 100 consecutive failed SCs to one address (§20).

---

## 16. CSRs, privilege, exceptions and interrupts

`core.scala`, "Privileged Co-processor 0 Register File", and
`rocket/CSR.scala`.

- **CSR access.** CSR instructions are unique and execute in the
  `UniqueExeUnit`. The response (`csr_resp`) drives `csr.io.rw`; if the CSR
  file stalls the access, it is replayed through the ROB (`csr_replay`). The
  read value returns through `ll_arb`.
- **Traps.** `csr.io.exception` and `csr.io.cause` come from `com_xcpt`,
  delayed a cycle. The trapping PC is rebuilt from the FTQ's committed PC
  (`io.ifu.com_pc`) plus `pc_lob`. `tval` is the bad address for misaligned,
  access and page faults and breakpoints. It is 0 for illegal instructions;
  BOOM does not report the encoding. `encodeVirtualAddress` compresses a
  64-bit address to `vaddrBits + 1` bits, keeping invalid addresses invalid.
- **Trap target.** The CSR file's `evec` (vectored `mtvec`/`stvec` supported)
  is the redirect target for exceptions; for `xret` it is delayed three cycles
  (`ShiftRegister(csr.io.evec, 3)`) to line up with when the CSR file updates.
- **Interrupts.** The CSR file computes `interrupt` and `interrupt_cause` from
  `mip`/`mie`/delegation. Decode stamps the cause on the next decoded uop as an
  exception, so interrupts are taken precisely between instructions.
- **Counters.** `retire` counts committed instructions. The HPM counters count
  the events in `perfEvents` (exceptions, mispredicts, flushes, I$/D$ misses,
  TLB misses).
- **Custom CSRs** (`BoomCustomCSRs`): `enableOOO` (0x800), `enableBPD` (0x808),
  the chicken bits, and `marchid = 2`.

---

## 17. The tile and the simulation SoC

### 17.1 `BoomTile`

`boom/common/tile.scala`. A plain component that instantiates `BoomFrontend`,
`BoomCore`, `LSU`, `BoomNonBlockingDCache` and a 3-port `PTW`. In rocket-chip
this is a LazyModule whose diplomatic nodes merge the I$ and D$ into one
master; here the I$ (`imem`, TL-UH Get) and D$ (`dmem`, TL-C) are separate
ports, and hart id, reset vector and interrupts are inputs. RoCC and the trace
encoder are not ported.

### 17.2 `BoomSimTop` and the memory model

`boom/system/BoomSimTop.scala` merges `imem` and `dmem` with Spinal's
`tilelink.Arbiter` onto one bus and exposes `tohost` writes, the commit trace
and, optionally, RVFI checked by riscv-formal's monitor (§18.3). Spinal's
Arbiter locks onto a requester that has `a.valid` up, but rocket's I$ may
withdraw `a.valid` (s2 kill), so an `m2sPipe` sits on the I$ A channel.

`src/test/scala/boom/sim/BoomSim.scala` drives it in Verilator:

- **Memory.** Spinal's TileLink `MemoryAgent` on a `SparseMemory`, loaded from
  the ELF, mapped at 0x8000_0000 (256 MiB, cacheable). `BoomMemoryAgent`
  overrides one decision: the stock agent decides `Grant` vs `GrantData` from
  its record of the client's permissions, but BOOM drops clean lines silently,
  so that record can be stale. TileLink requires data for an acquire from N,
  so the override always sends `GrantData` for `NtoB`/`NtoT`.
- **Platform** (`BoomSim.Platform`). A CLINT at 0x0200_0000 (`msip`,
  `mtimecmp` at +0x4000, `mtime` at +0xBFF8) and an interrupt generator at
  0x0C00_0000 (word 0 drives `meip`, word 1 `seip`), both uncached regions
  served from simulation memory. Every cycle the harness increments `mtime`
  (or adopts a value software wrote) and drives the interrupt lines.
- **Termination.** riscv-tests/ACT convention: a store of 1 to `tohost` is a
  pass, `(code << 1) | 1` a failure with that code.

---

## 18. Verification

### 18.1 Elaboration and unit tests

- `ElabSpec` elaborates rocket units; `BoomElabSpec` elaborates BOOM configs.
- `HardfloatSpec` checks the hardfloat port against Java IEEE doubles.
- `NBDTLBSpec` drives the data TLB's PTW port directly for directed TLB cases (§19).
- `VmTestsSpec` runs the directed virtual-memory programs in `tests/vm` (built
  by `tests/vm/build.sh`; each also passes on Sail), currently `ae_ptw` (§19.4).

### 18.2 riscv-tests

`RiscvTestsSpec` runs the rv64 suites `ui`, `um`, `ua`, `uf`, `ud`, `uc`,
`mi`, `si`, `uzba`, `uzbb` and `uzbs` in the `p` (physical) and `v` (virtual
memory) environments on the small config (`RISCV_TESTS_ENVS` selects
environments). Excluded: `ma_data` (it expects misaligned accesses to work;
BOOM traps on them) and `rv64mi-p-instret_overflow` (§20).

Result: **321 of 321 pass** (the tests check themselves). With
`RISCV_TESTS_RVFI=1` they also pass under the RVFI monitor, with these limits:

- the monitor is generated for **RV64IMC**, so its ISA checks only cover those
  instructions. A, F/D, B, CSR and most privileged instructions are retired
  through it, and the stream-consistency checks (order, PC continuity,
  register values) still apply, but their results are not checked against an
  ISA model;
- on a trap marked `mem_fault` the monitor trusts the core and does not check
  that the access or page fault was right.

### 18.3 RVFI

RVFI (the RISC-V Formal Interface) is a per-retirement record of an
instruction's effects: PC, next PC, registers read and written with values,
memory address, masks and data, trap and interrupt flags. riscv-formal checks
it against an ISA model. BOOM has no RVFI port upstream; the port adds one
(`enableRVFI`).

**The tracker** (`boom/exu/rvfi.scala`, `RvfiTracker`). An out-of-order core
learns an instruction's effects at different times, so the tracker keeps a
side table indexed by ROB index and fills it as the instruction moves:

| Source | Fills |
|--------|-------|
| Execution units, EXE stage (`io_rvfi_rs`) | rs1 / rs2 values |
| ALU (`io_rvfi_br`) | branch direction (including SFBs) |
| LSU AGEN | memory address |
| DGEN | store data |
| LSU load responses | load data, AMO old value |
| ROB commit (`debug_wdata`) | rd value |

At commit it emits the records, one cycle later. Details:

- `pc_wdata` for branches and jumps is recomputed from the instruction and the
  resolved direction or rs1. For traps and `xret` it is the frontend redirect
  target.
- Memory is reported in **aligned mode** (`RISCV_FORMAL_ALIGNED_MEM`):
  `mem_addr` is 8-byte aligned and masks and data sit in their byte lanes,
  because BOOM traps on misaligned accesses.
- The value an AMO writes is recomputed from rs2 and the old value; a
  successful SC reports rs2 as its data, a failed SC (rd ≠ 0) no memory write.
- `mem_fault` (port extension, after riscv-formal's `RISCV_FORMAL_MEM_FAULT`)
  marks traps the ISA model does not model: fetch, load and store access
  faults and page faults.

**Checking RVFI in simulation.** `formal/gen_monitors.sh` generates
riscv-formal's RVFI monitor (`rvfimon_c{1,2,3}.v`, rv64imc, aligned mode) and
patches it:

1. two specification bugs in the div/rem checks;
2. skipping the per-field checks of trapping instructions, as riscv-formal's
   own `rvfi_insn_check.sv` does;
3. a `rvfi_mem_fault` input, so a trap is accepted where `mem_fault` is set.

`RvfiMonitor` instantiates it as a black box in `BoomSimTop`, and
`RISCV_TESTS_RVFI=1` runs riscv-tests with it. A monitor error ends the test
with `RvfiError(code)`.

### 18.4 riscv-formal

`formal/` and `boom/system/FormalTop.scala`. `BoomFormalTop` is the small BOOM
with RVFI, whose I$ and D$ buses are answered by `FormalTLResponder`s: they
follow the TileLink protocol (one transaction at a time, Grant followed by
GrantAck, Release answered by ReleaseAck, no probes) but take data and stalls
from free inputs. `wrapper.sv` ties those inputs, and optionally the interrupt
lines, to `rvformal_rand_reg` values. `checks.cfg` sets rv64imc, aligned
memory and `RISCV_FORMAL_VALIDADDR` to the formal top's memory range, so
accesses outside it are expected access faults. `formal/run.sh` regenerates the
Verilog, links the files into riscv-formal as `cores/boom` and runs the checks
with SymbiYosys and bitwuzla.

**Status.** The harness elaborates and runs end to end. BOOM needs on the
order of a hundred or more cycles after reset before its first retirement
(cache metadata reset, I$ refill, pipeline fill). A `cover` run reached step
149 without a retirement and was stopped at 2 hours, with the solver taking
over 30 minutes per step at that depth. Making the checks useful needs a
formal-specific setup (tiny caches and queues, starting from a post-reset
state, or abstracting the caches); that work is deferred.

### 18.5 ACT4 (riscv-arch-test)

`act4/`. ACT4 generates self-checking test ELFs from a UDB description of the
implementation and Sail's reference results.

- `boom-rv64gc.yaml`: the UDB configuration. Extensions RV64IMAFDC, B (Zba,
  Zbb, Zbs), Zicntr, Zihpm, Zicsr, Zifencei, Zihintntl, Zihintpause, Zaamo,
  Zalrsc, U, S, Sv39, Sm and related. It also records BOOM's choices:
  misaligned accesses trap with high priority, `mtval = 0` on illegal
  instructions, no `time` CSR (ACT emulates it from the CLINT), 16 PMP
  entries with 8 usable at 4-byte granularity, 40-bit HPM counters,
  32-bit physical addresses, vectored trap alignment.
- `sail.json`: the matching Sail model configuration.
- `rvmodel_macros.h`: halt through `tohost`, CLINT and interrupt-generator
  addresses (§17.2), no console. `link.ld`: memory at 0x8000_0000.
- `act4/build.sh` builds the ELFs (with a macOS workaround for udb's bundled
  Linux Z3). `Act4Spec` runs them on `BoomSim`.

Historical baseline, before the section 18.6 fixes: with the ACT4 checkout
of 2026-10-15 (`56d40ab`, 620 tests), **585
pass** with all fixes in §19, and 583 without §19.4 (the two
`SvPMP_sv39_pmp_on_pte_*` tests). An earlier checkout gave 494 of 524 with the
first two fixes. The 35 failures:

| Tests | Cause |
|-------|-------|
| `PMP*` NA4/TOR `legal_lxwr` cases and `PMPZca` (12) | Fetch PMP is checked over the whole 8-byte fetch block, so a narrower PMP region around executing code faults (upstream behaviour, §20). Traced on `PMPZca_aligned_na4`. |
| `SvPMP_sv39_pmp_on_pa_*` (2) | Not triaged individually; likely the same cause. |
| `Sv_sv39_canonical_*` (2) | `jalr` to a non-canonical address: BOOM's 40-bit `xepc` turns it into a different invalid address, which the spec allows; Sail keeps the full address. |
| `ExceptionsSv_*` (2) | First mismatch on `..._Smode`: store access fault at VA 0x1_4000_0014. BOOM's `mtval` is the VA; Sail's is truncated to 32 physical-address bits. Looks like a Sail quirk; the others were not checked individually. |
| `Zalrsc-sc.{w,d}` (2) | The test retries a failing SC 100 times; BOOM's `debug_sc_fail_cnt` assertion stops the simulation (§20). |
| `PMPSm_cfg_A_tor_{bot,zero}`, `PMPSm_cfg_tor_check-01` (3) | Fetch-block PMP boundary faults. See section 18.6. |
| `ExceptionsSvZalrsc*`, `ExceptionsSvSm_*_mprv_*` (5) | Sail truncates the faulting virtual address in `mtval`. See section 18.6. |
| `Sm_F`, `Sm_mcsr_access`, `Sm_mcsr_cntr`, `Sstvecd`, `Zicntr{S,Sm,U}` (7) | FP dirty-state bug, architecture-ID config, timer assumption, WARL choice, and trap retirement bug. See section 18.6. |


### 18.6 ACT4 triage on 2026-11-07

The 15 tests that were not triaged in section 18.5 were run with seed 1.
See `docs/ACT4_TRIAGE.md` for the per-test evidence and regression results.
The initial run reproduced all 15 failures. Two upstream bugs were found:
FP loads did not mark FS Dirty, and ECALL/EBREAK incremented `instret`.
The port fixes both. The ACT4 config also described `marchid` as zero;
BOOM implements the constant value 2.

The full DUT rerun completed with **589 pass, 31 fail out of 620 tests**,
all with seed 1. `Sm_F`, `Sm_mcsr_access`, `ZicntrS`, and `ZicntrSm` pass.
`ZicntrU` still fails after the retirement-count fix. The three assigned
PMP boundary defects remained unfixed in that baseline. Section 6.2.1 and
the fetch PMP follow-up in `ACT4_TRIAGE.md` record the later fix.
The 31 baseline failures include the two
existing SC debug assertions. The small riscv-tests run with RVFI passed
all 321 selected tests. All three directed VM tests passed.

The exception tests return the faulting virtual address in `mtval`.
Sail truncates it to the physical address width for these access faults.
Privileged ISA 1.12 section 3.1.16 requires the virtual address when paging
is enabled. The DUT behavior is retained.

`Sstvecd` writes bit 38 of `stvec`. BOOM sign-extends its 39-bit register;
Sail retains the full value. Section 4.1.2 permits a WARL BASE field.
This difference is retained. Sail has no config field for this width.

`Sm_mcsr_cntr` requires a read of `time` to complete within 100 timer ticks
of a write to `mtime`. BOOM emulates `time` in software. The measured gap
is 687 ticks. Section 3.2.1 gives no such latency bound. The test needs a
platform-dependent bound; the core must not be changed to satisfy it.

The remaining `ZicntrU` failure is in ACT4's expected instruction count.
At WFI, the self-checking ELF tries M-mode invisible emulation before
forwarding the trap to S-mode. The signature ELF goes directly to S-mode.
The self-checking ELF returns `0x108` on both BOOM and Sail; its signature
expects `0x9f`. The extra 105 M-mode handler instructions explain the
whole difference. Sections 3.1.8 and 3.1.10 require counting these retired
handler instructions. This is a framework defect, category (d).

---

## 19. Upstream bugs fixed in the port

ACT4 found the first two bugs in upstream BOOM; review found the later ones.
Each fix is its own commit at the end of the history, small and commented, so
any of them can be reverted.

### 19.1 Faulting AMO/SC sent to the D$

Commit "lsu: keep misaligned and access-faulting AMO/SC out of the D$".

**Mechanism.** §13.5: an AMO enters the store execute queue before commit as
soon as its STQ entry has a valid address and data and no `exception` flag.
The address is written in the EXE cycle (`stq_addr.write(stq_idx)(_.valid :=
...)`). The `exception` flag comes from `mem_xcpt_*`, which is registered: it
is set one cycle *later*. Upstream guards against this only for page faults:

```scala
stq_addr(stq_idx).valid := (!exe_agen_killed(w) || will_fire_store_retry(w)) && !pf_st(w) // Prevent AMOs from executing!
```

A misaligned (`ma_st`) or access-faulting (`ae_st`) AMO or SC therefore had a
valid address for one cycle without the exception flag. If its data was
already there, it was copied into the execute queue in that cycle. The next
cycle `mem_xcpt_valid` holds store commits back for one cycle
(`can_fire_store_commit_slow` requires `!mem_xcpt_valid`), but the queue
entry is a copy taken before `exception` was set, so the AMO was then sent to
the D$. The D$
performed it (changing memory, for an access that should have trapped), and
its response arrived for a ROB entry the exception had already flushed:
`[rob] writeback occurred to an invalid ROB entry`.

**Fix.** Hold the address invalid for every store fault known in EXE:

```scala
stq_addr.write(stq_idx)(_.valid := (!exe_agen_killed(w) || will_fire_store_retry(w)) &&
                                    !pf_st(w) && !ma_st(w) && !ae_st(w))
```

For plain stores this changes nothing observable, since they wait for commit
and a faulting store never commits.

### 19.2 NBDTLB ignored the PTW's page fault

Commit "lsu/tlb: honour the PTW's page fault in the NBDTLB".

**Mechanism.** Current rocket-chip's PTW reports a leaf PTE that uses reserved
encodings (reserved bits 63:54 set, the N bit without Svnapot, PBMT without
Svpbmt) through `resp.pf`, and leaves the PTE's permission bits as they are.
Rocket's own TLB stores `pf` per entry and turns it into a fault. BOOM's
NBDTLB was derived from an older rocket TLB, from before the PTW reported such
PTEs this way, and never read `pf`. The entry got the PTE's R/W/X bits, so
loads and stores to such a page succeeded instead of page-faulting.
(Instruction fetch was correct: the I-TLB is rocket's.)

**Fix.** `NBDTLBEntryData` gets a `pf` bit, set from `io.ptw.resp.payload.pf`
on refill, and it is ORed into the fault arrays as in rocket's TLB:

```scala
val ptw_pf_array = widthMap(w => Cat(False, entries(w).map(_.pf).asUInt).asUInt)
val pf_ld_array  = widthMap(w => Mux(cmd_read(w), ~(r_array(w) | ptw_ae_array(w)) | ptw_pf_array(w), zeroA))
val pf_st_array  = widthMap(w => Mux(cmd_write_perms(w), ~(w_array(w) | ptw_ae_array(w)) | ptw_pf_array(w), zeroA))
val pf_inst_array = widthMap(w => ~(x_array(w) | ptw_ae_array(w)) | ptw_pf_array(w))
```

### 19.3 NBDTLB installed refills raced by `sfence.vma`

Commit "lsu/tlb: drop an NBDTLB refill raced by sfence.vma". Found in review.

**Mechanism.** A D-TLB miss starts a page-table walk, which takes many cycles
(each PTE is read through the D$). If an `sfence.vma` executes in that window,
the walk may have read PTEs that software has since changed. The NBDTLB
tracks this: its state machine moves to `s_wait_invalidate`, and
`invalidate_refill` is true in that state, in `s_request`, and when the fence
and the response arrive in the same cycle. Upstream BOOM computed
`invalidate_refill` but never used it, so the walk's result was installed as a
valid entry anyway and a stale translation survived the fence.

The window is narrow in practice: `sfence.vma` is a unique instruction, so
every older load and store has committed before it executes, and the walk must
belong to an instruction that was squashed (a wrong-path load, or one flushed
by an exception) while its walk was still running.

**Fix.** As in rocket's TLB, invalidate the entry just written when
`invalidate_refill` holds:

```scala
e.insert(r_refill_tag, io.ptw.resp.payload.level, newEntry)
when (invalidate_refill) { e.invalidate() }
```

for both the superpage and the sectored refill (rocket also leaves the special
entry alone). `boom.lsu.NBDTLBSpec` drives the NBDTLB's PTW port directly: a
miss, a walk, a flush-all `sfence.vma` while it is outstanding, then the
response. Without the fix the next lookup hits; with it, it misses.

### 19.4 NBDTLB turned PTE-read access faults into page faults

Commit "lsu/tlb: keep the PTW's ae_ptw in the NBDTLB". Found in review.

**Mechanism.** The PTW reports two access-fault flags. `ae_ptw`: a PTE could
not be read, because its address is outside physical memory or PMP denies the
read (`PTW.scala`, `resp_ae_ptw`). `ae_final`: the walk succeeded but the
final page is not accessible. Rocket's TLB keeps both per entry; `ae_ptw`
suppresses the page fault, and either flag clears the entry's `pr`/`pw`/`px`,
so the access raises an access fault. BOOM's NBDTLB had a single `ae` bit,
filled from `ae_final`, and used it in `ae_ptw`'s place. A walk that failed on
a PTE read returned no permissions and no `ae_final`, so loads and stores got
a page fault (cause 13/15) instead of an access fault (5/7).

**Fix.** Store both flags and use them as rocket does:

```scala
val ptw_ae_array   = widthMap(w => Cat(False, entries(w).map(_.ae_ptw).asUInt).asUInt)
val final_ae_array = widthMap(w => Cat(False, entries(w).map(_.ae_final).asUInt).asUInt)
val pr_array = widthMap(w => Cat(Fill(nPhysicalEntries, prot_r(w)), normal_entries(w).map(_.pr).asUInt).asUInt
                             & ~(ptw_ae_array(w) | final_ae_array(w)))   // pw, px alike
```

A side effect, also as in rocket: `ae_final` no longer suppresses the page
fault, so a leaf whose permissions fail *and* whose page is inaccessible now
raises the page fault (the LSU gives `pf` priority over `ae`), which is the
order the privileged spec gives.

**Test.** `tests/vm/ae_ptw.S` runs in S-mode under Sv39 and makes loads and
stores whose walk hits a pointer PTE outside physical memory, and a page-table
page that PMP denies. It expects access faults with the right `mtval`, then
checks that a mapped page still works. It passes on Sail with ACT4's BOOM
configuration; on BOOM it fails at its first check without the fix and passes
with it (`VmTestsSpec`; build with `tests/vm/build.sh`).

---

### 19.5 Fetch PMP boundaries

The parcel PMP checks in section 6.2.1 fix upstream issue #786 in the port.
The full ACT4 rerun passes 604 of 620 tests, up from 589 of 620.
All 15 fetch-PMP baseline failures pass. Both `SvPMP` physical-address
cases still fail before the execute check because Sail truncates the
store-fault virtual address in `mtval`. See `ACT4_TRIAGE.md`.
Small and medium riscv-tests with RVFI each pass all 321 selected tests.
The full VM suites each pass all four tests. The directed `fetch_pmp` test
also passes on Sail. Large benchmarks pass all nine tests on the fix and on
`3c0c707`. Every benchmark has the same cycle and retirement counts, so the
measured IPC change is 0%. This does not measure the physical critical path.

## 20. Known differences and limitations

**Kept from upstream (non-conformances):**

- **`debug_sc_fail_cnt`.** The D$ asserts after 100 consecutive failed SCs to
  one address. That is legal software behaviour, but the assertion stops
  simulation.
- **`instret_overflow`.** CSR writes happen at execute while retirement is
  counted at commit, so a write to `minstret` and the instruction count
  interact differently than the test expects.
- `mtval` is 0 on illegal instructions; `time` is not implemented as a CSR.

**Port differences:**

- No clock gating; no ECC on caches or TLBs (`tagECC`/`dataECC` must be
  `None`); no TileLink atomics or hints; random D$ replacement only.
- Not ported: RoCC, the software/DPI branch
  predictor, the trace encoder and trace-core ingress, TraceGen, printf
  tracing, `PlusArg` (the hang detector uses its default, bit 13).
- Synchronous-read memories are modelled as a registered address plus an
  asynchronous read (§3.3). This matches Chisel simulation semantics; mapping
  them onto SRAM macros or FPGA block RAM has not been tried.
- Only the small configuration is exercised by riscv-tests, ACT4 and the
  formal harness. The other configs elaborate (`BoomElabSpec`) but have not
  run programs.

---

## 21. File map

| Path | Contents |
|------|----------|
| `src/main/scala/rocketchip/config/` | `Parameters`, `SystemParams`, memory map |
| `src/main/scala/rocketchip/util/` | Chisel-compatible utilities: Queue, Arbiter, LFSR, `Cat`, `ChiselLayout`, `BitPat`/`DecodeLogic`, `readSyncChisel` |
| `src/main/scala/rocketchip/tilelink/` | `ClientMetadata`, TL edge helpers |
| `src/main/scala/rocketchip/rocket/` | CSR file, TLB, PTW, PMP, ALU, MulDiv, RVC, instruction tables, HellaCache bundles |
| `src/main/scala/rocketchip/tile/` | `CoreParams`/`TileParams`, FPU blocks, interrupts, custom CSRs |
| `src/main/scala/hardfloat/` | berkeley-hardfloat |
| `src/main/scala/boom/common/` | parameters, configs, `MicroOp`, constants, `BoomTile`, `SpinalBoomConfig` |
| `src/main/scala/boom/util/` | branch-mask helpers, ring-buffer arithmetic, misc |
| `src/main/scala/boom/ifu/` | frontend, I$, FTQ, fetch buffer, `bpd/` predictors |
| `src/main/scala/boom/exu/` | decode, rename, dispatch, issue, register files, execution units, FP pipeline, ROB, core, RVFI tracker |
| `src/main/scala/boom/lsu/` | LSU, NBDTLB, D$, MSHRs, prefetcher |
| `src/main/scala/boom/system/` | `BoomSimTop`, `RvfiMonitor`, `BoomFormalTop`, `GenFormal` |
| `src/test/scala/` | elaboration tests, hardfloat test, NBDTLB test, `BoomSim`, riscv-tests, ACT4 and `tests/vm` runners |
| `tests/vm/` | directed virtual-memory test programs and their build script |
| `formal/` | riscv-formal wrapper, checks, run script, RVFI monitors |
| `act4/` | ACT4 DUT configuration and build script |
| `PORTING_NOTES.md` | every Chisel→Spinal decision, deliberate differences, bug notes |

## Stage chains after the pipeline integration

Transaction delays use `spinal.lib.misc.pipeline` from SpinalHDL 1.15.0.
`FixedPipeline` implements fixed delays. `BranchPipeline.boundary` applies
branch kill and mask updates before each register. `HoldStageLink` keeps the
rename and arbitration hold rules. `GatedPipeline` implements registers with
explicit capture enables. Build links only after their consumers exist.
See `PORTING_NOTES.md` for valid, reset, payload and enable conventions.

The frontend, predictor banks, execute paths, D-cache stages, LSU paths and
PTW L2 lookup use these helpers. The integration keeps master's FP-load
FS Dirty update, trap exclusion from `instret`, load-load replay and optional
age-matrix issue unit. The default issue unit stays unchanged.

Manual registers remain for independent unreset ROB-flush captures, SRAM
read ports and alignment, freelist allocation selection, PTW replacement
snapshots and event flags, and MSHR metadata waits and hazard counters.
Queues, FSMs, CAMs, counters, pointers, rename storage and ROB storage remain
state. Assertion and trace delays remain observation code. The PTW L2 lookup
is already converted. It is not a remaining chain.

The cycle gate uses master `39fb5f6` behavior recordings. Added observations
live in the test tree. See `docs/CYCLE_EQUIVALENCE.md` for coverage limits and
for re-recording after an intentional behavior change. A matching trace is
bounded simulation evidence.

Integration results: quick 265/265, extra 180/180 and full 1,665/1,665
match with zero differences. Specs pass 52/52, small ISA with RVFI 321/321,
VM 3/3, load-load ordering 1/1 and matrix 3/3. ACT4 remains 589/620 with
the same 31 failing names. See `PIPELINE_INTEGRATION.md` for evidence.
